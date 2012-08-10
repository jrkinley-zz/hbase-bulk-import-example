package com.cloudera.examples.hbase.bulkimport;

import java.io.IOException;
import java.util.Locale;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import au.com.bytecode.opencsv.CSVParser;

/**
 * HBase bulk import example
 * <p>
 * Parses Facebook and Twitter messages from CSV files and outputs
 * <ImmutableBytesWritable, KeyValue>.
 * <p>
 * The ImmutableBytesWritable key is used by the TotalOrderPartitioner to map it
 * into the correct HBase table region.
 * <p>
 * The KeyValue value holds the HBase mutation information (column family,
 * column, and value)
 */
public class HBaseKVMapper extends
    Mapper<LongWritable, Text, ImmutableBytesWritable, KeyValue> {

  final static byte[] SRV_COL_FAM = "srv".getBytes();
  final static int NUM_FIELDS = 16;

  CSVParser csvParser = new CSVParser();
  int tipOffSeconds = 0;
  String tableName = "";

  DateTimeFormatter p = DateTimeFormat.forPattern("MMM dd, yyyy HH:mm:ss")
      .withLocale(Locale.US).withZone(DateTimeZone.forID("PST8PDT"));

  ImmutableBytesWritable hKey = new ImmutableBytesWritable();
  KeyValue kv;

  /** {@inheritDoc} */
  @Override
  protected void setup(Context context) throws IOException,
      InterruptedException {
    Configuration c = context.getConfiguration();

    tipOffSeconds = c.getInt("epoch.seconds.tipoff", 0);
    tableName = c.get("hbase.table.name");
  }

  /** {@inheritDoc} */
  @Override
  protected void map(LongWritable key, Text value, Context context)
      throws IOException, InterruptedException {

    if (value.find("Service,Term,") > -1) {
      // Skip header
      return;
    }

    String[] fields = null;

    try {
      fields = csvParser.parseLine(value.toString());
    } catch (Exception ex) {
      context.getCounter("HBaseKVMapper", "PARSE_ERRORS").increment(1);
      return;
    }

    if (fields.length != NUM_FIELDS) {
      context.getCounter("HBaseKVMapper", "INVALID_FIELD_LEN").increment(1);
      return;
    }

    // Get game offset in seconds from tip-off
    DateTime dt = null;

    try {
      dt = p.parseDateTime(fields[9]);
    } catch (Exception ex) {
      context.getCounter("HBaseKVMapper", "INVALID_DATE").increment(1);
      return;
    }

    int gameOffset = (int) ((dt.getMillis() / 1000) - tipOffSeconds);
    String offsetForKey = String.format("%04d", gameOffset);

    String username = fields[2];
    if (username.equals("")) {
      username = fields[3];
    }

    // Key: e.g. "1200:twitter:jrkinley"
    hKey.set(String.format("%s:%s:%s", offsetForKey, fields[0], username)
        .getBytes());

    // Service columns
    if (!fields[0].equals("")) {
      kv = new KeyValue(hKey.get(), SRV_COL_FAM,
          HColumnEnum.SRV_COL_SERVICE.getColumnName(), fields[0].getBytes());
      context.write(hKey, kv);
    }

    if (!fields[1].equals("")) {
      kv = new KeyValue(hKey.get(), SRV_COL_FAM,
          HColumnEnum.SRV_COL_TERM.getColumnName(), fields[1].getBytes());
      context.write(hKey, kv);
    }

    if (!fields[2].equals("")) {
      kv = new KeyValue(hKey.get(), SRV_COL_FAM,
          HColumnEnum.SRV_COL_USERNAME.getColumnName(), fields[2].getBytes());
      context.write(hKey, kv);
    }

    if (!fields[3].equals("")) {
      kv = new KeyValue(hKey.get(), SRV_COL_FAM,
          HColumnEnum.SRV_COL_NAME.getColumnName(), fields[3].getBytes());
      context.write(hKey, kv);
    }

    if (!fields[4].equals("")) {
      kv = new KeyValue(hKey.get(), SRV_COL_FAM,
          HColumnEnum.SRV_COL_UPDATE.getColumnName(), fields[4].getBytes());
      context.write(hKey, kv);
    }

    if (!fields[9].equals("")) {
      kv = new KeyValue(hKey.get(), SRV_COL_FAM,
          HColumnEnum.SRV_COL_TIME.getColumnName(), fields[9].getBytes());
      context.write(hKey, kv);
    }

    context.getCounter("HBaseKVMapper", "NUM_MSGS").increment(1);

    /*
     * Output number of messages per quarter and before/after game. This should
     * correspond to the number of messages per region in HBase
     */
    if (gameOffset < 0) {
      context.getCounter("QStats", "BEFORE_GAME").increment(1);
    } else if (gameOffset < 900) {
      context.getCounter("QStats", "Q1").increment(1);
    } else if (gameOffset < 1800) {
      context.getCounter("QStats", "Q2").increment(1);
    } else if (gameOffset < 2700) {
      context.getCounter("QStats", "Q3").increment(1);
    } else if (gameOffset < 3600) {
      context.getCounter("QStats", "Q4").increment(1);
    } else {
      context.getCounter("QStats", "AFTER_GAME").increment(1);
    }
  }
}
