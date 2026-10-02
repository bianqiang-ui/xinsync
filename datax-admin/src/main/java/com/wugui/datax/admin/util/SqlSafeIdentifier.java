package com.wugui.datax.admin.util;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 拼进 SQL 的**标识符**（列名、排序字段）必须过的唯一一道闸。
 *
 * <p>mybatis-plus 的 {@code orderByAsc(String)} / {@code eq(String, Object)} 第一个参数是**原样拼接**进
 * SQL 的片段，不走预编译占位符；分页接口又允许前端直接传 {@code ascs} / {@code desc} 这类排序参数，
 * 于是排序字段是一个"用户可控、且必然出现在 SQL 结构位置"的注入面。值（value）走占位符，不在本类职责内。
 *
 * <p>这里只放行"形状上就是一个裸列名"的字符串：字母/数字/下划线，不能以数字开头，长度 ≤64。
 * 空格、逗号、分号、引号、括号、注释符一律拒绝——被拒的字段宁可不排，也不能拼进去。
 */
public final class SqlSafeIdentifier {

    /**
     * 单个列名的形状：MySQL 标识符上限 64 字符，这里与之一致
     */
    private static final String IDENTIFIER_RULE = "^[A-Za-z_][A-Za-z0-9_]{0,63}$";

    private SqlSafeIdentifier() {
    }

    /**
     * 校验单个标识符。
     *
     * @param raw  已经过 {@code toUnderlineCase} 等规范化、马上要拼进 SQL 的那个值
     * @param desc 出错文案里的字段用途名，例如"排序字段"
     * @return 去空白后的原值
     * @throws IllegalArgumentException 不是合法列名形状时抛出，异常文案只回显调用方自己传进来的值
     */
    public static String check(String raw, String desc) {
        String value = raw == null ? null : raw.trim();
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("非法的" + desc + "：不能为空");
        }
        if (!value.matches(IDENTIFIER_RULE)) {
            throw new IllegalArgumentException("非法的" + desc + "：" + value);
        }
        return value;
    }

    /**
     * 把"一个可能写成 a,b 的排序参数"拆成合法列名列表。
     *
     * <p>允许调用方传逗号分隔的多列（接口注释里就写着"多个用逗号分隔"），但一个 token 只对应一次
     * {@code orderByAsc}，绝不把整串原样交给 SQL。有一列非法就整批拒绝。
     */
    public static List<String> splitAndCheck(Object rawParam, String desc) {
        String value = StrUtil.toString(rawParam);
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("非法的" + desc + "：不能为空");
        }
        List<String> columns = new ArrayList<>();
        for (String token : value.split(",")) {
            columns.add(check(StrUtil.toUnderlineCase(token.trim()), desc));
        }
        return columns;
    }
}
