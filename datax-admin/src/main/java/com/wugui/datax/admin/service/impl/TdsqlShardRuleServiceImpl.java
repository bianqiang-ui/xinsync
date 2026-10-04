package com.wugui.datax.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wugui.datax.admin.entity.TdsqlShardRule;
import com.wugui.datax.admin.mapper.TdsqlShardRuleMapper;
import com.wugui.datax.admin.service.TdsqlShardRuleService;
import com.wugui.datax.admin.tool.tdsql.TdsqlShardRules;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TdsqlShardRuleServiceImpl
 *
 * @author XinSync
 * @version v2.1.2
 * @since 2026-10-04
 */
@Service("tdsqlShardRuleService")
public class TdsqlShardRuleServiceImpl extends ServiceImpl<TdsqlShardRuleMapper, TdsqlShardRule>
        implements TdsqlShardRuleService {

    @Override
    public TdsqlShardRule findEnabledRule(String logicDb, String logicTable) {
        // 故意不在 SQL 里过滤 enabled：把该表的行全取回来，才能分清
        // "没登记过规则"和"登记了但全停用"，也才能发现"启用了两条"这种库里数据坏了的情况。
        QueryWrapper<TdsqlShardRule> wrapper = new QueryWrapper<TdsqlShardRule>();
        wrapper.eq("logic_db", logicDb);
        wrapper.eq("logic_table", logicTable);
        List<TdsqlShardRule> rows = list(wrapper);
        return TdsqlShardRules.singleEnabled(logicDb, logicTable, rows);
    }
}
