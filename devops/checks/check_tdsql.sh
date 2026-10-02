#!/usr/bin/env bash
# GATE: slow
# TDSQL 规则门禁：分布式 DDL 改写规则必须能被任何人在任何机器上复跑。
#
# 为什么单独成一个门禁：根 pom 里 `maven.test.skip=true`，默认构建**根本不编译也不跑测试**，
# 新写的规则单测在不加显式开关时是"看起来绿、其实没跑"。所以门禁必须自带那条开关，
# 并把"测试没被执行"本身判为失败。判定逻辑见 lib_mvn_test_gate.sh。
#
# 用法（容器里，仓库挂在 /work）：
#   docker run --rm -v <repo>:/work -v datax-m2:/root/.m2 -w /work maven:3.8-openjdk-8 \
#     bash /work/devops/checks/check_tdsql.sh
set -u

cd "$(dirname "$0")/../.." || exit 1
. devops/checks/lib_mvn_test_gate.sh

run_test_gate "tdsql-ddl" datax-admin "TdsqlDdlRewriterTest" || exit 1
echo "PASS: TDSQL 分布式 DDL 改写规则全部通过"
