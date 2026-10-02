#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""权限接缝门禁：判定逻辑写在 AccessControl 里不等于接口用上了它。

本批次修的越权点（复核结论里最要命的一类）：
  - /api/user/add|update|remove    普通用户可增删平台账号
  - /api/user/updatePwd            只按请求体 username 找人，任何登录用户可重置 admin 口令
  - /api/jobGroup/save|update|remove 手动录入的执行器地址会被调度器直连（并带上 accessToken）
  - /api/log/killJob、/api/log/logDetailCat  地址与 PID 全部来自请求体 = SSRF + 任意进程 kill

这类回归的特点是"看着还在、其实没接"：AccessControlTest 只证明判定本身对，
证明不了每个敏感方法都调用了它。所以接缝必须由脚本每次复跑，而不靠人眼 review。
"""
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "datax-admin/src/main/java/com/wugui/datax/admin"

# (文件, 方法名, 方法体内必须出现的标记列表)
RULES = [
    ("controller/UserController.java", "add", ["AccessControl.requireAdmin"]),
    ("controller/UserController.java", "update", ["AccessControl.requireAdmin"]),
    ("controller/UserController.java", "remove", ["AccessControl.requireAdmin"]),
    ("controller/UserController.java", "updatePwd", ["AccessControl.requireSelfOrAdmin"]),
    ("controller/JobGroupController.java", "save", ["AccessControl.requireAdmin"]),
    ("controller/JobGroupController.java", "update", ["AccessControl.requireAdmin"]),
    ("controller/JobGroupController.java", "remove", ["AccessControl.requireAdmin"]),
    # kill/看日志必须以库里那条执行记录为准，不能信请求体里传进来的地址和 PID
    ("controller/JobLogController.java", "killJob", ["jobLogMapper.load"]),
    ("controller/JobLogController.java", "logDetailCat", ["jobLogMapper.load"]),
]


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def method_body(text, name):
    """返回方法体（含首尾大括号）；同名重载取第一个，规则里的方法都没有重载。"""
    sig = re.search(r"\b(?:public|private|protected)\s+[\w<>\[\], .?]+\b%s\s*\(" % re.escape(name), text)
    if sig is None:
        return None
    i = text.index("{", sig.end())
    depth = 0
    for j in range(i, len(text)):
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
    return None


def split_top_level(s):
    """按顶层逗号切 VALUES(...) 的内容：反引号/引号内的逗号不算分隔，'' 视为转义。"""
    out, cur, depth, quote = [], [], 0, None
    i = 0
    while i < len(s):
        c = s[i]
        if quote:
            cur.append(c)
            if c == quote:
                if i + 1 < len(s) and s[i + 1] == quote:   # '' / "" 是转义，不是结束
                    cur.append(s[i + 1])
                    i += 2
                    continue
                quote = None
            i += 1
            continue
        if c in "'\"`":
            quote = c
            cur.append(c)
            i += 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        if c == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    if cur:
        out.append("".join(cur).strip())
    return out


def unquote(literal):
    t = literal.strip()
    if len(t) >= 2 and t[0] == "'" and t[-1] == "'":
        return t[1:-1].replace("''", "'")
    return None


def role_column_index(sql_text):
    """从 CREATE TABLE `job_user` 的列定义里数 role 是第几列（0 基）。"""
    m = re.search(r"CREATE TABLE `job_user`.*?\((.*?)\n\)\s*ENGINE", sql_text, re.S)
    if not m:
        return -1
    cols = re.findall(r"^\s*`(\w+)`\s+\w", m.group(1), re.M)
    return cols.index("role") if "role" in cols else -1


def seed_roles():
    """出厂脚本 job_user 里出现过的 role 字面量。

    这是踩过的坑：列注释写"0-普通用户、1-管理员"，但真正的 INSERT 种子行写的是 'ROLE_ADMIN'。
    权限常量若只按注释取 '1'，结果是**管理员本人也被拒**。所以种子值必须被代码认得。

    取值按 CREATE TABLE 的列顺序定位，不能"数有几个带引号的量"——id 是数字、permission 是 NULL，
    带引号的只有 3 个，那样数会直接漏掉 role。
    """
    sql = ROOT / "bin/db/datax_web.sql"
    if not sql.exists():
        return []
    text = sql.read_text(encoding="utf-8", errors="replace")
    idx = role_column_index(text)
    if idx < 0:
        return None                     # 结构变了，判不出来 —— 由调用方判 FAIL

    values = []
    for line in text.splitlines():
        m = re.match(r"\s*INSERT INTO `job_user`(?:\s*\([^)]*\))?\s*VALUES\s*\((.*)\)\s*;?\s*$", line)
        if not m:
            continue
        cols = split_top_level(m.group(1))
        if len(cols) <= idx:
            continue
        role = unquote(cols[idx])
        if role is not None:
            values.append(role)
    return values


def check_seed_roles():
    """返回 (是否可判定, 消息列表)。种子行缺 role 列时判不出来，按 FAIL 处理。"""
    ac = (SRC / "security/AccessControl.java").read_text(encoding="utf-8")
    known = {"ROLE_ADMIN": "ROLE_ADMIN", "ROLE_ADMIN_LEGACY": "1", "ROLE_NORMAL": "0"}
    recognized = set()
    for name, literal in known.items():
        if re.search(r'String\s+%s\s*=\s*"%s"' % (name, re.escape(literal)), ac):
            recognized.add(literal)
    if not recognized:
        return False, ["AccessControl 里找不到角色常量定义，权限判定无法核对"]

    roles = seed_roles()
    if not roles:
        return False, ["bin/db/datax_web.sql 里没解析出任何 job_user 种子行"
                       "（表结构或脚本写法变了，本门禁需同步更新）"]

    msgs = []
    bad = []
    for role in sorted(set(roles)):
        if role in recognized:
            msgs.append("OK   种子 role '%s' 被 AccessControl 认得" % role)
        else:
            bad.append("种子 role '%s' 不在权限模型的识别范围内（认识 %s）—— "
                       "这种账号既进不了管理页也调不动管理接口" % (role, sorted(recognized)))
    return (not bad), (msgs + ["FAIL: " + b for b in bad])


def main():
    failed = []
    for rel, method, marks in RULES:
        path = SRC / rel
        if not path.exists():
            failed.append("%s 不存在" % rel)
            continue
        text = strip_comments(path.read_text(encoding="utf-8"))
        body = method_body(text, method)
        if body is None:
            failed.append("%s 里找不到方法 %s()（被改名或删掉了？）" % (rel, method))
            continue
        for mark in marks:
            if mark not in body:
                failed.append("%s#%s 没有调用 %s —— 越权校验掉了" % (rel, method, mark))
            else:
                print("OK   %s#%s -> %s" % (rel.split("/")[-1], method, mark))

    # AccessControl 本身必须真的被 SecurityContext 驱动，而不是又退回"信请求体"
    ac = (SRC / "security/AccessControl.java").read_text(encoding="utf-8")
    if "SecurityContextHolder" not in ac:
        failed.append("AccessControl 不再从 SecurityContext 取身份，等于没有认证主体")
    else:
        print("OK   AccessControl -> SecurityContextHolder")

    # 种子 role 必须落在权限模型认得的取值域里
    seed_ok, seed_msgs = check_seed_roles()
    for m in seed_msgs:
        print(m)
    if not seed_ok:
        failed.append("种子 role / 角色常量核对未通过（详见上面的 FAIL 行）")

    if failed:
        for f in failed:
            print("FAIL: " + f)
        return 1
    print("PASS: 越权接缝全部在岗")
    return 0


if __name__ == "__main__":
    sys.exit(main())
