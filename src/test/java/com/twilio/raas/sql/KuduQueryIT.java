package com.twilio.raas.sql;

import org.apache.calcite.linq4j.Enumerable;
import org.apache.kudu.client.AsyncKuduScanner;
import org.junit.runners.JUnit4;
import org.junit.ClassRule;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kudu.test.KuduTestHarness;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Type;
import java.util.List;
import java.util.Arrays;
import org.apache.kudu.Schema;
import org.junit.Test;
import java.util.Collections;
import org.junit.Assert;
import java.util.Random;
import org.apache.kudu.client.Upsert;
import org.apache.kudu.client.PartialRow;
import java.sql.Timestamp;
import org.apache.kudu.client.AsyncKuduSession;
import java.util.Iterator;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.KuduPredicate;
import java.util.ArrayList;
import java.util.LinkedList;
import org.apache.calcite.linq4j.Enumerator;

@RunWith(JUnit4.class)
public class KuduQueryIT {
  private static final Logger logger = LoggerFactory.getLogger(KuduQueryIT.class);

  public static String FIRST_SID = "SM1234857";
  public static String SECOND_SID = "SM123485789";

  public static String ACCOUNT_SID = "AC1234567";

  @ClassRule
  public static KuduTestHarness testHarness = new KuduTestHarness();
  public static final String BASE_TABLE_NAME = "ReportCenter.DeliveredMessages";

  public static KuduTable TABLE;

  @BeforeClass
  public static void setup() throws Exception {
    final List<ColumnSchema> columns = Arrays.asList(
        new ColumnSchema.ColumnSchemaBuilder("account_sid", Type.STRING).key(true).build(),
        new ColumnSchema.ColumnSchemaBuilder("date_created", Type.UNIXTIME_MICROS).key(true).build(),
        new ColumnSchema.ColumnSchemaBuilder("sid", Type.STRING).key(true).build());

    testHarness.getClient().createTable(BASE_TABLE_NAME, new Schema(columns),
					new org.apache.kudu.client.CreateTableOptions()
					.addHashPartitions(Arrays.asList("account_sid"), 5)

					.setNumReplicas(1));
    final AsyncKuduSession insertSession = testHarness.getAsyncClient().newSession();
    TABLE = testHarness.getClient().openTable(BASE_TABLE_NAME);

    final Upsert firstRowOp = TABLE.newUpsert();
    final PartialRow firstRowWrite = firstRowOp.getRow();
    firstRowWrite.addString("account_sid", KuduQueryIT.ACCOUNT_SID);
    firstRowWrite.addString("sid", KuduQueryIT.FIRST_SID);
    firstRowWrite.addTimestamp("date_created", new Timestamp(System.currentTimeMillis()));
    insertSession.apply(firstRowOp).join();

    final Upsert secondRowOp = TABLE.newUpsert();
    final PartialRow secondRowWrite = secondRowOp.getRow();
    secondRowWrite.addString("account_sid", KuduQueryIT.ACCOUNT_SID);
    secondRowWrite.addString("sid", "SM123485789");
    secondRowWrite.addTimestamp("date_created", new Timestamp(System.currentTimeMillis()));
    insertSession.apply(secondRowOp).join();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    testHarness.getClient().deleteTable(BASE_TABLE_NAME);
  }

  @Test
  public void queryBaseTableForMessageSid() throws Exception {
    final CalciteKuduTable relTable = new CalciteKuduTable(KuduQueryIT.TABLE,
							   testHarness.getAsyncClient());

    // @TODO: we have the columnSchema in the setup, we don't need to grab the table.
    final KuduPredicate filterToSid = KuduPredicate
      .newComparisonPredicate(KuduQueryIT.TABLE.getSchema().getColumn("sid"), KuduPredicate.ComparisonOp.EQUAL, "SM1234857");
    final Enumerable<Object[]> results =
            relTable.executeQuery(Collections.singletonList(Collections.singletonList(filterToSid)), Collections.singletonList(2), -1, -1, false);
    Iterator<Object[]> resultIter = results.iterator();
    Assert.assertTrue("Should have something to iterate over",
		      resultIter.hasNext());
    Assert.assertEquals("Message sid should match",
     			KuduQueryIT.FIRST_SID, resultIter.next()[0]);
    Assert.assertFalse("Should only have one row",
		       resultIter.hasNext());
  }

