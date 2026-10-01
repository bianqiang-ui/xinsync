package com.wugui.datax.admin.tool.tdsql;

/**
 * TDSQL（MySQL 版）的表类型。
 * 三种类型在建表 DDL 里的表达完全不同，也因此决定了同步任务的展开方式。
 */
public enum TdsqlTableType {

    /** 分片表：SHARDKEY = 分片键列 */
    SHARD,

    /** 广播表（每个分片都有一份全量数据）：SHARDKEY = noshardkey_allset */
    BROADCAST,

    /** 单表：不写 SHARDKEY 子句 */
    SINGLE
}
