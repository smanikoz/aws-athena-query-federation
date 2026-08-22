/*-
 * #%L
 * athena-tpcds
 * %%
 * Copyright (C) 2019 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connectors.tpcds;

import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.substrait.model.ColumnPredicate;
import com.amazonaws.athena.connector.substrait.model.LogicalExpression;
import com.amazonaws.athena.connector.substrait.model.SubstraitOperator;
import com.teradata.tpcds.Table;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.amazonaws.athena.connector.lambda.domain.predicate.Constraints.DEFAULT_NO_LIMIT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TPCDSSubstraitFilter} row evaluation, built against the real TPC-DS
 * {@code income_band} table (columns: ib_income_band_sk BIGINT, ib_lower_bound INT, ib_upper_bound INT).
 */
public class TPCDSSubstraitFilterTest
{
    private static final Table INCOME_BAND = TPCDSUtils.validateTable(
            new com.amazonaws.athena.connector.lambda.domain.TableName("tpcds1", "income_band"));

    private static final String SK = "ib_income_band_sk";
    private static final String LOWER = "ib_lower_bound";
    private static final String UPPER = "ib_upper_bound";

    // income_band row layout by column position: [ib_income_band_sk, ib_lower_bound, ib_upper_bound]
    private static List<String> row(String sk, String lower, String upper)
    {
        return Arrays.asList(sk, lower, upper);
    }

    private static LogicalExpression leaf(String col, SubstraitOperator op, Object value)
    {
        return new LogicalExpression(new ColumnPredicate(col, op, value, null));
    }

    private static boolean matches(LogicalExpression expr, List<String> row)
    {
        return TPCDSSubstraitFilter.forExpression(expr, INCOME_BAND).matches(row);
    }

    @Test
    public void from_WhenNoQueryPlan_ReturnsNull()
    {
        Constraints noPlan = new Constraints(
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                DEFAULT_NO_LIMIT, Collections.emptyMap(), null);
        assertNull(TPCDSSubstraitFilter.from(noPlan, INCOME_BAND));
    }

    @Test
    public void equalOnBigintColumn()
    {
        LogicalExpression expr = leaf(SK, SubstraitOperator.EQUAL, 7L);
        assertTrue(matches(expr, row("7", "0", "10000")));
        assertFalse(matches(expr, row("1", "0", "10000")));
    }

    @Test
    public void notEqualOnBigintColumn()
    {
        LogicalExpression expr = leaf(SK, SubstraitOperator.NOT_EQUAL, 7L);
        assertFalse(matches(expr, row("7", "0", "10000")));
        assertTrue(matches(expr, row("8", "0", "10000")));
    }

    @Test
    public void greaterThanExcludesZeroLowerBound()
    {
        LogicalExpression expr = leaf(LOWER, SubstraitOperator.GREATER_THAN, 0L);
        assertFalse(matches(expr, row("1", "0", "10000")));
        assertTrue(matches(expr, row("2", "10001", "20000")));
    }

    @Test
    public void rangeComparisonsAreNumeric()
    {
        assertTrue(matches(leaf(UPPER, SubstraitOperator.GREATER_THAN_OR_EQUAL_TO, 10000L), row("1", "0", "10000")));
        assertTrue(matches(leaf(UPPER, SubstraitOperator.LESS_THAN_OR_EQUAL_TO, 10000L), row("1", "0", "10000")));
        assertTrue(matches(leaf(UPPER, SubstraitOperator.LESS_THAN, 20000L), row("1", "0", "10000")));
        assertFalse(matches(leaf(UPPER, SubstraitOperator.LESS_THAN, 10000L), row("1", "0", "10000")));
    }

    @Test
    public void isNullAndIsNotNull()
    {
        assertTrue(matches(leaf(LOWER, SubstraitOperator.IS_NOT_NULL, null), row("1", "0", "10000")));
        assertFalse(matches(leaf(LOWER, SubstraitOperator.IS_NULL, null), row("1", "0", "10000")));
        assertTrue(matches(leaf(LOWER, SubstraitOperator.IS_NULL, null), row("1", null, "10000")));
        assertFalse(matches(leaf(LOWER, SubstraitOperator.IS_NOT_NULL, null), row("1", null, "10000")));
    }

    @Test
    public void nullValueFailsComparison()
    {
        assertFalse(matches(leaf(LOWER, SubstraitOperator.GREATER_THAN, 0L), row("1", null, "10000")));
    }

    @Test
    public void andRequiresAllChildren()
    {
        LogicalExpression and = new LogicalExpression(SubstraitOperator.AND, Arrays.asList(
                leaf(LOWER, SubstraitOperator.GREATER_THAN, 0L),
                leaf(UPPER, SubstraitOperator.IS_NOT_NULL, null)));
        assertTrue(matches(and, row("2", "10001", "20000")));
        assertFalse(matches(and, row("1", "0", "10000")));
    }

    @Test
    public void orModelsInList()
    {
        // ib_income_band_sk IN (1,2,3) is rendered as OR(=1, =2, =3)
        LogicalExpression in = new LogicalExpression(SubstraitOperator.OR, Arrays.asList(
                leaf(SK, SubstraitOperator.EQUAL, 1L),
                leaf(SK, SubstraitOperator.EQUAL, 2L),
                leaf(SK, SubstraitOperator.EQUAL, 3L)));
        assertTrue(matches(in, row("2", "10001", "20000")));
        assertFalse(matches(in, row("5", "40001", "50000")));
    }

    @Test
    public void notInExcludesListedValues()
    {
        ColumnPredicate notIn = new ColumnPredicate(SK, SubstraitOperator.NOT_IN,
                Arrays.<Object>asList(1L, 2L, 3L), null);
        assertFalse(matches(new LogicalExpression(notIn), row("2", "10001", "20000")));
        assertTrue(matches(new LogicalExpression(notIn), row("5", "40001", "50000")));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void unknownColumnThrows()
    {
        matches(leaf("does_not_exist", SubstraitOperator.EQUAL, 1L), row("1", "0", "10000"));
    }

    @Test
    public void validateSchemaLayoutAssumption()
    {
        // Guard against the income_band column order the tests depend on.
        assertEquals(SK, INCOME_BAND.getColumns()[0].getName());
        assertEquals(LOWER, INCOME_BAND.getColumns()[1].getName());
        assertEquals(UPPER, INCOME_BAND.getColumns()[2].getName());
    }
}
