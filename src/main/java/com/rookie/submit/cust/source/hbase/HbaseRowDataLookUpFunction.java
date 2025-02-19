package com.rookie.submit.cust.source.hbase;


import org.apache.flink.connector.hbase.util.HBaseSerde;
import org.apache.flink.connector.hbase.util.HBaseTableSchema;
import org.apache.flink.shaded.guava30.com.google.common.cache.Cache;
import org.apache.flink.shaded.guava30.com.google.common.cache.CacheBuilder;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.TableFunction;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The MysqlRowDataLookUpFunction is a standard user-defined table function, it can be used in
 * tableAPI and also useful for temporal table join plan in SQL. It looks up the result as {@link
 * RowData}.
 */
public class HbaseRowDataLookUpFunction extends TableFunction<RowData> {
    private static final long serialVersionUID = 1008611L;
    private static final Logger LOG = LoggerFactory.getLogger(HbaseRowDataLookUpFunction.class);

    private final HBaseTableSchema hbaseSchema;
    private transient HBaseSerde serde;
    private Connection conn;
    private Table table;
    private final HbaseOption options;
    private final long cacheMaxSize;
    private final long cacheExpireMs;
    private final int maxRetryTimes;

    private transient Cache<RowData, List<RowData>> cache;

    private LinkedHashMap<byte[], List<byte[]>> lookupKey;
    private LinkedHashMap<byte[], List<byte[]>> resultColumn;

    public HbaseRowDataLookUpFunction(HBaseTableSchema hbaseSchema, HbaseOption options) throws UnsupportedEncodingException {

        this.hbaseSchema = hbaseSchema;
        this.cacheMaxSize = options.getCacheMaxSize();
        this.cacheExpireMs = options.getCacheExpireMs();
        this.maxRetryTimes = options.getMaxRetryTimes();
        this.options = options;

        // format lookup filter column
        String lookupKeyConfig = options.getLookupKey();
        lookupKey = new LinkedHashMap<>();
        for (String key : lookupKeyConfig.split(",")) {
            String[] tmp = key.split(":");
            byte[] family = tmp[0].getBytes("UTF-8");
            byte[] qualify = tmp[1].getBytes("UTF-8");
            if (lookupKey.containsKey(family)) {
                lookupKey.get(family).add(qualify);
            } else {
                List<byte[]> list = new ArrayList<>();
                list.add(qualify);
                lookupKey.put(family, list);
            }
        }

        // format result qualifier
        resultColumn = new LinkedHashMap<>();
        for (String familyString : hbaseSchema.getFamilyNames()) {
            byte[] family = familyString.getBytes("UTF-8");
            byte[][] qualifies = hbaseSchema.getQualifierKeys(familyString);
            for (byte[] by : qualifies) {
                if (resultColumn.containsKey(family)) {
                    resultColumn.get(family).add(by);
                } else {
                    List<byte[]> list = new ArrayList<>();
                    list.add(by);
                    resultColumn.put(family, list);
                }
            }
        }
        LOG.info("open end");
    }

    @Override
    public void open(FunctionContext context) {
        try {
            establishConnectionAndStatement();
            this.cache =
                    cacheMaxSize == -1 || cacheExpireMs == -1
                            ? null
                            : CacheBuilder.newBuilder()
                            .expireAfterWrite(cacheExpireMs, TimeUnit.MILLISECONDS)
                            .maximumSize(cacheMaxSize)
                            .build();
        } catch (IOException ioe) {
            throw new IllegalArgumentException("open() failed.", ioe);
        }
        this.serde = new HBaseSerde(hbaseSchema, options.getNullStringLiteral());
    }

    private void establishConnectionAndStatement() throws IOException {
        Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", options.getZookeeperQuorum());
        conf.set("zookeeper.znode.parent", options.getZookeeperZnodeParent());
        conn = ConnectionFactory.createConnection(conf);
        table = conn.getTable(TableName.valueOf(options.getTableName()));
    }

    /**
     * method eval lookup key,
     * search cache first
     * if cache not exit, query third system
     *
     * @param input query parameter
     */
    public void eval(Object... input) {

        String[] keys = input[0].toString().split(",");

        RowData keyRow = GenericRowData.of(input);
        if (cache != null) {
            List<RowData> cachedRows = cache.getIfPresent(keyRow);
            if (cachedRows != null) {
                for (RowData cachedRow : cachedRows) {
                    collect(cachedRow);
                }
                return;
            }
        }
        // query mysql, retry maxRetryTimes count
        for (int retry = 0; retry <= maxRetryTimes; retry++) {
            try {
                // create scan, add return column
                Scan scan = getScan();
                int i = 0;
                // add SingleColumnValueFilter
                for (Map.Entry<byte[], List<byte[]>> entry : lookupKey.entrySet()) {
                    byte[] family = entry.getKey();
                    for (byte[] qualifier : entry.getValue()) {
                        Filter filter = new SingleColumnValueFilter(family, qualifier, CompareOperator.EQUAL, keys[i].getBytes("UTF8"));
                        scan.setFilter(filter);
                        // Avoid can't get all condition column in keys
                        // if have two filter column, but only get one key, just use first column as filter
                        if (i == keys.length - 1) {
                            break;
                        }
                        ++i;
                    }
                }

                // scan&parse result
                try (ResultScanner resultSet = table.getScanner(scan)) {
                    Result result;
                    if (cache == null) {
                        while ((result = resultSet.next()) != null) {
                            // parse to RowData
                            RowData row = serde.convertToNewRow(result);
                            collect(row);
                        }
                    } else {
                        ArrayList<RowData> rows = new ArrayList<>();
                        while ((result = resultSet.next()) != null) {
                            // parse to RowData
                            RowData row = serde.convertToNewRow(result);
                            rows.add(row);
                            collect(row);
                        }
                        rows.trimToSize();
                        cache.put(keyRow, rows);
                    }

                }
                break;
            } catch (Exception e) {
                LOG.error(String.format("Hbase executeBatch error, retry times = %d", retry), e);
                if (retry >= maxRetryTimes) {
                    throw new RuntimeException("Execution of Hbase query failed.", e);
                }
                try {
                    if (conn.isClosed()) {
                        table.close();
                        establishConnectionAndStatement();
                    }
                } catch (IOException exception) {
                    LOG.error(
                            "Hbase connection is not valid, and reestablish connection failed",
                            exception);
                    throw new RuntimeException("Reestablish Hbase connection failed", exception);
                }

                try {
                    Thread.sleep(1000 * retry);
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
            }
        }

    }

    private Scan getScan() {
        Scan scan = new Scan();
        // add result column
        for (Map.Entry<byte[], List<byte[]>> entry : resultColumn.entrySet()) {
            byte[] family = entry.getKey();
            for (byte[] qualifier : entry.getValue()) {
                scan.addColumn(family, qualifier);
            }
        }
        return scan;
    }

    @Override
    public void close() throws Exception {
        if (cache != null) {
            cache.cleanUp();
            cache = null;
        }
        if (conn != null) {
            conn.close();
        }
    }
}
