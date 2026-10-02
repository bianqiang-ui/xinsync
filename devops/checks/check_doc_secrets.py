#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""门禁：对外文档里不得出现"可直接抄用的密钥字面值"，也不得出现维护者的本机路径。

守的两条：

1) 密钥字面值。`doc/XinSync-Windows-启动指南.md` 一度在 7 处写着
   `DATAX_JWT_SECRET="XinSync2026DemoSecretKeyAtLeast32Chars"` 这类"演示值"。
   文档是公开的，于是任何照做部署的人**共用同一把密钥**：
     - JWT 密钥公开 → 任何人都能给自己签一张管理员 token（登录面直接归零）；
     - accessToken 公开 → 四个回调接口 `/api/callback|registry|log` 全裸（执行器注册与回报都是匿名可伪造）；
     - AES 密钥公开 → 数据源表里那串"密文"口令等同于明文（见 10-G 的同一判据）。
   更糟的是这类值一旦进了 git 历史就再也擦不干净，所以只能从一开始就不写进去。
   判据取"赋值右边的值是不是占位符"，而不是枚举黑名单：黑名单挡不住下一个换一个名字的演示值。

2) 本机绝对路径（`D:\code\githubCode\...`、`/d/code/...`、`C:\Users\<name>`）。
   这类路径照做必失败（别人的机器上没有这个目录），同时把维护者的用户名/目录结构一起发出去。

范围为什么只圈 fork 自己的对外文档（README*、docs/、doc/XinSync-*）：
上游老文档里带着 `/Users/xxx`、`/home/xxx` 这类示例路径，一上来就把全仓库扫成红灯的门禁
只会被下一次构建忽略掉 —— 门禁必须今天就能绿，才谈得上守住明天。

两条规则的覆盖面不同，这是刻意的：
  - 密钥字面值：所有对外文档一起扫（包括开发日志）；
  - 本机路径：只扫**照做类**文档（README、部署/启动指南）。`docs/devlog.md` 与
    `docs/technical-manual.md` 里的工作副本路径是"当时在哪台机器上实测的"历史记录，
    把它们批量改成占位符等于把开发日志改成假事实 —— 那类文档要保真，不要保整洁。
"""
import io
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

SECRET_VARS = ("DATAX_JWT_SECRET", "DATAX_AES_KEY", "DATAX_ACCESS_TOKEN")

# 已知泄漏过的字面演示值：即使换成占位符写法，这几个串也永远不能再出现在文档里
BANNED_LITERALS = (
    "XinSync2026DemoSecretKeyAtLeast32Chars",
    "XinSyncDemo2026AES",
    "xinsync-demo-token",
)

# 占位符的特征串：命中任意一个就认为这里是"让部署者自己填"，不是可直接抄的值
PLACEHOLDER_MARKS = ("<", ">", "你的", "您", "生成", "替换", "填", "REPLACE", "your", "Your", "...")

# <变量名> = <右值>，覆盖 powershell / bash / IDEA 一行串 三种写法
ASSIGN_RE = re.compile(r"(DATAX_(?:JWT_SECRET|AES_KEY|ACCESS_TOKEN))\s*(?:=|\s)\s*\"?([^\";\n]*)")

LOCAL_PATH_RES = (
    ("仓库开发目录（他人机器上不存在）", re.compile(r"[A-Za-z]:[\\/](?:[^\s\"'`|]{1,32}[\\/])*githubCode", re.I)),
    ("仓库开发目录（他人机器上不存在）", re.compile(r"/[a-z]/code/githubCode/", re.I)),
    ("Windows 用户目录（泄漏维护者用户名）", re.compile(r"[A-Za-z]:[\\/](?:Program Files[\\/])?[Uu]sers[\\/][^\s\"'`|\\]+")),
    ("Linux/mac 用户目录（泄漏维护者用户名）", re.compile(r"(?:^|[\s\"'`(])/(?:home|Users)/(?!\$\{|xxx|<)[A-Za-z0-9._-]{3,}/")),
)


def doc_files():
    files = []
    for pattern in ("README*.md", "docs/*.md", "doc/XinSync-*.md"):
        files.extend(ROOT.glob(pattern))
    return sorted(set(files))


def howto_files():
    """"照做类"文档：读者会一行行复制的部署/启动指南，路径必须是可移植的。"""
    files = []
    for pattern in ("README*.md", "doc/XinSync-*.md"):
        files.extend(ROOT.glob(pattern))
    return sorted(set(files))


def value_is_placeholder(value):
    stripped = value.strip()
    if not stripped:
        return True
    if any(mark in stripped for mark in PLACEHOLDER_MARKS):
        return True
    # shell/编排引用：${VAR}、$VAR、%VAR% —— 值不在文档里
    if "$" in stripped or "%" in stripped or "{" in stripped:
        return True
    return False


def check_secret_literals(text, rel, findings):
    for literal in BANNED_LITERALS:
        if literal in text:
            findings.append("%s：文档里出现了已泄漏过的演示密钥字面值「%s」" % (rel, literal))
    for match in ASSIGN_RE.finditer(text):
        var, value = match.group(1), match.group(2)
        if value_is_placeholder(value):
            continue
        findings.append(
            "%s：%s 被赋成了可直接照抄的字面值「%s」—— 公开文档里的常量等于没有密钥，"
            "改成 <生成的随机串> 占位符并给出生成命令" % (rel, var, value.strip()[:48]))


def check_local_paths(text, rel, findings):
    for line_no, line in enumerate(text.splitlines(), 1):
        for reason, pattern in LOCAL_PATH_RES:
            if pattern.search(line):
                findings.append("%s:%d：%s —— %s" % (rel, line_no, reason, line.strip()[:80]))


def main():
    docs = [p for p in doc_files() if p.is_file()]
    if not docs:
        sys.stdout.write("FAIL: 一个对外文档都没扫到（README*/docs//doc/XinSync-*），门禁本身失效\n")
        return 1

    findings = []
    howto = howto_files()
    for path in docs:
        rel = path.relative_to(ROOT).as_posix()
        text = io.open(str(path), encoding="utf-8", errors="replace").read()
        check_secret_literals(text, rel, findings)
        # docs/ 下的开发日志与手册里出现的绝对路径是"这份记录产生于哪台机器"的出处信息，
        # 抹掉它会让证据变得不具体；只有读者会照着敲的文档才要求路径可移植。
        if path in howto:
            check_local_paths(text, rel, findings)

    sys.stdout.write("扫描对外文档 %d 份\n" % len(docs))
    if findings:
        for item in findings:
            sys.stdout.write("FAIL: %s\n" % item)
        sys.stdout.write("\n共 %d 条：密钥字面值与本机路径都不允许出现在对外文档里\n" % len(findings))
        return 1
    sys.stdout.write("PASS: 对外文档无可用密钥字面值、无本机路径（%d 份）\n" % len(docs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
