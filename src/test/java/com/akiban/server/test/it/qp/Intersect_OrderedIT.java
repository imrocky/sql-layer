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

package com.akiban.server.test.it.qp;

import com.akiban.qp.expression.IndexBound;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.operator.API;
import com.akiban.qp.operator.Operator;
import com.akiban.qp.row.RowBase;
import com.akiban.qp.rowtype.IndexRowType;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.server.api.dml.SetColumnSelector;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.expression.Expression;
import com.akiban.server.expression.std.FieldExpression;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static com.akiban.qp.operator.API.*;
import static com.akiban.server.expression.std.Expressions.field;
import static junit.framework.Assert.fail;

// Single-branch testing. See MultiIndexCrossBranchIT for cross-branch testing.

public class Intersect_OrderedIT extends OperatorITBase
{
    @Before
    public void before()
    {
        parent = createTable(
            "schema", "parent",
            "pid int not null primary key",
            "x int",
            "y int");
        createIndex("schema", "parent", "x", "x");
        createIndex("schema", "parent", "y", "y");
        child = createTable(
            "schema", "child",
            "cid int not null primary key",
            "pid int",
            "z int",
            "grouping foreign key (pid) references parent(pid)");
        createIndex("schema", "child", "z", "z");
        schema = new Schema(rowDefCache().ais());
        parentRowType = schema.userTableRowType(userTable(parent));
        childRowType = schema.userTableRowType(userTable(child));
        parentPidIndexRowType = indexType(parent, "pid");
        parentXIndexRowType = indexType(parent, "x");
        parentYIndexRowType = indexType(parent, "y");
        childZIndexRowType = indexType(child, "z");
        coi = groupTable(parent);
        adapter = persistitAdapter(schema);
        queryContext = queryContext(adapter);
        db = new NewRow[]{
            // 0x: Both index scans empty
            // 1x: Left empty
            createNewRow(parent, 1000L, -1L, 12L),
            createNewRow(parent, 1001L, -1L, 12L),
            createNewRow(parent, 1002L, -1L, 12L),
            // 2x: Right empty
            createNewRow(parent, 2000L, 22L, -1L),
            createNewRow(parent, 2001L, 22L, -1L),
            createNewRow(parent, 2002L, 22L, -1L),
            // 3x: Both non-empty, and no overlap
            createNewRow(parent, 3000L, 31L, -1L),
            createNewRow(parent, 3001L, 31L, -1L),
            createNewRow(parent, 3002L, 31L, -1L),
            createNewRow(parent, 3003L, 9999L, 32L),
            createNewRow(parent, 3004L, 9999L, 32L),
            createNewRow(parent, 3005L, 9999L, 32L),
            // 4x: left contains right
            createNewRow(parent, 4000L, 44L, -1L),
            createNewRow(parent, 4001L, 44L, 44L),
            createNewRow(parent, 4002L, 44L, 44L),
            createNewRow(parent, 4003L, 44L, 9999L),
            // 5x: right contains left
            createNewRow(parent, 5000L, -1L, 55L),
            createNewRow(parent, 5001L, 55L, 55L),
            createNewRow(parent, 5002L, 55L, 55L),
            createNewRow(parent, 5003L, 9999L, 55L),
            // 6x: overlap but neither side contains the other
            createNewRow(parent, 6000L, -1L, 66L),
            createNewRow(parent, 6001L, -1L, 66L),
            createNewRow(parent, 6002L, 66L, 66L),
            createNewRow(parent, 6003L, 66L, 66L),
            createNewRow(parent, 6004L, 66L, 9999L),
            createNewRow(parent, 6005L, 66L, 9999L),
            // 7x: parent with no children
            createNewRow(parent, 7000L, 70L, 70L),
            // 8x: parent with children
            createNewRow(parent, 8000L, 88L, 88L),
            createNewRow(child, 800000L, 8000L, 88L),
            createNewRow(parent, 8001L, 88L, 88L),
            createNewRow(child, 800100L, 8001L, 88L),
            createNewRow(child, 800101L, 8001L, 88L),
            createNewRow(parent, 8002L, 88L, 88L),
            createNewRow(child, 800200L, 8002L, 88L),
            createNewRow(child, 800201L, 8002L, 88L),
            createNewRow(child, 800202L, 8002L, 88L),
            // 9x child with no parent
            createNewRow(child, 900000L, 9000L, 99L),
            // 12x right join (child on right)
            createNewRow(child, 1200000L, null, 12L),
        };
        use(db);
    }

