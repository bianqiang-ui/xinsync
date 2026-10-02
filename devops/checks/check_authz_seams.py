#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""权限接缝门禁：判定逻辑写在 AccessControl 里不等于接口用上了它。

本批次修的越权点（复核结论里最要命的一类）：
  - /api/user/add|update|remove    普通用户可增删平台账号
  - /api/user/updatePwd            只按请求体 username 找人，任何登录用户可重置 admin 口令
  - /api/jobGroup/save|update|remove 手动录入的执行器地址会被调度器直连（并带上 accessToken）
  - /api/log/killJob、/api/log/logDetailCat、/api/log/logKill  地址与 PID 来自请求体 = SSRF + 任意 kill
  - /api/log/clearLog              不可回滚的批量删除（type=9 清全库），原先无权限判定
  - /api/job/update|remove|stop|start、/jobcode/save、/api/jobTemplate/*、/api/jobProject/*
    按 id 越权（IDOR）：不看属主就能改/删/停别人的任务；update 还把 user_id 覆写成调用者
  - /api/jobJdbcDatasource 写接口与 /test：数据源没有属主列，是平台资源，试连还是一个 SSRF 面

这类回归的特点是"看着还在、其实没接"：AccessControlTest 只证明判定本身对，
证明不了每个敏感方法都调用了它。上一轮复核还抓到更细的一条——**调用还在、结果被丢掉**
（`AccessControl.requireAdmin();` 但不 return 拒绝体），子串存在型检查对这种完全无感。
所以本门禁判的是"赋值 → 非空即返回"这条闭环，不是字符串出没出现过。
"""
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "datax-admin/src/main/java/com/wugui/datax/admin"

# kind -> 必须出现的"判定调用"正则（结果必须被接住并当场返回，见 guarded_use）
KINDS = {
    "admin": r"AccessControl\.(?:requireAdmin|adminDeny)\s*\(",
    "self": r"AccessControl\.requireSelfOrAdmin\s*\(",
    "owner": r"(?:AccessControl\.denyUnlessAdminOrOwner|denyUnlessCanOperate)\s*\(",
    "stored": r"jobLogMapper\.load\s*\(",
}

# (文件, 方法名, 判定类型)
RULES = [
    ("controller/UserController.java", "add", "admin"),
    ("controller/UserController.java", "update", "admin"),
    ("controller/UserController.java", "remove", "admin"),
    ("controller/UserController.java", "updatePwd", "self"),
    ("controller/JobGroupController.java", "save", "admin"),
    ("controller/JobGroupController.java", "update", "admin"),
    ("controller/JobGroupController.java", "remove", "admin"),
    # kill/看日志必须以库里那条执行记录为准，不能信请求体里传进来的地址和 PID
    ("controller/JobLogController.java", "killJob", "stored"),
    ("controller/JobLogController.java", "logDetailCat", "stored"),
    # 地址锁死之后还剩"谁能按 logId 调"这一层：日志正文含目标库连接串与数据样本，
    # kill 会打断正在写的目标表留下一半数据 —— 两个都必须管理员或本人。
    ("controller/JobLogController.java", "killJob", "owner"),
    ("controller/JobLogController.java", "logDetailCat", "owner"),
    ("controller/JobLogController.java", "logKill", "owner"),
    ("controller/JobLogController.java", "clearLog", "admin"),
    # 按 id 操作资源的归属闭环
    ("controller/JobInfoController.java", "denyUnlessCanOperate", "owner"),
    ("controller/JobInfoController.java", "update", "owner"),
    ("controller/JobInfoController.java", "remove", "owner"),
    ("controller/JobInfoController.java", "pause", "owner"),
    ("controller/JobInfoController.java", "start", "owner"),
    # 手动触发走的是 JobTriggerPoolHelper 而不是 service，接线时最容易整个漏掉：
    # 不判归属的话任何登录用户都能把别人的作业跑一遍，直接往别人的目标表写数。
    ("controller/JobInfoController.java", "triggerJob", "owner"),
    ("controller/JobCodeController.java", "save", "owner"),
    ("controller/JobTemplateController.java", "update", "owner"),
    ("controller/JobTemplateController.java", "remove", "owner"),
    ("controller/JobProjectController.java", "update", "owner"),
    ("controller/JobProjectController.java", "delete", "owner"),
    # 数据源是平台级资源：增删改与试连都要管理员
    ("controller/JobDatasourceController.java", "insert", "admin"),
    ("controller/JobDatasourceController.java", "update", "admin"),
    ("controller/JobDatasourceController.java", "delete", "admin"),
    ("controller/JobDatasourceController.java", "dataSourceTest", "admin"),
]


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


# 方法声明的行首特征。修饰符可以只写 static（包私有判定方法就是这么声明的），
# 也可以一个都不写 —— 但必须出现在行首，否则 call site 的 `AccessControl.isAdmin()` 会被当成声明。
SIG_TMPL = r"(?m)^\s*(?:@\w+\s+)*(?:public|protected|private|static|final|synchronized|abstract)[\w<>\[\], .?]*\b%s\s*\("


def method_body(text, name):
    """返回方法体（含首尾大括号）。

    两个静默通道要堵：
      - 接口/抽象声明 `public ReturnT<String> add(x);` 没有体，原来的写法会抓去"下一个方法"的体，
        下一个方法带守卫就假绿 —— 所以声明后面先出现 ';' 就判"没有体"；
      - 同名重载取第一个，规则里的方法都没有重载（这条由上面的 ';' 判定顺带兜住）。
    """
    sig = re.search(SIG_TMPL % re.escape(name), text)
    if sig is None:
        return None
    brace = text.find("{", sig.end())
    if brace < 0:
        return None
    if ";" in text[sig.end():brace] and ")" not in text[sig.end():brace]:
        return None        # 这是声明不是定义
    depth = 0
    for j in range(brace, len(text)):
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[brace:j + 1]
    return None


def method_bodies(text, name):
    """同名多个重载的全体。isAdmin() 有两个形态（无参 / 收 Collection），
    只看第一个会把"无参那个转发调用"当成判定本体，从而误判。"""
    bodies = []
    for sig in re.finditer(SIG_TMPL % re.escape(name), text):
        brace = text.find("{", sig.end())
        if brace < 0:
            continue
        if ";" in text[sig.end():brace] and ")" not in text[sig.end():brace]:
            continue
        depth = 0
        for j in range(brace, len(text)):
            c = text[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    bodies.append(text[brace:j + 1])
                    break
    return bodies


ASSIGN_RE = r"(?:String|ReturnT<String>|R<Boolean>|JobLog|JobInfo|JobTemplate|JobProject|\w+)\s+(\w+)\s*=\s*%s[^;]*\)\s*;"


def guarded_use(body, kind):
    """判定调用必须"被接住并当场返回"，否则等于没判。

    @return (是否闭环, 说明)
    """
    call_re = KINDS[kind]
    if not re.search(call_re, body):
        return False, "方法体里没有 %s 型判定调用（正则 %s）" % (kind, call_re)

    # 存库行这一型要求的是"取到 == null 就返回"（返回体里不必带上那行对象）；
    # 其余几型要求"!= null 就把判定结果返回给调用方"，否则拒绝文案到不了前端。
    for m in re.finditer(ASSIGN_RE % call_re, body):
        var = m.group(1)
        rest = body[m.end():]
        if kind == "stored":
            if re.search(r"if\s*\(\s*%s\s*==\s*null\s*\)\s*\{?\s*return" % re.escape(var), rest):
                return True, "取到的库里的行被 null 兜住并当场返回"
        elif re.search(r"if\s*\(\s*%s\s*!=\s*null\s*\)\s*\{?\s*return[^;]*%s"
                       % (re.escape(var), re.escape(var)), rest):
            return True, "判定结果被接住，且拒绝时原样返回给前端"
    return False, ("%s 判定调用了但结果没被接住返回（缺 `if (%s) return ...`），越权校验等于掉了"
                   % (kind, "var != null" if kind != "stored" else "var == null"))


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


def table_role_index(sql_text):
    """从 CREATE TABLE `job_user` 的列定义里数 role 是第几列（0 基）。"""
    m = re.search(r"CREATE TABLE `job_user`.*?\((.*?)\n\)\s*ENGINE", sql_text, re.S)
    if not m:
        return -1
    cols = re.findall(r"^\s*`(\w+)`\s+\w", m.group(1), re.M)
    return cols.index("role") if "role" in cols else -1


INSERT_RE = re.compile(
    r"\s*INSERT INTO `job_user`\s*(?:\(([^)]*)\))?\s*VALUES\s*\((.*)\)\s*;?\s*$")


def seed_roles():
    """出厂脚本 job_user 里出现过的 role 字面量。

    这是踩过的坑：列注释写"0-普通用户、1-管理员"，但真正的 INSERT 种子行写的是 'ROLE_ADMIN'。
    权限常量若只按注释取 '1'，结果是**管理员本人也被拒**。所以种子值必须被代码认得。

    列位优先用 INSERT 自己写的列名表；没写列名表才退回按 CREATE TABLE 的列序数。
    之前一律按建表列序取位，dump 里带显式列名或调整列序就会取到别的列。
    """
    sql = ROOT / "bin/db/datax_web.sql"
    if not sql.exists():
        return []
    text = sql.read_text(encoding="utf-8", errors="replace")
    fallback_idx = table_role_index(text)

    values = []
    for line in text.splitlines():
        m = INSERT_RE.match(line)
        if not m:
            continue
        cols = split_top_level(m.group(2))
        if m.group(1):
            names = [c.strip().strip("`").lower() for c in m.group(1).split(",")]
            if "role" not in names:
                continue
            idx = names.index("role")
        else:
            idx = fallback_idx
            if idx < 0:
                return None                 # 结构变了，判不出来 —— 由调用方判 FAIL
        if len(cols) <= idx:
            continue
        role = unquote(cols[idx])
        if role is not None:
            values.append(role)
    return values


def recognized_roles():
    """AccessControl 里声明的角色常量取值；None 表示连声明都找不到。"""
    ac = strip_comments((SRC / "security/AccessControl.java").read_text(encoding="utf-8"))
    known = {"ROLE_ADMIN": "ROLE_ADMIN", "ROLE_ADMIN_LEGACY": "1", "ROLE_NORMAL": "0"}
    recognized = set()
    for name, literal in known.items():
        if re.search(r'String\s+%s\s*=\s*"%s"' % (name, re.escape(literal)), ac):
            recognized.add(literal)
    return recognized


def check_seed_roles():
    """返回 (是否可判定, 消息列表)。种子行缺 role 列时判不出来，按 FAIL 处理。"""
    recognized = recognized_roles()
    if not recognized:
        return False, ["AccessControl 里找不到角色常量定义，权限判定无法核对"]

    roles = seed_roles()
    if roles is None or not roles:
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


def check_is_admin_behavior():
    """常量声明对了还不够：isAdmin() 的比较必须真的引用这些常量。

    上一版只核对常量声明在不在，于是"声明留着、isAdmin 里改成只认 '1'"依然 PASS。
    """
    ac = strip_comments((SRC / "security/AccessControl.java").read_text(encoding="utf-8"))
    bodies = method_bodies(ac, "isAdmin")
    if not bodies:
        return False, "AccessControl 里找不到 isAdmin() 方法体"
    if not any(re.search(r"ROLE_ADMIN(?:_LEGACY)?\.equals\s*\(", b) for b in bodies):
        return False, ("isAdmin() 的各个重载都没有用 ROLE_ADMIN / ROLE_ADMIN_LEGACY 常量做比较，"
                       "常量只是声明着、判定其实没用它")
    return True, "OK   isAdmin() 用角色常量做比较"


def main():
    failed = []
    for rel, method, kind in RULES:
        if not kind in KINDS:
            failed.append("规则 %s#%s 的判定类型 %s 不在 KINDS 里（写错了）" % (rel, method, kind))
            continue
        path = SRC / rel
        if not path.exists():
            failed.append("%s 不存在" % rel)
            continue
        text = strip_comments(path.read_text(encoding="utf-8"))
        body = method_body(text, method)
        if body is None:
            failed.append("%s 里找不到方法 %s() 的**实现体**（被改名、删掉，或写成了接口声明）" % (rel, method))
            continue
        ok, why = guarded_use(body, kind)
        if ok:
            print("OK   %s#%s -> %s 闭环" % (rel.split("/")[-1], method, kind))
        else:
            failed.append("%s#%s：%s" % (rel, method, why))

    # AccessControl 本身必须真的被 SecurityContext 驱动，而不是又退回"信请求体"。
    # 判 SecurityContextHolder.getContext() 而不是裸的类名：光留着 import 也能满足后者。
    raw = (SRC / "security/AccessControl.java").read_text(encoding="utf-8")
    ac = strip_comments(raw)
    if "SecurityContextHolder.getContext()" not in ac:
        failed.append("AccessControl 不再从 SecurityContext 取身份，等于没有认证主体")
    else:
        print("OK   AccessControl -> SecurityContextHolder.getContext()")

    ok, why = check_is_admin_behavior()
    if ok:
        print(why)
    else:
        failed.append(why)

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
    print("PASS: 越权接缝全部在岗（%d 条闭环判定 + 角色取值域）" % len(RULES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
