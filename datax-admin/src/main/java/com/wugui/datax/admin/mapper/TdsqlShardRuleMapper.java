package com.wugui.datax.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wugui.datax.admin.entity.TdsqlShardRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * TDSQL 分片规则表映射。
 *
 * 只有 BaseMapper 的通用方法，没有 XML：这张表的读侧查询就一种形状（按逻辑库 + 逻辑表取行），
 * 用 QueryWrapper 表达够了；少一个 XML 文件，就少一个"改了实体忘了改 resultMap"的地方。
 */
@Mapper
public interface TdsqlShardRuleMapper extends BaseMapper<TdsqlShardRule> {
}
