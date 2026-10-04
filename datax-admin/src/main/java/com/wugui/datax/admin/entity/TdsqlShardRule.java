package com.wugui.datax.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Date;

/**
 * TDSQL 分片规则（表 `tdsql_shard_rule`）。
 *
 * ①导入、②分布式 DDL 生成、③应用 SQL 改造共用这一份规则，不做三套配置；
 * 因此"一条逻辑表只能有一条启用规则"是这张表的硬约束（库里由 uk_tdsql_rule_logic_table 保证，
 * 读侧再用 `TdsqlShardRules.singleEnabled` 兜一次 —— 索引被人手工删掉时不能退回随机取一条）。
 */
@Data
@TableName("tdsql_shard_rule")
public class TdsqlShardRule {

    @ApiModelProperty("主键")
    private int id;

    @ApiModelProperty("目标 TDSQL 数据源 id")
    private Long datasourceId;

    @ApiModelProperty("逻辑库名")
    private String logicDb;

    @ApiModelProperty("逻辑表名")
    private String logicTable;

    /**
     * 存的是 `TdsqlTableType` 的名字。这里刻意保持 String 而不做类型转换：
     * 库里可能是历史值或手工写入的拼错值，实体层一声不吭地 valueOf 会把"读规则"变成 500。
     * 判定统一放在 `TdsqlDdlGenerator`，认不出来就明确报错。
     */
    @ApiModelProperty("表类型：SHARD / BROADCAST / SINGLE")
    private String tableType;

    @ApiModelProperty("分片键列名，仅分片表必填")
    private String shardKey;

    @ApiModelProperty("分片数，仅作记录；平台内不据此计算路由")
    private Integer shardNum;

    @ApiModelProperty("改造前主键列快照")
    private String pkColumns;

    @ApiModelProperty("改造前唯一索引列快照")
    private String ukColumns;

    @ApiModelProperty("自增列名")
    private String autoIncrementCol;

    @ApiModelProperty("TDSQL 序列名")
    private String sequenceName;

    @ApiModelProperty("来源数据源 id")
    private Long sourceDatasourceId;

    @ApiModelProperty("来源表名")
    private String sourceTable;

    @ApiModelProperty("是否启用：0 停用 1 启用")
    private Integer enabled;

    @ApiModelProperty("创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @ApiModelProperty("更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;

    /**
     * 调用侧写法统一用这个；MyBatis-Plus 只映射字段，方法不参与映射，无需额外注解。
     */
    public boolean isEnabledRule() {
        return enabled != null && enabled == 1;
    }
}
