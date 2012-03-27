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

package com.akiban.server.test.it.dxl;

import com.akiban.ais.metamodel.io.MessageTarget;
import com.akiban.ais.metamodel.io.Writer;
import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.server.error.UnsupportedIndexSizeException;
import com.akiban.server.store.SchemaManager;
import com.akiban.server.store.TableDefinition;
import com.akiban.server.test.it.ITBase;
import com.akiban.sql.parser.SQLParserException;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static junit.framework.Assert.*;

public class AtomicSchemaChangesIT extends ITBase
{
    // The tests catch Throwable, because some of the breakage scenarios actually survive the DDL and AIS
    // layers, and instead cause createTable to fail an assertion.

    @Test
    public void tryBadSyntax() throws Exception
    {
        createInitialSchema();
        checkInitialSchema();
        try {
            createTable("s", "bad_syntax",
                        "foo bar");
            fail();
        } catch (RuntimeException e) {
            // expected
        }
        checkInitialSchema();
    }

    @Ignore("bug941657 - Invalid grouping foreign keys not handled")
    @Test
    public void tryFailValidation() throws Exception
    {
        createInitialSchema();
        checkInitialSchema();
        try {
            createTable("s", "fail_validation",
                        "bid int not null primary key",
                        "pid int",
                        "grouping foreign key (pid) references parent(no_such_column)");
            fail();
        } catch (Throwable e) {
            // expected
        }
        checkInitialSchema();
    }

    @Test
    public void tryFailAISCreation_1() throws Exception
    {
        createInitialSchema();
        checkInitialSchema();
        try {
            createTable("s", "fail_ais_creation_1",
                        "bid int not null primary key",
                        "pid int",
                        "unique(xyz)");
            fail();
        } catch (Throwable e) {
            // expected
        }
        checkInitialSchema();
    }

    @Test
    public void tryRootPrimaryKeyTooLarge() throws Exception {
        createInitialSchema();
        checkInitialSchema();
        try {
            createTable("s", "t1",
                        "id varchar(2050) not null",
                        "primary key(id)");
            Assert.fail("Expected table to be rejected");
        } catch (UnsupportedIndexSizeException e) {
            // expected
        }
        checkInitialSchema();
    }

    @Test
    public void tryRootSecondaryKeyTooLarge() throws Exception {
        createInitialSchema();
        checkInitialSchema();
        try {
            createTable("s", "t1",
                        "id int not null",
                        "c1 varchar(2050)",
                        "unique(c1)",
                        "primary key(id)");
            Assert.fail("Expected table to be rejected");
        } catch (UnsupportedIndexSizeException e) {
            // expected
        }
        checkInitialSchema();
    }

    @Test
    public void tryChildPrimaryKeyTooLarge() throws Exception {
        createInitialSchema();
        checkInitialSchema();
        try {
            createTable("s", "child2",
                        "id varchar(2052) not null",
                        "pid int",
                        "primary key(id)",
                        "grouping foreign key(pid) references parent(pid)");
            Assert.fail("Expected table to be rejected");
        } catch (UnsupportedIndexSizeException e) {
            // expected
        }
        checkInitialSchema();
    }

    @Test
    public void tryChildSecondaryKeyTooLarge() throws Exception {
        createInitialSchema();
        checkInitialSchema();
        try {
            createTable("s", "child2",
                        "id int not null",
                        "pid int",
                        "filler varchar(2052)",
                        "primary key(id)",
                        "unique(filler)",
                        "grouping foreign key(pid) references parent(pid)");
            Assert.fail("Expected table to be rejected");
        } catch (UnsupportedIndexSizeException e) {
            // expected
        }
        checkInitialSchema();
    }

    private void createInitialSchema() throws Exception
    {
        createTable("s", "parent",
                    "pid int not null primary key",
                    "filler int");
        createTable("s", "child",
                    "cid int not null primary key",
                    "pid int",
                    "grouping foreign key (pid) references parent(pid)");
        createGroupingFKIndex("s", "child", "__akiban_cp", "pid");
        expectedAIS = serialize(ais());
    }

    private void checkInitialSchema() throws Exception
    {
        checkInitialAIS();
        checkInitialDDL();
    }

    private void checkInitialAIS() throws Exception
    {
        ByteBuffer copy = expectedAIS.duplicate();
        ByteBuffer ais = serialize(ais());
        assertEquals(expectedAIS, ais);
        expectedAIS = copy;
    }

    private void checkInitialDDL() throws Exception
    {
        for (Map.Entry<String, TableDefinition> entry : createTableStatements("s").entrySet()) {
            String table = entry.getKey();
            String ddl = entry.getValue().getDDL();
            if (table.equals("parent")) {
                assertEquals(PARENT_DDL, ddl);
            } else if (table.equals("child")) {
                assertEquals(CHILD_DDL, ddl);
            } else {
                assertTrue(String.format("%s: %s", table, ddl), false);
            }
        }
    }

    private AkibanInformationSchema ais()
    {
        return ddl().getAIS(session());
    }

    private ByteBuffer serialize(AkibanInformationSchema ais) throws Exception
    {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        new Writer(new MessageTarget(buffer)).save(ais);
        buffer.flip();
        return buffer;
    }

    private Map<String, TableDefinition> createTableStatements(String schema) throws Exception
    {
        return serviceManager().getServiceByClass(SchemaManager.class).getTableDefinitions(session(), schema);
    }

    private static final int BUFFER_SIZE = 100000; // 100K
    private static final String PARENT_DDL =
        "create table `s`.`parent`(`pid` int NOT NULL, `filler` int, PRIMARY KEY(`pid`)) engine=akibandb";
    private static final String CHILD_DDL =
        "create table `s`.`child`(`cid` int NOT NULL, `pid` int, PRIMARY KEY(`cid`), "+
            "CONSTRAINT `__akiban_cp` FOREIGN KEY `__akiban_cp`(`pid`) REFERENCES `parent`(`pid`)) engine=akibandb";
    private ByteBuffer expectedAIS;
}
