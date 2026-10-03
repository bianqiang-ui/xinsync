#!/usr/bin/env bash
# GATE: slow
# 第 14 道门禁：部署包里的依赖版本（批次 10-K 的根因）。
#
# 判的内容全在 devops/checks/lib_package_deps.py 里（读 packages/*.tar.gz 里的 jar 文件名，
# 并与根 pom 的 <properties> pin 与 <dependencyManagement> 条目对账）。本脚本只负责一件事：
# **让"没有产物"不变成一个绿灯**。
#
# 为什么这一层必须存在：本仓库不打 fat jar、也没有 Dockerfile，交付物就是 packages/ 下那两个
# tar.gz；而 packages/ 在 .gitignore 里（clone 后天然为空）。如果门禁只在"包存在时"才判，
# 那"先 clean 再复跑 recheck"就会得到一串"没有东西可判所以没问题"—— 这正是本项目反复抓的
# "采集量=0 必须自 FAIL"。所以缺包时的默认行为是**现场构建**（慢门禁本来就在 JDK8 容器里跑，
# mvn 与 datax-m2 卷都是现成的），构建失败或建完仍无包一律判红。
# 反证沙箱里没有整个 maven 工程，用 PACKAGE_DEPS_AUTOBUILD=0 关掉这条自动构建，
# 让"包不在"直接落到 python 侧那条 FAIL 上（反证 C 验的就是这个分支，不是"跳过"）。
set -u

cd "$(dirname "$0")/../.." || exit 1

BUILD_CAP="mvn -B clean install -DskipTests"   # 全 reactor，不用 -pl：局部构建看不到执行器包

shopt -s nullglob
admin_tars=(packages/datax-admin_*.tar.gz)
exec_tars=(packages/datax-executor_*.tar.gz)
shopt -u nullglob

if [ "${#admin_tars[@]}" -eq 0 ] || [ "${#exec_tars[@]}" -eq 0 ]; then
  if [ "${PACKAGE_DEPS_AUTOBUILD:-1}" = "1" ]; then
    echo "[INFO] packages/ 下部署包不全（admin=${#admin_tars[@]} executor=${#exec_tars[@]}），"
    echo "[INFO] 本门禁判的是产出包，先现场构建：${BUILD_CAP}"
    if ! eval "$BUILD_CAP"; then
      echo "FAIL: 构建未成功，没有产出包可判 —— 依赖门禁拒绝在空产物上报绿"
      exit 1
    fi
  else
    echo "[WARN] PACKAGE_DEPS_AUTOBUILD=0：缺包时不自动构建，直接把「没有产物」交给判定体判红"
  fi
fi

PYBIN=""
for cand in python3 python; do
  if command -v "$cand" >/dev/null 2>&1 \
     && [ "$("$cand" -c 'print("gate-interpreter-ok")' 2>/dev/null)" = "gate-interpreter-ok" ]; then
    PYBIN="$cand"; break
  fi
done
if [ -z "$PYBIN" ]; then
  echo "FAIL: 找不到可用的 python3/python（存在但跑不出输出的占位程序不算），依赖判定未执行"
  exit 1
fi

"$PYBIN" devops/checks/lib_package_deps.py
rc=$?
if [ "$rc" -ne 0 ]; then
  echo "FAIL: 部署包依赖门禁未通过（判定体 rc=${rc}）"
  exit 1
fi
exit 0
