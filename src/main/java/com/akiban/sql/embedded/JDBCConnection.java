/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.embedded;

import com.akiban.sql.server.ServerServiceRequirements;
import com.akiban.sql.server.ServerSessionBase;
import com.akiban.sql.server.ServerSessionTracer;

import com.akiban.sql.StandardException;
import com.akiban.sql.parser.DMLStatementNode;
import com.akiban.sql.parser.SQLParser;
import com.akiban.sql.parser.SQLParserException;
import com.akiban.sql.parser.StatementNode;

import com.akiban.ais.model.UserTable;
import com.akiban.qp.loadableplan.LoadablePlan;
import com.akiban.qp.memoryadapter.MemoryAdapter;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.operator.StoreAdapter;
import com.akiban.qp.persistitadapter.PersistitAdapter;
import com.akiban.server.api.DDLFunctions;
import com.akiban.server.error.ErrorCode;
import com.akiban.server.error.InvalidOperationException;
import com.akiban.server.error.SQLParseException;
import com.akiban.server.error.SQLParserInternalException;
import com.akiban.server.error.UnsupportedSQLException;
import static com.akiban.server.service.dxl.DXLFunctionsHook.DXLFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;

public class JDBCConnection extends ServerSessionBase implements Connection {
    private boolean closed, autoCommit;
    private JDBCWarning warnings;
    private Properties clientInfo = new Properties();
    private String schema;
    private EmbeddedOperatorCompiler compiler;
    private List<JDBCResultSet> openResultSets = new ArrayList<JDBCResultSet>();

    private static final Logger logger = LoggerFactory.getLogger(JDBCConnection.class);

    protected JDBCConnection(ServerServiceRequirements reqs, Properties info) {
        super(reqs);
        setProperties(info);
        session = reqs.sessionService().createSession();
        sessionTracer = new ServerSessionTracer(0, false);
        autoCommit = true;
    }

    @Override
    protected void sessionChanged() {
    }

    @Override
    public void notifyClient(QueryContext.NotificationLevel level, ErrorCode errorCode, String message) {
        if (shouldNotify(level)) {
            addWarning(new JDBCWarning(level, errorCode, message));
        }
    }

    protected void addWarning(JDBCWarning warning) {
        if (warnings == null)
            warnings = warning;
        else
            warnings.setNextWarning(warning);
    }
    
    @Override
    public StoreAdapter getStore(final UserTable table) {
        if (table.hasMemoryTableFactory()) {
            return adapters.get(StoreAdapter.AdapterType.MEMORY_ADAPTER);
        }
        return adapters.get(StoreAdapter.AdapterType.PERSISTIT_ADAPTER);
    }

    protected InternalStatement compileInternalStatement(String sql) {
        logger.debug("Compile: {}");
        EmbeddedQueryContext context = new EmbeddedQueryContext(this);
        updateAIS(context);
        StatementNode sqlStmt;
        SQLParser parser = getParser();
        try {
            sqlStmt = parser.parseStatement(sql);
        } 
        catch (SQLParserException ex) {
            throw new SQLParseException(ex);
        }
        catch (StandardException ex) {
            throw new SQLParserInternalException(ex);
        }
        if (sqlStmt instanceof DMLStatementNode)
            return compiler.compileInternalStatement(this, (DMLStatementNode)sqlStmt, parser.getParameterList(), context);
        throw new UnsupportedSQLException("Not DML: ", sqlStmt);
    }

    protected void updateAIS(EmbeddedQueryContext context) {
        boolean locked = false;
        try {
            context.lock(DXLFunction.UNSPECIFIED_DDL_READ);
            locked = true;
            DDLFunctions ddl = reqs.dxl().ddlFunctions();
            long currentTimestamp = ddl.getTimestamp();
            if (aisTimestamp == currentTimestamp) 
                return;             // Unchanged.
            aisTimestamp = currentTimestamp;
            ais = ddl.getAIS(session);
        }
        finally {
            if (locked) {
                context.unlock(DXLFunction.UNSPECIFIED_DDL_READ);
            }
        }
        rebuildCompiler();
    }

    protected void rebuildCompiler() {
        initParser();
        compiler = EmbeddedOperatorCompiler.create(this);
        initAdapters(compiler);
    }

