#!/usr/bin/env bash
# TDSQL 规则门禁：分布式 DDL 改写规则必须能被任何人在任何机器上复跑。
#
# 为什么单独成一个门禁：根 pom 里 `maven.test.skip=true`，默认构建**根本不编译也不跑测试**，
# 新写的规则单测在不加显式开关时是"看起来绿、其实没跑"。所以门禁必须自带那条开关，
# 并把"测试没被执行"本身判为失败。
#
# 用法（容器里，仓库挂在 /work）：
#   docker run --rm -v <repo>:/work -v datax-m2:/root/.m2 -w /work maven:3.8-openjdk-8 \
#     bash /work/devops/checks/check_tdsql.sh
set -u

cd "$(dirname "$0")/../.." || exit 1
LOG=$(mktemp)

mvn -B -pl datax-admin -am install \
    -Dmaven.test.skip=false \
    -Dtest=TdsqlDdlRewriterTest \
    -DfailIfNoTests=false > "$LOG" 2>&1
rc=$?

echo "mvn exit = $rc"

if grep -q "Not compiling test sources" "$LOG"; then
  echo "FAIL: 测试源码被跳过，本次没有验证任何规则（检查 maven.test.skip 是否真的关掉了）"
  rm -f "$LOG"; exit 1
fi

summary=$(grep -E "^Tests run:.*Failures:.*Errors:" "$LOG" | tail -1)
if [ -z "$summary" ]; then
  echo "FAIL: 没有测试执行汇总，说明 TdsqlDdlRewriterTest 根本没跑起来"
  tail -20 "$LOG"; rm -f "$LOG"; exit 1
fi
echo "$summary"

if [ "$rc" -ne 0 ] || ! echo "$summary" | grep -q "Failures: 0, Errors: 0"; then
  echo "FAIL: DDL 改写规则回归未通过"
  grep -E "Tests run|FAIL|ERROR" "$LOG" | head -20
  rm -f "$LOG"; exit 1
fi

echo "PASS: TDSQL 分布式 DDL 改写规则全部通过"
rm -f "$LOG"
