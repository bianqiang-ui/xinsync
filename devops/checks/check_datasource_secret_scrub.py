#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""数据源口令门禁：读接口的响应体里不能有 jdbcPassword，且剥离点只能有一处。

守的是批次 10-G 修的那条泄漏面：`/api/jobJdbcDatasource`（分页）、`/all`、`/{id}` 三个只读接口
把 `job_jdbc_datasource.jdbc_password` 原样发给任何登录用户。库里存的是 AES 密文，但
`datasource.aes.key` 有出厂默认值且写在本仓库里，拿到密文的人只需再读一次公开源码就有明文口令。
**为什么不能靠权限收口**：普通用户建作业必须浏览数据源列表，把只读接口收归管理员会砍掉正常流程。

为什么不能只靠单测（`JobDatasourceSecretScrubTest`）：单测覆盖的是"眼下这三个接口"，
挡不住下面三种退化 ——
  1) 有人新加一个返回 JobDatasource 的 GET 接口，忘了剥口令（最常见，且完全静默）；
  2) 有人把剥离动作"顺手"挪进 service（看着更彻底，实际会让 `update()` 拿不到库里旧值做比较，
     于是"只改名称"的编辑会把连接口令静默清空 —— 失败要等到下一次作业连库才暴露）；
  3) 调用了剥离方法但返回的是**另一个**未剥离的对象（`hideSecret(copy)` 后 `return success(origin)`）。
