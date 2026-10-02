#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SQL 标识符白名单门禁：判定写在 SqlSafeIdentifier 里不等于拼装处真的用了它。

守的是批次 10-A 修的那条注入面：分页接口把 `ascs` / `descs` / 列查询条件的 key 经
`StrUtil.toUnderlineCase` 之后**原样拼**进 `ORDER BY` / `WHERE`（mybatis-plus 的列名参数
不走预编译占位符），于是排序字段成了"用户可控且必然出现在 SQL 结构位置"的注入点。

为什么不能只靠单测：`SqlSafeIdentifierTest` 证明正则本身严，`BaseFormOrderByWhitelistTest`
证明 BaseForm 用了它；但这两条都挡不住第三种退化——**又抄出一份没带白名单的拼装代码**
（本仓库历史上就出现过：JobRegistryController 把 BaseForm 的组装逻辑整段抄了一遍）。
所以这里判的是"拼装点的数量与形态"，不是"字符串有没有出现过"。
"""
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "datax-admin/src/main/java"

# 唯一允许的 ORDER BY 拼装点（相对 datax-admin/src/main/java/com/wugui/datax/admin）
ALLOWED_ORDERBY_SITES = {"controller/BaseForm.java"}

# 直接把规范化后的入参塞进 wrapper 方法的写法一律禁止（必须先进白名单变量）
RAW_INJECTION = re.compile(
    r"\.(?:eq|ne|gt|ge|lt|le|like|notLike|in|notIn|orderBy|orderByAsc|orderByDesc)\s*\(\s*StrUtil\.toUnderlineCase"
)

failed = []


def java_files():
    for p in sorted(SRC.rglob("*.java")):
        yield p, p.read_text(encoding="utf-8", errors="replace")


def strip_noise(text):
    """剥掉注释但保持行号：块注释按整段剥（逐行判 `*` 开头会把续行当代码）。"""
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return "\n".join("" if ln.lstrip().startswith("//") else ln for ln in text.splitlines())


def check_single_implementation():
    sites = {}
    for path, text in java_files():
        code = strip_noise(text)
        if re.search(r"\.orderBy(?:Asc|Desc)?\s*\(", code):
            rel = str(path.relative_to(SRC)).replace("\\", "/")
            rel = rel.replace("com/wugui/datax/admin/", "")
            # 工具类自己的 javadoc 里提到过 orderByAsc，剥注释后不会命中
            sites[rel] = code
    if set(sites) != ALLOWED_ORDERBY_SITES:
        failed.append(
            "ORDER BY 拼装点必须只有 %s，实际是 %s（新增拼装点必须走 SqlSafeIdentifier，不许另抄一份）"
            % (sorted(ALLOWED_ORDERBY_SITES), sorted(sites) or ["（一处都没有）"])
        )
        return
    body = sites["controller/BaseForm.java"]
    if "SqlSafeIdentifier.splitAndCheck" not in body:
        failed.append("BaseForm 的 ascs/descs 不再经过 SqlSafeIdentifier.splitAndCheck，白名单被绕过")
        return
    if "SqlSafeIdentifier.check(" not in body:
        failed.append("BaseForm 的列查询条件 key 不再经过 SqlSafeIdentifier.check，白名单被绕过")
        return
    print("OK   ORDER BY / WHERE 列名拼装收在唯一一处 controller/BaseForm.java，且两段都过 SqlSafeIdentifier")


def check_no_raw_identifiers():
    hits = []
    for path, text in java_files():
        for no, line in enumerate(strip_noise(text).splitlines(), 1):
            if RAW_INJECTION.search(line):
                hits.append("%s:%d" % (path.relative_to(SRC), no))
    if hits:
        failed.append("发现把规范化入参直接喂进 wrapper 的写法（必须先过白名单）：" + ", ".join(hits))
    else:
        print("OK   没有任何 .eq/.like/.orderBy*(StrUtil.toUnderlineCase(用户入参)) 的裸拼装")


def check_rule_is_still_a_whitelist():
    util = SRC / "com/wugui/datax/admin/util/SqlSafeIdentifier.java"
    if not util.exists():
        failed.append("SqlSafeIdentifier 不存在了，白名单整体消失")
        return
    text = util.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"IDENTIFIER_RULE\s*=\s*\"(.*?)\"", text)
    if not m:
        failed.append("找不到 IDENTIFIER_RULE 常量，白名单正则被改名或删除")
        return
    rule = m.group(1)
    if not (rule.startswith("^") and rule.endswith("$")):
        failed.append("IDENTIFIER_RULE 不再是全串锚定：%s" % rule)
        return
    body = rule[1:-1]
    # 必须真有"字符类白名单"：只靠锚定 + 通配（如 ^.{1,200}$）看着严，其实放行任意载荷。
    # 这条是被反证逼出来的：把正则换成 ^.{1,200}$ 时，只查锚定/取反/长度的旧版本照样 PASS。
    if not re.search(r"\[A-Za-z", body):
        failed.append("IDENTIFIER_RULE 里没有正向字符类（^[A-Za-z...]），等于没白名单：%s" % rule)
        return
    stripped = re.sub(r"\[[^\]]*\]", "", body)
    wildcards = sorted({c for c in stripped if c in ".*+?|()[]\\"})
    if wildcards:
        failed.append(
            "IDENTIFIER_RULE 含通配/分组元字符 %s，字符类之外必须是字面量：%s" % ("".join(wildcards), rule)
        )
        return
    print("OK   IDENTIFIER_RULE 是全串锚定的正向字符类白名单：%s" % rule)


def check_no_forgotten_duplicate():
    dup = SRC / "com/wugui/datax/admin/controller/JobRegistryController.java"
    if not dup.exists():
        return
    code = strip_noise(dup.read_text(encoding="utf-8", errors="replace"))
    if "toUnderlineCase" in code or "filterPageParams" in code:
        failed.append("JobRegistryController 又自己抄了一份查询拼装（该复用 BaseForm.pageQueryWrapperCustom）")
    else:
        print("OK   JobRegistryController 已收敛到 BaseForm 的同一实现处")


def main():
    check_single_implementation()
    check_no_raw_identifiers()
    check_rule_is_still_a_whitelist()
    check_no_forgotten_duplicate()

    if failed:
        for f in failed:
            print("FAIL: " + f)
        return 1
    print("PASS: SQL 标识符白名单在岗（唯一拼装点 + 锚定白名单正则 + 无重复实现）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