    protected void openingResultSet(JDBCResultSet resultSet) {
        if (!isTransactionActive()) {
            logger.debug("BEGIN TRANSACTION");
            beginTransaction();
        }
        openResultSets.add(resultSet);
    }

    protected void closingResultSet(JDBCResultSet resultSet) {
        openResultSets.remove(resultSet);
        if (autoCommit && openResultSets.isEmpty()) {
            commitTransaction();
            logger.debug("COMMIT TRANSACTION");
        }
    }

    /* Wrapper */

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("Not supported");
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    /* Connection */

    @Override
    public Statement createStatement() throws SQLException {
        return new JDBCStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return new JDBCPreparedStatement(this, compileInternalStatement(sql));
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return new JDBCCallableStatement(this, compileInternalStatement(sql));
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return sql;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (this.autoCommit != autoCommit) {
            if (transaction != null) 
                commit();
            this.autoCommit = autoCommit;
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        if (autoCommit)
            throw new JDBCException("Not allowed in auto-commit mode");
        try {
            commitTransaction();
        }
        catch (InvalidOperationException ex) {
            throw new JDBCException(ex);
        }
    }

    @Override
    public void rollback() throws SQLException {
        if (autoCommit)
            throw new JDBCException("Not allowed in auto-commit mode");
        try {
            rollbackTransaction();
        }
        catch (InvalidOperationException ex) {
            throw new JDBCException(ex);
        }
    }

    @Override
    public void close() throws SQLException {
        if (isTransactionActive())
            rollback();
        while (!openResultSets.isEmpty()) {
            openResultSets.get(0).close();
        }
        this.closed = true;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        this.transactionDefaultReadOnly = transactionDefaultReadOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return transactionDefaultReadOnly;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
    }

    @Override
    public String getCatalog() throws SQLException {
        return null;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        if (level != TRANSACTION_SERIALIZABLE)
            throw new SQLException("Only TRANSACTION_SERIALIZABLE supported");
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return TRANSACTION_SERIALIZABLE;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        warnings = null;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency)
            throws SQLException {
        if ((resultSetType != ResultSet.TYPE_FORWARD_ONLY) ||
            (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY))
            throw new SQLFeatureNotSupportedException();
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType,
                                              int resultSetConcurrency)
            throws SQLException {
        if ((resultSetType != ResultSet.TYPE_FORWARD_ONLY) ||
            (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY))
            throw new SQLFeatureNotSupportedException();
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType,
                                         int resultSetConcurrency) throws SQLException {
        if ((resultSetType != ResultSet.TYPE_FORWARD_ONLY) ||
            (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY))
            throw new SQLFeatureNotSupportedException();
        return prepareCall(sql);
    }

    @Override
    public Map<String,Class<?>> getTypeMap() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTypeMap(java.util.Map<String,Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        if (holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT)
            throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency,
                                     int resultSetHoldability) throws SQLException {
        if ((resultSetType != ResultSet.TYPE_FORWARD_ONLY) ||
            (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) ||
            (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT))
            throw new SQLFeatureNotSupportedException();
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType,
                                              int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        if ((resultSetType != ResultSet.TYPE_FORWARD_ONLY) ||
            (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) ||
            (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT))
            throw new SQLFeatureNotSupportedException();
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType,
                                         int resultSetConcurrency,
                                         int resultSetHoldability) throws SQLException {
        if ((resultSetType != ResultSet.TYPE_FORWARD_ONLY) ||
            (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) ||
            (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT))
            throw new SQLFeatureNotSupportedException();
        return prepareCall(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
            throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS)
            throw new SQLFeatureNotSupportedException();
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int columnIndexes[])
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String columnNames[])
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return !closed;
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        clientInfo.put(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        clientInfo.putAll(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return clientInfo.getProperty(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return clientInfo;
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    //@Override // JDK 1.7
    public void setSchema(String schema) throws SQLException {
        this.schema = schema;
    }

    //@Override // JDK 1.7
    public String getSchema() throws SQLException {
        return schema;
    }

    //@Override // JDK 1.7
    public void abort(Executor executor) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    //@Override // JDK 1.7
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    //@Override // JDK 1.7
    public int getNetworkTimeout() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }
}