    private int parent;
    private int child;
    private RowType parentRowType;
    private RowType childRowType;
    private IndexRowType parentPidIndexRowType;
    private IndexRowType parentXIndexRowType;
    private IndexRowType parentYIndexRowType;
    private IndexRowType childZIndexRowType;

    // IllegalArumentException tests

    @Test
    public void testInputNull()
    {
        try {
            intersect_Ordered(null,
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              null,
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testInputTypeNull()
    {
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              null,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              null,
                              1,
                              1,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testJoinType()
    {
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true),
                              null,
                              IntersectOutputOption.OUTPUT_LEFT);
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true),
                              JoinType.FULL_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testOutputOptionNull()
    {
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              null);
        } catch (IllegalArgumentException e) {
        }
    }

    public void testJoinTypeAndOrderingConsistency()
    {
        intersect_Ordered(groupScan_Default(coi),
                          groupScan_Default(coi),
                          parentXIndexRowType,
                          parentYIndexRowType,
                          1,
                          1,
                          ascending(true),
                          JoinType.INNER_JOIN,
                          IntersectOutputOption.OUTPUT_LEFT);
        intersect_Ordered(groupScan_Default(coi),
                          groupScan_Default(coi),
                          parentXIndexRowType,
                          parentYIndexRowType,
                          1,
                          1,
                          ascending(true),
                          JoinType.INNER_JOIN,
                          IntersectOutputOption.OUTPUT_RIGHT);
        intersect_Ordered(groupScan_Default(coi),
                          groupScan_Default(coi),
                          parentXIndexRowType,
                          parentYIndexRowType,
                          1,
                          1,
                          ascending(true),
                          JoinType.LEFT_JOIN,
                          IntersectOutputOption.OUTPUT_LEFT);
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true),
                              JoinType.LEFT_JOIN,
                              IntersectOutputOption.OUTPUT_RIGHT);
            fail();
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true),
                              JoinType.RIGHT_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
        intersect_Ordered(groupScan_Default(coi),
                          groupScan_Default(coi),
                          parentXIndexRowType,
                          parentYIndexRowType,
                          1,
                          1,
                          ascending(true),
                          JoinType.RIGHT_JOIN,
                          IntersectOutputOption.OUTPUT_RIGHT);
    }

    @Test
    public void testOrderingColumns()
    {
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              -1,
                              1,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              3,
                              1,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              -1,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              3,
                              ascending(true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              -1,
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
        try {
            intersect_Ordered(groupScan_Default(coi),
                              groupScan_Default(coi),
                              parentXIndexRowType,
                              parentYIndexRowType,
                              1,
                              1,
                              ascending(true, true),
                              JoinType.INNER_JOIN,
                              IntersectOutputOption.OUTPUT_LEFT);
            fail();
        } catch (IllegalArgumentException e) {
        }
    }

    // Runtime tests

    @Test
    public void test0x()
    {
        Operator plan = intersectPxPy(0, true);
        RowBase[] expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(0, false);
        expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test1x()
    {
        Operator plan = intersectPxPy(12, true);
        RowBase[] expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(12, false);
        expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test2x()
    {
        Operator plan = intersectPxPy(22, true);
        RowBase[] expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(22, false);
        expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test3x()
    {
        Operator plan = intersectPxPy(31, true);
        RowBase[] expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(32, true);
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(31, false);
        expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(32, true);
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test4x()
    {
        Operator plan = intersectPxPy(44, true);
        RowBase[] expected = new RowBase[]{
            row(parentXIndexRowType, 44L, 4001L),
            row(parentXIndexRowType, 44L, 4002L),
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(44, false);
        expected = new RowBase[]{
            row(parentXIndexRowType, 44L, 4002L),
            row(parentXIndexRowType, 44L, 4001L),
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test5x()
    {
        Operator plan = intersectPxPy(55, true);
        RowBase[] expected = new RowBase[]{
            row(parentXIndexRowType, 55L, 5001L),
            row(parentXIndexRowType, 55L, 5002L),
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(55, false);
        expected = new RowBase[]{
            row(parentXIndexRowType, 55L, 5002L),
            row(parentXIndexRowType, 55L, 5001L),
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test6x()
    {
        Operator plan = intersectPxPy(66, true);
        RowBase[] expected = new RowBase[]{
            row(parentXIndexRowType, 66L, 6002L),
            row(parentXIndexRowType, 66L, 6003L),
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxPy(66, false);
        expected = new RowBase[]{
            row(parentXIndexRowType, 66L, 6003L),
            row(parentXIndexRowType, 66L, 6002L),
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test7x()
    {
        Operator plan = intersectPxCz(70, JoinType.INNER_JOIN, true);
        RowBase[] expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxCz(70, JoinType.INNER_JOIN, false);
        expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test8x()
    {
        Operator plan = intersectPxCz(88, JoinType.INNER_JOIN, true);
        RowBase[] expected = new RowBase[]{
            row(childRowType, 88L, 8000L, 800000L),
            row(childRowType, 88L, 8001L, 800100L),
            row(childRowType, 88L, 8001L, 800101L),
            row(childRowType, 88L, 8002L, 800200L),
            row(childRowType, 88L, 8002L, 800201L),
            row(childRowType, 88L, 8002L, 800202L),
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxCz(88, JoinType.INNER_JOIN, false);
        expected = new RowBase[]{
            row(childRowType, 88L, 8002L, 800202L),
            row(childRowType, 88L, 8002L, 800201L),
            row(childRowType, 88L, 8002L, 800200L),
            row(childRowType, 88L, 8001L, 800101L),
            row(childRowType, 88L, 8001L, 800100L),
            row(childRowType, 88L, 8000L, 800000L),
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test9x()
    {
        Operator plan = intersectPxCz(99, JoinType.INNER_JOIN, true);
        RowBase[] expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxCz(99, JoinType.INNER_JOIN, false);
        expected = new RowBase[]{
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void test12x()
    {
        Operator plan = intersectPxCz(12, JoinType.RIGHT_JOIN, true);
        RowBase[] expected = new RowBase[]{
            row(childRowType, 12L, null, 1200000L),
        };
        compareRows(expected, cursor(plan, queryContext));
        plan = intersectPxCz(12, JoinType.RIGHT_JOIN, false);
        expected = new RowBase[]{
            row(childRowType, 12L, null, 1200000L),
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    @Test
    public void testAllOrderingFieldsNoComparisonFields()
    {
        Operator plan =
            intersect_Ordered(
                indexScan_Default(parentPidIndexRowType),
                indexScan_Default(parentPidIndexRowType),
                parentPidIndexRowType,
                parentPidIndexRowType,
                1,
                1,
                0,
                JoinType.INNER_JOIN,
                IntersectOutputOption.OUTPUT_LEFT);
        RowBase[] expected = new RowBase[]{
            row(parentPidIndexRowType, 1000L),
            row(parentPidIndexRowType, 1001L),
            row(parentPidIndexRowType, 1002L),
            row(parentPidIndexRowType, 2000L),
            row(parentPidIndexRowType, 2001L),
            row(parentPidIndexRowType, 2002L),
            row(parentPidIndexRowType, 3000L),
            row(parentPidIndexRowType, 3001L),
            row(parentPidIndexRowType, 3002L),
            row(parentPidIndexRowType, 3003L),
            row(parentPidIndexRowType, 3004L),
            row(parentPidIndexRowType, 3005L),
            row(parentPidIndexRowType, 4000L),
            row(parentPidIndexRowType, 4001L),
            row(parentPidIndexRowType, 4002L),
            row(parentPidIndexRowType, 4003L),
            row(parentPidIndexRowType, 5000L),
            row(parentPidIndexRowType, 5001L),
            row(parentPidIndexRowType, 5002L),
            row(parentPidIndexRowType, 5003L),
            row(parentPidIndexRowType, 6000L),
            row(parentPidIndexRowType, 6001L),
            row(parentPidIndexRowType, 6002L),
            row(parentPidIndexRowType, 6003L),
            row(parentPidIndexRowType, 6004L),
            row(parentPidIndexRowType, 6005L),
            row(parentPidIndexRowType, 7000L),
            row(parentPidIndexRowType, 8000L),
            row(parentPidIndexRowType, 8001L),
            row(parentPidIndexRowType, 8002L),
        };
        compareRows(expected, cursor(plan, queryContext));
    }
    
    @Test
    public void testRowIntersection()
    {
        Operator parentProject =
            project_Default(
                filter_Default(
                    groupScan_Default(coi),
                    Collections.singleton(parentRowType)),
                parentRowType,
                Arrays.asList((Expression) new FieldExpression(parentRowType, 1),
                              (Expression) new FieldExpression(parentRowType, 2),
                              (Expression) new FieldExpression(parentRowType, 0)));
        Operator childProject =
            project_Default(
                filter_Default(
                    groupScan_Default(coi),
                    Collections.singleton(childRowType)),
                childRowType,
                Arrays.asList((Expression) new FieldExpression(childRowType, 2),
                              (Expression) new FieldExpression(childRowType, 1),
                              (Expression) new FieldExpression(childRowType, 0)));
        Operator plan =
            intersect_Ordered(
                parentProject,
                childProject,
                parentProject.rowType(),
                childProject.rowType(),
                1,
                2,
                1,
                JoinType.RIGHT_JOIN,
                IntersectOutputOption.OUTPUT_RIGHT);
        RowBase[] expected = new RowBase[]{
            row(childRowType, 12L, null, 1200000L),
            row(childRowType, 88L, 8000L, 800000L),
            row(childRowType, 88L, 8001L, 800100L),
            row(childRowType, 88L, 8001L, 800101L),
            row(childRowType, 88L, 8002L, 800200L),
            row(childRowType, 88L, 8002L, 800201L),
            row(childRowType, 88L, 8002L, 800202L),
            row(childRowType, 99L, 9000L, 900000L),
        };
        compareRows(expected, cursor(plan, queryContext));
    }

    private Operator intersectPxPy(int key, boolean ascending)
    {
        Operator plan =
            intersect_Ordered(
                indexScan_Default(
                    parentXIndexRowType,
                    parentXEq(key),
                    ordering(field(parentXIndexRowType, 1), ascending)),
                indexScan_Default(
                    parentYIndexRowType,
                    parentYEq(key),
                    ordering(field(parentYIndexRowType, 1), ascending)),
                parentXIndexRowType,
                parentYIndexRowType,
                1,
                1,
                ascending(ascending),
                JoinType.INNER_JOIN,
                IntersectOutputOption.OUTPUT_LEFT);
        return plan;
    }

    private Operator intersectPxCz(int key, JoinType joinType, boolean ascending)
    {
        Operator plan =
            intersect_Ordered(
                indexScan_Default(
                    parentXIndexRowType,
                    parentXEq(key),
                    ordering(field(parentXIndexRowType, 1), ascending)),
                indexScan_Default(
                    childZIndexRowType,
                    childZEq(key),
                    ordering(field(childZIndexRowType, 1), ascending,
                             field(childZIndexRowType, 2), ascending)),
                    parentXIndexRowType,
                    childZIndexRowType,
                    1,
                    2,
                    ascending(ascending),
                    joinType,
                    IntersectOutputOption.OUTPUT_RIGHT);
        return plan;
    }

    private IndexKeyRange parentXEq(long x)
    {
        IndexBound xBound = new IndexBound(row(parentXIndexRowType, x), new SetColumnSelector(0));
        return IndexKeyRange.bounded(parentXIndexRowType, xBound, true, xBound, true);
    }

    private IndexKeyRange parentYEq(long y)
    {
        IndexBound yBound = new IndexBound(row(parentYIndexRowType, y), new SetColumnSelector(0));
        return IndexKeyRange.bounded(parentYIndexRowType, yBound, true, yBound, true);
    }

    private IndexKeyRange childZEq(long z)
    {
        IndexBound zBound = new IndexBound(row(childZIndexRowType, z), new SetColumnSelector(0));
        return IndexKeyRange.bounded(childZIndexRowType, zBound, true, zBound, true);
    }

    private Ordering ordering(Object... objects)
    {
        Ordering ordering = API.ordering();
        int i = 0;
        while (i < objects.length) {
            Expression expression = (Expression) objects[i++];
            Boolean ascending = (Boolean) objects[i++];
            ordering.append(expression, ascending);
        }
        return ordering;
    }
    
    private boolean[] ascending(boolean ... ascending)
    {
        return ascending;
    }
}
