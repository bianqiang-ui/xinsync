#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""建表脚本可重复执行门禁：`bin/db/datax_web.sql` 必须能被同一个库连跑两遍而不报错、不丢配置、不插重复行。

守的是批次15 导入冒烟实测出来的那条缺陷（不是读代码读出来的）：
把脚本导进一个干净的 MySQL 8 库之后，往 `tdsql_shard_rule` 手工登记一条规则，
再跑第二遍 —— 第二遍在 `job_project` 上直接崩
（`1050 Table 'job_project' already exists`）。根因是上游那份脚本里
**12 条 CREATE TABLE 只有 11 条 DROP**，`job_project` 是漏的那一张；
这条断点在我们改之前一直存在，而且断在没人会去读的 SQL 里。

为什么要有机器判据而不是改完就算了：漏一张表和漏十张表的症状完全一样
——第一遍永远正常，缺陷只在第二遍暴露。这种形状靠人眼数 DROP 是数不住的。

四条判据：
1. **每张表都要有归位**：`CREATE TABLE t` 要么带 `IF NOT EXISTS`，要么前面有一条同表的
   `DROP TABLE IF EXISTS`。`DROP TABLE t`（不带 IF EXISTS）不算：干净库上第一遍就会因
   「表不存在」报错中断，等于把缺陷从第二遍挪到第一遍。
2. **活下来的表不许插重复行**：用 `IF NOT EXISTS` 建、又不删的表，重复执行时表结构和出厂行都还在，
   此时脚本里若有裸 `INSERT INTO t`，第二遍就插出重复的出厂数据
   （这类缺陷不报错，只在运行期长成脏数据）。必须写成 `INSERT IGNORE` 或 `REPLACE INTO`。
   实测对得上：`job_group`/`job_lock`/`job_log_report`/`job_user` 四张带出厂数据的表都走「先删再建」，
   所以本条现在钉的是例外表那一面。
3. **例外必须显式登记**：`tdsql_shard_rule` 是刻意不留 DROP 的那张（存用户配置，删一次就清空，
   而清空之后的症状与「用户还没配置」一模一样，查不出来）。
   它必须带 `IF NOT EXISTS`，且全文不得出现任何对它的 DROP；新增例外要同时改 `KEEP_ON_REPLAY`，
   没改就红灯。
4. **解析器要自证**：语句是按「跳过注释与引号串」扫出来的，而注释里也写着
   `CREATE TABLE IF NOT EXISTS` 这个词（批次14 的说明段就写了），所以
   原始文本里行首 `CREATE TABLE` 的出现次数必须等于解析出的条数；
   多出来的那一次说明有一种写法没被认出来 —— 直接 FAIL，不许「没扫到 = 没问题」。