  @Test
  public void queryBaseTableForAccountSid() throws Exception {
    final CalciteKuduTable relTable = new CalciteKuduTable(KuduQueryIT.TABLE,
							   testHarness.getAsyncClient());

    // @TODO: we have the columnSchema in the setup, we don't need to grab the table.
    final KuduPredicate filterToAccountSid = KuduPredicate
      .newComparisonPredicate(KuduQueryIT.TABLE.getSchema().getColumn("account_sid"), KuduPredicate.ComparisonOp.EQUAL, KuduQueryIT.ACCOUNT_SID);
    final Enumerable<Object[]> results = relTable.executeQuery(Collections.singletonList(Collections.singletonList(filterToAccountSid)),
        Arrays.asList(2, 0), -1, -1, false);
    Iterator<Object[]> resultIter = results.iterator();
    Assert.assertTrue("Should have something to iterate over",
		      resultIter.hasNext());
    final Object[] firstRow = resultIter.next();
    Assert.assertEquals("Message sid should match first sid",
     			KuduQueryIT.FIRST_SID, firstRow[0]);
    Assert.assertTrue("Should have a second row",
		      resultIter.hasNext());
    final Object[] secondRow = resultIter.next();
    Assert.assertEquals("Message sid should match second sid",
     			KuduQueryIT.SECOND_SID, secondRow[0]);

    Assert.assertEquals("First row should have two fields",
    			2, firstRow.length);
    Assert.assertEquals("Second row should have two fields",
    			2, secondRow.length);
    Assert.assertEquals("First row second field should match the account sid",
			KuduQueryIT.ACCOUNT_SID, firstRow[1]);
    Assert.assertEquals("Second row second field should match the account sid",
			KuduQueryIT.ACCOUNT_SID, secondRow[1]);
  }

  @Test
  public void queryForInSids() throws Exception {
    final CalciteKuduTable relTable = new CalciteKuduTable(KuduQueryIT.TABLE,
							   testHarness.getAsyncClient());

    // @TODO: we have the columnSchema in the setup, we don't need to grab the table.
    final KuduPredicate firstSid = KuduPredicate
      .newComparisonPredicate(KuduQueryIT.TABLE.getSchema().getColumn("sid"), KuduPredicate.ComparisonOp.EQUAL, KuduQueryIT.FIRST_SID);
    final KuduPredicate secondSid = KuduPredicate
      .newComparisonPredicate(KuduQueryIT.TABLE.getSchema().getColumn("sid"), KuduPredicate.ComparisonOp.EQUAL, KuduQueryIT.SECOND_SID);
    final List<List<KuduPredicate>> predicateQuery = new ArrayList<>();
    predicateQuery.add(Arrays.asList(firstSid));
    predicateQuery.add(Arrays.asList(secondSid));
    final Enumerable<Object[]> results = relTable.executeQuery(predicateQuery,
        Collections.singletonList(2), -1, -1, false);
    Enumerator<Object[]> resultIter = results.enumerator();
    Assert.assertTrue("Should have something to iterate over",
        resultIter.moveNext());
    final List<String> resultCollection = new LinkedList<String>();

    do {
        resultCollection.add(resultIter.current()[0].toString());
    } while(resultIter.moveNext());
    Assert.assertEquals(String.format("Should return two rows from the Kudu Table: %s", resultCollection),
        2, resultCollection.size());
    Assert.assertTrue("First sid should be in the result set",
        resultCollection.contains(KuduQueryIT.FIRST_SID));
    Assert.assertTrue("Second sid should be in the result set",
        resultCollection.contains(KuduQueryIT.SECOND_SID));
  }

    @Test
    public void testKuduScannerLimitPushdown() throws Exception {
        final CalciteKuduTable relTable = new CalciteKuduTable(KuduQueryIT.TABLE,
                testHarness.getAsyncClient());

        final KuduPredicate filterToSid = KuduPredicate
                .newComparisonPredicate(KuduQueryIT.TABLE.getSchema()
                        .getColumn("sid"), KuduPredicate.ComparisonOp.EQUAL, "SM1234857");

        // since we are not sorting assert that the limit is not pushed down into the kudu scanner
        SortableEnumerable sortableEnumerable =
                (SortableEnumerable)relTable.executeQuery(
                        Collections.singletonList(Collections.singletonList(filterToSid)),
                        Collections.singletonList(2), 3, -1, false);
        for (AsyncKuduScanner scanner : sortableEnumerable.getScanners()) {
            Assert.assertEquals( Long.MAX_VALUE, scanner.getLimit());
        }

        // even though we are sorting we cannot push down the limit since there is an offset
        sortableEnumerable = (SortableEnumerable)relTable.executeQuery(
                Collections.singletonList(Collections.singletonList(filterToSid)),
                Collections.singletonList(2), 3, 4, true);
        for (AsyncKuduScanner scanner : sortableEnumerable.getScanners()) {
            Assert.assertEquals( Long.MAX_VALUE, scanner.getLimit());
        }

        // since we sorting assert that the limit is pushed down into the kudu scanner
        sortableEnumerable = (SortableEnumerable)relTable.executeQuery(
                Collections.singletonList(Collections.singletonList(filterToSid)),
                Collections.singletonList(2), 3, -1, true);
        for (AsyncKuduScanner scanner : sortableEnumerable.getScanners()) {
            Assert.assertEquals( 3, scanner.getLimit());
        }

        // even though we ask not to sort, since we set an offset the enumerable forces a sort
        sortableEnumerable = (SortableEnumerable)relTable.executeQuery(
                Collections.singletonList(Collections.singletonList(filterToSid)),
                Collections.singletonList(2), -1, 1, false);
        Assert.assertTrue(sortableEnumerable.sort);
        for (AsyncKuduScanner scanner : sortableEnumerable.getScanners()) {
            Assert.assertEquals( Long.MAX_VALUE, scanner.getLimit());
        }
    }

}