所以这里判的是"GET 出口的形状 + 剥离点的数量 + 回提空值的处置"，不是"字符串有没有出现过"。
"""
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADMIN_SRC = ROOT / "datax-admin/src/main/java/com/wugui/datax/admin"
CONTROLLER_DIR = ADMIN_SRC / "controller"
DATASOURCE_CONTROLLER = CONTROLLER_DIR / "JobDatasourceController.java"
DATASOURCE_SERVICE_IMPL = ADMIN_SRC / "service/impl/JobDatasourceServiceImpl.java"

# 剥离动作的唯一允许落点（相对 admin 源码根）
ALLOWED_SCRUB_SITES = {"controller/JobDatasourceController.java"}

failed = []


def ok(msg):
    print("OK   " + msg)


def fail(msg):
    failed.append(msg)
    print("FAIL " + msg)


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def strip_comments(text):
    """剥注释但保持行号，避免用注释里的历史代码骗过判定。"""
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return "\n".join("" if ln.lstrip().startswith("//") else ln for ln in text.splitlines())


def annotation_spans(lines):
    """每行属于哪些注解（多行注解要整段算）。

    `@ApiImplicitParams( {@ApiImplicitParam(...), ... } )` 会占掉十几行，
    按"上一行是不是以 @ 开头"找 @GetMapping 会把这种写法漏掉 —— 分页接口就是这么漏的。
    """
    res = [set() for _ in lines]
    k = 0
    while k < len(lines):
        s = lines[k].strip()
        m = re.match(r"@(\w+)", s)
        if m:
            depth = s.count("(") - s.count(")") + s.count("{") - s.count("}")
            end = k
            while depth > 0 and end + 1 < len(lines):
                end += 1
                t = lines[end]
                depth += t.count("(") - t.count(")") + t.count("{") - t.count("}")
            for idx in range(k, end + 1):
                res[idx].add(m.group(1))
            k = end + 1
            continue
        k += 1
    return res


def method_bodies(text):
    """返回 [(方法名, 是否 @GetMapping, 方法体)]，方法体按大括号配对取出。"""
    lines = text.splitlines()
    spans = annotation_spans(lines)
    out = []
    i = 0
    sig = re.compile(r"^\s*(?:public|protected|private)\s+[\w<>,\[\]\.\s\$?]*?\b(\w+)\s*\(")
    while i < len(lines):
        m = sig.match(lines[i])
        if m:
            name = m.group(1)
            is_get = False
            j = i - 1
            while j >= 0:
                if not lines[j].strip():
                    j -= 1
                    continue
                if spans[j]:
                    if "GetMapping" in spans[j]:
                        is_get = True
                    j -= 1
                    continue
                break
            # 从签名所在行起做括号配对
            k, depth, started = i, 0, False
            body = []
            while k < len(lines):
                for ch in lines[k]:
                    if ch == "{":
                        depth += 1
                        started = True
                    elif ch == "}":
                        depth -= 1
                if started:
                    body.append(lines[k])
                if started and depth == 0:
                    break
                k += 1
            out.append((name, is_get, "\n".join(body)))
            i = k + 1 if started else i + 1
            continue
        i += 1
    return out


def java_files(base):
    for p in sorted(base.rglob("*.java")):
        yield p, strip_comments(read(p))


def check_get_exports_scrub():
    """每个返回 JobDatasource 的 GET 接口都必须过 hideSecret，且过的就是返回出去的那个对象。"""
    found = 0
    for path, text in java_files(CONTROLLER_DIR):
        for name, is_get, body in method_bodies(text):
            if not is_get or "JobDatasource" not in body:
                continue
            # 只关心"把 JobDatasource 放进响应"的方法：返回语句里有 success(...) 且实参含数据源
            rets = re.findall(r"return\s+success\s*\(([^;]*)\)\s*;", body, re.S)
            if not rets:
                continue
            found += 1
            rel = path.name
            for expr in rets:
                if "hideSecret" in expr:
                    continue
                # 返回的是变量：必须在该变量上做过 forEach(hideSecret)
                var = expr.strip()
                if re.match(r"^\w+$", var):
                    if re.search(r"\.\s*forEach\s*\([^)]*hideSecret", body):
                        continue
                    fail("%s#%s 返回变量 %s，但方法体里没有对它做过 hideSecret"
                         % (rel, name, var))
                    continue
                fail("%s#%s 的返回实参没有过 hideSecret：%s" % (rel, name, var[:60].replace("\n", " ")))
    if found >= 3:
        ok("数据源读出口 %d 个，全部把剥口令放在返回路径上" % found)
    else:
        fail("只识别到 %d 个返回 JobDatasource 的 GET 出口，预期至少 3 个（分页 /all /{id}）"
             "—— 说明读接口被改名或本门禁的识别规则失效，不得当作通过" % found)
    return found


def check_single_scrub_site():
    sites = {}
    for path, text in java_files(ADMIN_SRC):
        if "setJdbcPassword(null)" in text:
            sites[str(path.relative_to(ADMIN_SRC)).replace("\\", "/")] = text.count("setJdbcPassword(null)")
    extra = sorted(set(sites) - ALLOWED_SCRUB_SITES)
    if extra:
        fail("剥离点必须唯一，口令置空出现在这些文件里：%s（挪进 service 会让 update() "
             "拿不到库里旧值，从而把连接口令静默清空）" % ", ".join(extra))
    else:
        ok("剥离点唯一：jdbc_password 置空只出现在 %s（共 %d 处）"
           % (", ".join(sorted(ALLOWED_SCRUB_SITES)), sum(sites.get(s, 0) for s in ALLOWED_SCRUB_SITES)))

    # 上面那条数出来的"处"里包含 update() 自己的两处置空，所以"计数还在"不能证明
    # 剥离动作在岗：hideSecret 被改成 `ds.setJdbcPassword(ds.getJdbcPassword())` 时，
    # 读接口名为剥离、实为原样返回，而字符串看起来仍在"设置口令"。
    bodies = {name: body for name, _g, body in method_bodies(strip_comments(read(DATASOURCE_CONTROLLER)))}
    scrub_body = bodies.get("hideSecret", "")
    if not scrub_body:
        fail("JobDatasourceController 里找不到 hideSecret 方法体，读出口的剥离动作无从校验")
    elif "setJdbcPassword(PASSWORD_MASK)" not in scrub_body:
        fail("hideSecret 没有把口令换成常量掩码（找不到 setJdbcPassword(PASSWORD_MASK)）")
    elif "getJdbcPassword()" in scrub_body:
        fail("hideSecret 里出现了 getJdbcPassword()：把原值读出来再写回去等于没剥")
    else:
        ok("hideSecret 回的是常量掩码，且没有原值穿透")

    mask = re.search(r'PASSWORD_MASK\s*=\s*"([^"]*)"', strip_comments(read(DATASOURCE_CONTROLLER)))
    if not mask:
        fail("找不到 PASSWORD_MASK 常量定义（掩码必须是编译期常量，不能由入参拼出来）")
    elif len(mask.group(1)) < 4 or set(mask.group(1)) != {"*"}:
        fail("PASSWORD_MASK 不是纯星号掩码或太短：%r —— 掩码若带真实字符就等于泄漏长度甚至内容" % mask.group(1))
    else:
        ok("PASSWORD_MASK 是纯星号的编译期常量（%d 位，与真实口令长度无关）" % len(mask.group(1)))
    return sites


def check_service_keeps_stored_value():
    if not DATASOURCE_SERVICE_IMPL.exists():
        fail("找不到 %s，无法确认 service 仍带回口令" % DATASOURCE_SERVICE_IMPL.name)
        return
    text = strip_comments(read(DATASOURCE_SERVICE_IMPL))
    if "setJdbcPassword(null)" in text:
        fail("JobDatasourceServiceImpl 里出现了置空口令：读出口一旦不带回旧值，"
             "update() 的比较就永远不成立，会把库里口令清掉")
        return
    if "PASSWORD_MASK" in text or "******" in text:
        fail("掩码只属于响应层：service 里出现掩码会让持久化路径把占位值当真值写库")
        return
    ok("service 的 getById 仍带回库里的口令，且不知道掩码的存在（脱敏只发生在响应层）")


def check_blank_resubmit_is_not_a_clear():
    """逃生口：前端回提掩码或空值时，都必须解释成"不修改"而不是"用这个值覆盖"。"""
    if not DATASOURCE_CONTROLLER.exists():
        fail("找不到 JobDatasourceController.java")
        return
    text = strip_comments(read(DATASOURCE_CONTROLLER))
    bodies = {name: body for name, _g, body in method_bodies(text)}
    body = bodies.get("update", "")
    if not body:
        fail("JobDatasourceController 里找不到 update() 方法体，无法确认回提值的处置")
        return
    if not re.search(r"isBlank\s*\(\s*entity\.getJdbcPassword\(\)\s*\)", body):
        fail("update() 没判空口令：脚本调用/部分字段更新会把库里的连接口令清空")
    else:
        ok("update() 把空口令判成『本次不修改』")
    if not re.search(r"PASSWORD_MASK\.equals\s*\(\s*entity\.getJdbcPassword\(\)\s*\)", body):
        fail("update() 没判掩码：前端把读到的掩码原样回提时，6 个星号会被当成新口令写进库，"
             "下一次作业连库直接认证失败（这条是'脱敏改完就把功能修坏'的典型形态）")
    else:
        ok("update() 认得自己回给前端的掩码，回提时不会覆盖真实口令")
    if re.search(r"getJdbcPassword\(\)\.equals\s*\(", body):
        ok("仍旧认『回提值 == 库里值』这条历史写法（旧前端会原样回提密文）")


def main():
    if not DATASOURCE_CONTROLLER.exists():
        print("FAIL 找不到数据源控制器：%s" % DATASOURCE_CONTROLLER)
        return 1
    check_get_exports_scrub()
    check_single_scrub_site()
    check_service_keeps_stored_value()
    check_blank_resubmit_is_not_a_clear()
    if failed:
        print("FAIL: 数据源口令门禁未通过（%d 条）" % len(failed))
        return 1
    print("PASS: 数据源口令只进不出（读出口全剥 + 剥离点唯一 + 回提空值不清库）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
