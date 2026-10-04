package com.wugui.datax.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wugui.datax.admin.entity.TdsqlShardRule;

/**
 * TDSQL 分片规则读写（能力①导入、②DDL 生成、③SQL 改造共用的单一真相源）。
 *
 * @author XinSync
 * @version v2.1.2
 * @since 2026-10-04
 */
public interface TdsqlShardRuleService extends IService<TdsqlShardRule> {

    /**
     * 取一张逻辑表**当前生效**的规则。
     *
     * 返回类型直接是单条而不是 List：把"一张表只许有一条启用规则"这条约束放在服务出口，
     * 调用方就没有"自己 get(0)"的机会。取不到或取多了抛异常，不返回 null ——
     * 返回 null 会让上层把"规则丢了"当成"这张表是普通单表"，静默产出错误的 DDL。
     *
     * @param logicDb     逻辑库名
     * @param logicTable  逻辑表名
     * @return 唯一那条启用的规则
     * @throws IllegalArgumentException 没有启用规则、有多条启用规则、或库里数据不自洽
     */
    TdsqlShardRule findEnabledRule(String logicDb, String logicTable);
}
