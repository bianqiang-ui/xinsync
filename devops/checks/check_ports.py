#!/usr/bin/env python3
"""校验 datax-web 的端口口径是否自洽：yml / env.properties / 启动脚本兜底值三处必须一致。

脱离启动脚本裸跑（IDE）读 yml，打包部署读 env.properties（脚本再经 -D 覆盖 yml）。
三处任写一个值就会出现"部署起来才知道端口不对"的事故，所以把它钉成可复跑的门禁。
"""
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

ADMIN_ENV = ROOT / "datax-admin/src/main/bin/env.properties"
ADMIN_SH = ROOT / "datax-admin/src/main/bin/datax-admin.sh"
ADMIN_YML = ROOT / "datax-admin/src/main/resources/application.yml"
EXEC_ENV = ROOT / "datax-executor/src/main/bin/env.properties"
EXEC_SH = ROOT / "datax-executor/src/main/bin/datax-executor.sh"
EXEC_YML = ROOT / "datax-executor/src/main/resources/application.yml"

failures = []


def need(text, pattern, source):
    m = re.search(pattern, text, re.MULTILINE)
    if not m:
        failures.append(f"[提取失败] {source}: 找不到 {pattern!r}（门禁不允许静默跳过）")
        return None
    return m.group(1)


def read(path):
    if not path.is_file():
        failures.append(f"[文件缺失] {path}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def env_value(text, key, source):
    m = re.search(rf"^{key}=(.*)$", text, re.MULTILINE)
    if not m:
        failures.append(f"[提取失败] {source}: 没有 {key}= 这一行")
        return None
    return m.group(1).strip()


def sh_fallback(text, key, source):
    m = re.search(rf"if \[\[ ! \${{{key}}} \]\]; then\n(?:.*\n)*?\s*{key}=(\S+)\nfi", text)
    if not m:
        failures.append(f"[提取失败] {source}: 找不到 {key} 的兜底赋值")
        return None
    return m.group(1).strip()


def yml_top(text, path_regex, source):
    return need(text, path_regex, source)


def check_equal(label, values):
    vals = {k: v for k, v in values.items() if v is not None}
    uniq = set(vals.values())
    if len(uniq) > 1:
        detail = ", ".join(f"{k}={v}" for k, v in vals.items())
        failures.append(f"[端口不一致] {label}: {detail}")
    elif vals:
        print(f"OK   {label} = {uniq.pop()}  ({', '.join(vals)})")


admin_env = read(ADMIN_ENV)
admin_sh = read(ADMIN_SH)
admin_yml = read(ADMIN_YML)
exec_env = read(EXEC_ENV)
exec_sh = read(EXEC_SH)
exec_yml = read(EXEC_YML)

admin_yml_port = yml_top(admin_yml, r"^server:\n(?:[ \t]*#.*\n)*[ \t]+port:[ \t]+(\d+)", str(ADMIN_YML.relative_to(ROOT)))
check_equal("admin 服务端口", {
    "conf/application.yml": admin_yml_port,
    "bin/env.properties": env_value(admin_env, "SERVER_PORT", str(ADMIN_ENV.relative_to(ROOT))),
    "bin/datax-admin.sh": sh_fallback(admin_sh, "SERVER_PORT", str(ADMIN_SH.relative_to(ROOT))),
})

exec_yml_port = yml_top(exec_yml, r"^server:\n(?:[ \t]*#.*\n)*[ \t]+port:[ \t]+(\d+)", str(EXEC_YML.relative_to(ROOT)))
exec_yml_admin = yml_top(exec_yml, r"addresses:[ \t]*http://[^/\s]+:\$\{datax\.admin.port:(\d+)\}", str(EXEC_YML.relative_to(ROOT)))
exec_yml_rpc = yml_top(exec_yml, r"port:[ \t]*\$\{executor\.port:(\d+)\}", str(EXEC_YML.relative_to(ROOT)))

check_equal("executor web 端口", {
    "conf/application.yml": exec_yml_port,
    "bin/env.properties": env_value(exec_env, "SERVER_PORT", str(EXEC_ENV.relative_to(ROOT))),
    "bin/datax-executor.sh": sh_fallback(exec_sh, "SERVER_PORT", str(EXEC_SH.relative_to(ROOT))),
})
check_equal("executor RPC 端口", {
    "conf/application.yml": exec_yml_rpc,
    "bin/env.properties": env_value(exec_env, "EXECUTOR_PORT", str(EXEC_ENV.relative_to(ROOT))),
    "bin/datax-executor.sh": sh_fallback(exec_sh, "EXECUTOR_PORT", str(EXEC_SH.relative_to(ROOT))),
})
check_equal("executor 眼里的 admin 端口", {
    "conf/application.yml": exec_yml_admin,
    "bin/datax-executor.sh": sh_fallback(exec_sh, "DATAX_ADMIN_PORT", str(EXEC_SH.relative_to(ROOT))),
})

if admin_yml_port and exec_yml_port and admin_yml_port == exec_yml_port:
    failures.append(f"[同机冲突] admin 与 executor 的 web 端口都是 {admin_yml_port}")

if failures:
    print()
    for f in failures:
        print(f)
    print(f"\nFAIL: {len(failures)} 项端口口径问题")
    sys.exit(1)
print("\nPASS: 端口口径三处一致（yml / env.properties / 脚本兜底）")
