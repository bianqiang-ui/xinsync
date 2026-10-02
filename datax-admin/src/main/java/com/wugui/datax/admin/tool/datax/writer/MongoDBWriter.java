package com.wugui.datax.admin.tool.datax.writer;

import com.google.common.collect.Maps;
import com.wugui.datatx.core.util.Constants;
import com.wugui.datax.admin.dto.UpsertInfo;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.tool.datax.DsSecretPlaceholder;
import com.wugui.datax.admin.tool.pojo.DataxMongoDBPojo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MongoDBWriter extends BaseWriterPlugin implements DataxWriterInterface {
    @Override
    public String getName() {
        return "mongodbwriter";
    }

    @Override
    public Map<String, Object> sample() {
        return null;
    }


    @Override
    public Map<String, Object> buildMongoDB(DataxMongoDBPojo plugin) {
        //构建
        Map<String, Object> writerObj = Maps.newLinkedHashMap();
        JobDatasource dataSource = plugin.getJdbcDatasource();
        writerObj.put("name", getName());
        Map<String, Object> parameterObj = Maps.newLinkedHashMap();
        String[] addressList = null;
        String str = dataSource.getJdbcUrl().replace(Constants.MONGO_URL_PREFIX, Constants.STRING_BLANK);
        if (str.contains(Constants.SPLIT_AT) && str.contains(Constants.SPLIT_DIVIDE)) {
            addressList = str.substring(str.indexOf(Constants.SPLIT_AT) + 1, str.indexOf(Constants.SPLIT_DIVIDE)).split(Constants.SPLIT_COMMA);
        } else if (str.contains(Constants.SPLIT_DIVIDE)) {
            addressList = str.substring(0, str.indexOf(Constants.SPLIT_DIVIDE)).split(Constants.SPLIT_COMMA);
        }
        parameterObj.put("address", addressList);
        // 见 MongoDBReader：占位符还原与键名无关
        String username = DsSecretPlaceholder.usernameForJson(dataSource);
        String password = DsSecretPlaceholder.passwordForJson(dataSource);
        parameterObj.put("userName", username == null ? Constants.STRING_BLANK : username);
        parameterObj.put("userPassword", password == null ? Constants.STRING_BLANK : password);
        parameterObj.put("dbName", dataSource.getDatabaseName());
        parameterObj.put("collectionName", plugin.getWriterTable());
        parameterObj.put("column", plugin.getColumns());
        UpsertInfo upsert = plugin.getUpsertInfo();
        if (upsert != null) {
            parameterObj.put("upsertInfo", upsert);
        }
        writerObj.put("parameter", parameterObj);
        return writerObj;
    }
}
