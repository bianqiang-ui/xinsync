package com.wugui.datax.admin.tool.datax.reader;

import com.google.common.collect.Maps;
import com.wugui.datatx.core.util.Constants;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.tool.datax.DsSecretPlaceholder;
import com.wugui.datax.admin.tool.pojo.DataxMongoDBPojo;

import java.util.Map;

public class MongoDBReader extends BaseReaderPlugin implements DataxReaderInterface {
    @Override
    public String getName() {
        return "mongodbreader";
    }

    @Override
    public Map<String, Object> sample() {
        return null;
    }

    public Map<String, Object> buildMongoDB(DataxMongoDBPojo plugin) {
        //构建
        JobDatasource dataSource = plugin.getJdbcDatasource();
        Map<String, Object> readerObj = Maps.newLinkedHashMap();
        readerObj.put("name", getName());
        Map<String, Object> parameterObj = Maps.newLinkedHashMap();
        String[] addressList = null;
        String str = dataSource.getJdbcUrl().replace(Constants.MONGO_URL_PREFIX, Constants.STRING_BLANK);
        if (str.contains(Constants.SPLIT_AT) && str.contains(Constants.SPLIT_DIVIDE)) {
            addressList = str.substring(str.indexOf(Constants.SPLIT_AT) + 1, str.indexOf(Constants.SPLIT_DIVIDE)).split(Constants.SPLIT_COMMA);
        } else if (str.contains(Constants.SPLIT_DIVIDE)) {
            addressList = str.substring(0, str.indexOf(Constants.SPLIT_DIVIDE)).split(Constants.SPLIT_COMMA);
        }
        parameterObj.put("address", addressList);
        // 这两个键名（userName/userPassword）不在 JSONUtils 的解密范围内，历来是把库里的值原样发出去；
        // 占位符还原是按值全文匹配的，与键名无关，所以这条路面也一并收口了。
        String username = DsSecretPlaceholder.usernameForJson(dataSource);
        String password = DsSecretPlaceholder.passwordForJson(dataSource);
        parameterObj.put("userName", username == null ? Constants.STRING_BLANK : username);
        parameterObj.put("userPassword", password == null ? Constants.STRING_BLANK : password);
        parameterObj.put("dbName", dataSource.getDatabaseName());
        parameterObj.put("collectionName", plugin.getReaderTable());
        parameterObj.put("column", plugin.getColumns());
        readerObj.put("parameter", parameterObj);
        return readerObj;
    }
}
