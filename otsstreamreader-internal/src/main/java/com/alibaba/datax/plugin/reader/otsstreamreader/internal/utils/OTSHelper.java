package com.alibaba.datax.plugin.reader.otsstreamreader.internal.utils;

import com.alibaba.datax.plugin.reader.otsstreamreader.internal.config.OTSRetryStrategyForStreamReader;
import com.alibaba.datax.plugin.reader.otsstreamreader.internal.config.OTSStreamReaderConfig;
import com.aliyun.openservices.ots.internal.ClientConfiguration;
import com.aliyun.openservices.ots.internal.OTS;
import com.aliyun.openservices.ots.internal.OTSClient;
import com.aliyun.openservices.ots.internal.OTSException;
import com.aliyun.openservices.ots.internal.core.OTSRetryStrategy;
import com.aliyun.openservices.ots.internal.model.*;
import com.aliyun.openservices.ots.internal.streamclient.utils.TimeUtils;

import java.util.*;

public class OTSHelper {

    private static final String TABLE_NOT_READY = "OTSTableNotReady";
    private static final String OTS_PARTITION_UNAVAILABLE = "OTSPartitionUnavailable";
    private static final String OBJECT_NOT_EXIST = "OTSObjectNotExist";
    private static final int CREATE_TABLE_READ_CU = 5000;
    private static final int CREATE_TABLE_WRITE_CU = 5000;
    private static final long CHECK_TABLE_READY_INTERNAL_MILLIS = 100;

    public static OTS getOTSInstance(OTSStreamReaderConfig config) {
        if (config.getOtsForTest() != null) {
            return config.getOtsForTest(); // for test
        }

        ClientConfiguration clientConfig = new ClientConfiguration();
        clientConfig.setRetryStrategy(new OTSRetryStrategyForStreamReader());
        OTS ots = new OTSClient(config.getEndpoint(), config.getAccessId(),
                config.getAccessKey(), config.getInstanceName(), clientConfig);
        return ots;
    }

    public static StreamDetails getStreamDetails(OTS ots, String tableName) {
        DescribeTableRequest describeTableRequest = new DescribeTableRequest(tableName);
        DescribeTableResult result = ots.describeTable(describeTableRequest);
        return result.getStreamDetails();
    }

    public static List<StreamShard> getOrderedShardList(OTS ots, String streamId) {
        DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest(streamId);
        DescribeStreamResult describeStreamResult = ots.describeStream(describeStreamRequest);
        List<StreamShard> shardList = new ArrayList<StreamShard>();
        shardList.addAll(describeStreamResult.getShards());
        while (describeStreamResult.getNextShardId() != null) {
            describeStreamRequest.setInclusiveStartShardId(describeStreamResult.getNextShardId());
            describeStreamResult = ots.describeStream(describeStreamRequest);
            shardList.addAll(describeStreamResult.getShards());
        }
        return shardList;
    }

    public static boolean checkTableExists(OTS ots, String tableName) {
        boolean exist = false;
        try {
            describeTable(ots, tableName);
            exist = true;
        } catch (OTSException ex) {
            if (!ex.getErrorCode().equals(OBJECT_NOT_EXIST)) {
                throw ex;
            }
        }
        return exist;
    }

    public static DescribeTableResult describeTable(OTS ots, String tableName) {
        return ots.describeTable(new DescribeTableRequest(tableName));
    }

    public static void createTable(OTS ots, TableMeta tableMeta, TableOptions tableOptions) {
        CreateTableRequest request = new CreateTableRequest(tableMeta, tableOptions,
                new ReservedThroughput(CREATE_TABLE_READ_CU, CREATE_TABLE_WRITE_CU));
        ots.createTable(request);
    }

    public static boolean waitUntilTableReady(OTS ots, String tableName, long maxWaitTimeMillis) {
        TableMeta tableMeta = describeTable(ots, tableName).getTableMeta();
        List<PrimaryKeyColumn> startPkCols = new ArrayList<PrimaryKeyColumn>();
        List<PrimaryKeyColumn> endPkCols = new ArrayList<PrimaryKeyColumn>();
        for (PrimaryKeySchema pkSchema : tableMeta.getPrimaryKeyList()) {
            startPkCols.add(new PrimaryKeyColumn(pkSchema.getName(), PrimaryKeyValue.INF_MIN));
            endPkCols.add(new PrimaryKeyColumn(pkSchema.getName(), PrimaryKeyValue.INF_MAX));
        }
        RangeRowQueryCriteria rangeRowQueryCriteria = new RangeRowQueryCriteria(tableName);
        rangeRowQueryCriteria.setInclusiveStartPrimaryKey(new PrimaryKey(startPkCols));
        rangeRowQueryCriteria.setExclusiveEndPrimaryKey(new PrimaryKey(endPkCols));
        rangeRowQueryCriteria.setLimit(1);
        rangeRowQueryCriteria.setMaxVersions(1);

        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < maxWaitTimeMillis) {
            try {
                GetRangeRequest getRangeRequest = new GetRangeRequest(rangeRowQueryCriteria);
                ots.getRange(getRangeRequest);
                return true;
            } catch (OTSException ex) {
                if (!ex.getErrorCode().equals(OTS_PARTITION_UNAVAILABLE) &&
                        !ex.getErrorCode().equals(TABLE_NOT_READY)) {
                    throw ex;
                }
            }
            TimeUtils.sleepMillis(CHECK_TABLE_READY_INTERNAL_MILLIS);
        }
        return false;
    }

}