不需要凭据、不需要实例：本门禁只读这一个 SQL 文件。
真库上的两遍导入由 `tmp/draft/smoke_import_b15.py` 覆盖（台账，不外发）。
"""
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / "bin" / "db" / "datax_web.sql"

# 判据 3：重复执行时**故意**保留内容的那些表（存用户配置，删一次就清空）
KEEP_ON_REPLAY = {"tdsql_shard_rule"}

IDENT = r"(?:`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)"
CREATE_RE = re.compile(r"^CREATE\s+TABLE\s+(IF\s+NOT\s+EXISTS\s+)?(" + IDENT + r")", re.I)
DROP_RE = re.compile(r"^DROP\s+TABLE\s+(IF\s+EXISTS\s+)?(" + IDENT + r")", re.I)
INSERT_RE = re.compile(r"^(INSERT(?:\s+IGNORE)?\s+INTO|REPLACE\s+INTO)\s+(" + IDENT + r")", re.I)
# 原始文本侧的自查计数：只数行首的 CREATE TABLE，注释里的不算
RAW_CREATE_RE = re.compile(r"(?im)^\s*CREATE\s+TABLE")


def norm(ident):
    return ident.strip("`").lower()


def strip_comments(sql):
    """把 `-- `、`#`、`/* */` 注释压成空格，字符串字面量与反引号标识符**原样保留**。

    两者必须分开处理：注释不是内容，整段可以丢；反引号里的名字和引号串里的值恰恰是内容。
    列定义的 `COMMENT '…；只作记录…'` 里有全角分号，切句按 ASCII 分号判，字符串保留才不会切碎。
    """
    out = []
    i, n = 0, len(sql)
    while i < n:
        ch = sql[i]
        if ch == "'":
            j = i + 1
            while j < n:
                if sql[j] == "\\" and j + 1 < n:
                    j += 2
                    continue
                if sql[j] == "'":
                    if j + 1 < n and sql[j + 1] == "'":
                        j += 2
                        continue
                    break
                j += 1
            out.append(sql[i:min(j + 1, n)])
            i = j + 1
        elif ch == "`":
            j = sql.find("`", i + 1)
            if j < 0:
                out.append(sql[i:])
                break
            out.append(sql[i:j + 1])
            i = j + 1
        elif sql.startswith("--", i) and (i + 2 >= n or sql[i + 2] in " \t\r\n-"):
            j = sql.find("\n", i)
            out.append(" ")
            i = n if j < 0 else j
        elif ch == "#":
            j = sql.find("\n", i)
            out.append(" ")
            i = n if j < 0 else j
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            out.append(" ")
            i = n if j < 0 else j + 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def split_statements(sql):
    """按不在引号/反引号内的 ASCII `;` 切句。"""
    stmts, buf, i, n = [], [], 0, len(sql)
    while i < n:
        ch = sql[i]
        if ch in "'`":
            close = sql.find(ch, i + 1)
            if close < 0:
                buf.append(sql[i:])
                break
            buf.append(sql[i:close + 1])
            i = close + 1
            continue
        if ch == ";":
            stmt = "".join(buf).strip()
            if stmt:
                stmts.append(stmt)
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        stmts.append(tail)
    return stmts


def classify(stmts):
    creates, drops, bare_drops, inserts = [], [], [], []
    for st in stmts:
        m = CREATE_RE.match(st)
        if m:
            creates.append((norm(m.group(2)), bool(m.group(1))))
            continue
        m = DROP_RE.match(st)
        if m:
            (drops if m.group(1) else bare_drops).append(norm(m.group(2)))
            continue
        m = INSERT_RE.match(st)
        if m:
            head = m.group(1).upper()
            kind = "insert-ignore" if "IGNORE" in head else (
                "replace" if head.startswith("REPLACE") else "insert")
            inserts.append((norm(m.group(2)), kind))
    return creates, drops, bare_drops, inserts


failed = []


def main():
    if not SQL.is_file():
        print("FAIL: 找不到建表脚本 %s —— 交付物里的导入入口必须在" % SQL.relative_to(ROOT))
        return 1

    raw = SQL.read_text(encoding="utf-8", errors="replace")
    stmts = split_statements(strip_comments(raw))
    creates, drops, bare_drops, inserts = classify(stmts)

    # 判据 4：解析器自证
    if not creates:
        print("FAIL: 一条 CREATE TABLE 都没解析出来（脚本被清空或写法不认识）—— 判据不成立")
        return 1
    raw_count = len(RAW_CREATE_RE.findall(raw))
    if raw_count != len(creates):
        print("FAIL: 原文里行首 CREATE TABLE 出现 %d 次，解析器只认出 %d 条 —— "
              "有一种写法没被认出来，判据正在静默漏表" % (raw_count, len(creates)))
        return 1

    names = [t for t, _ in creates]
    if_not_exists = {t for t, ok in creates if ok}
    dropped = set(drops)

    # 判据 1：同名表不得重复建；没写 IF NOT EXISTS 的必须有在前面的 DROP TABLE IF EXISTS
    for table in names:
        if names.count(table) > 1:
            failed.append("%s 被同名 CREATE TABLE 建了 %d 次" % (table, names.count(table)))
    order = []
    for st in stmts:
        m = CREATE_RE.match(st)
        if m:
            order.append(("create", norm(m.group(2))))
            continue
        m = DROP_RE.match(st)
        if m:
            order.append(("drop-guarded" if m.group(1) else "drop-bare", norm(m.group(2))))
    for table, ok in creates:
        if ok:
            continue
        first_idx = order.index(("create", table))
        if not any(k == "drop-guarded" and t == table for k, t in order[:first_idx]):
            failed.append("%s 既没有 IF NOT EXISTS，前面也没有 DROP TABLE IF EXISTS —— "
                          "同一个库跑第二遍会在这里报 1050 中断" % table)

    # 不带 IF EXISTS 的删表：干净库上第一遍就报「表不存在」并中断，重复执行口径直接失效
    for table in sorted(set(bare_drops)):
        failed.append("%s 的 DROP TABLE 不带 IF EXISTS —— 干净库上第一遍就会报表不存在而中断，"
                      "这一句必须写成 DROP TABLE IF EXISTS" % table)

    # 判据 2：重复执行后仍保留内容的表，不得被裸 INSERT 追加出厂行
    surviving = {t for t in set(names) if t in if_not_exists and t not in dropped}
    for table, kind in inserts:
        if table in surviving and kind == "insert":
            failed.append("%s 在重复执行时不会被重建，脚本却对它裸 INSERT —— "
                          "第二遍会插出重复的出厂数据（该写 INSERT IGNORE 或 REPLACE INTO）" % table)

    # 判据 3：例外面必须仍是例外，且不得有未登记的例外
    for table in sorted(KEEP_ON_REPLAY):
        if table not in names:
            failed.append("登记的例外表 %s 在脚本里找不到建表语句" % table)
            continue
        if table not in if_not_exists:
            failed.append("例外表 %s 没有写 CREATE TABLE IF NOT EXISTS" % table)
        if table in dropped or table in set(bare_drops):
            failed.append("例外表 %s 出现了删表语句 —— 这张表存用户配置，删一次就清空，"
                          "而清空之后的症状与用户还没配置一模一样，查不出来" % table)
    unregistered = sorted(surviving - KEEP_ON_REPLAY)
    if unregistered:
        failed.append("出现了未登记的「重复执行会保留内容」的表：%s —— 新增例外必须同时更新本门禁的 "
                      "KEEP_ON_REPLAY，否则没人知道它是刻意的" % "、".join(unregistered))

    if failed:
        for f in failed:
            print("FAIL: " + f)
        return 1
    print("PASS: 建表脚本可重复执行（%d 张表：%d 张先删再建、%d 张刻意保留且无删表语句，"
          "出厂 INSERT 无重复插入风险）"
          % (len(names), len(names) - len(if_not_exists), len(KEEP_ON_REPLAY)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
