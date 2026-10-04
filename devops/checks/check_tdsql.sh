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

GEN=datax-admin/src/main/java/com/wugui/datax/admin/tool/tdsql/TdsqlDdlGenerator.java
RULE_SQL=bin/db/datax_web.sql
RULE_SVC=datax-admin/src/main/java/com/wugui/datax/admin/service/impl/TdsqlShardRuleServiceImpl.java

# ---- 先做形状检查：这类问题不该花掉一次 mvn 才暴露 ----
# 为什么形状也要进门禁：改写器的规则正确性由单测守着，但"生成器绕过改写器自己拼子句"
# 这种改动单测照样绿（它拼出来的字符串可以刚好等于期望值），只有从源码上禁止第二条产出路径才守得住。
shape_failed=0
shape_checks=0
need() {   # need <文件> <正则> <说明>
  shape_checks=$((shape_checks + 1))
  if ! grep -Eq -- "$2" "$1" 2>/dev/null; then
    echo "FAIL[形状]: $1 里找不到「$3」（$2）"
    shape_failed=1
  fi
}
ban() {    # ban <文件> <正则> <说明>
  shape_checks=$((shape_checks + 1))
  if grep -Eq -- "$2" "$1" 2>/dev/null; then
    echo "FAIL[形状]: $1 里出现了「$3」（$2）"
    shape_failed=1
  fi
}

for f in "$GEN" "$RULE_SVC" "$RULE_SQL"; do
  if [ ! -f "$f" ]; then
    echo "FAIL[形状]: 缺少文件 $f —— 先把它补回来，形状判据不去猜"
    exit 1
  fi
done

# 1) 生成器只许有一条产出路径：调改写器，且源码里不出现分布式子句的字面量
need "$GEN" 'TdsqlDdlRewriter\.rewrite\(' '生成器必须经改写器产出 DDL'
ban  "$GEN" 'SHARDKEY' '分布式子句字面量只能出现在改写器里'
# 2) 生成器必须核对表名，否则 A 表的规则能配到 B 表的 DDL 上
need "$GEN" 'TdsqlDdlRewriter\.tableNameOf\(' '生成器必须核对规则与 DDL 的表名'
# 引号收尾是必须的：只写"表名对不上"时注释里提一次就算过，把真正的报错文案改成"不太对劲"照样绿
# —— 反证 P2 第一遍就是这么不成立的。
need "$GEN" '表名对不上，拒绝生成"' '表名不符时必须明确报错（文案要在字符串里，不是注释里）'
# 3) 停用规则、空规则不得产出
need "$GEN" 'isEnabledRule\(\)' '生成器必须拒绝停用规则'
# 4) 服务出口只许走 singleEnabled，不许自己取第一条
need "$RULE_SVC" 'TdsqlShardRules\.singleEnabled\(' '服务必须走统一的选择口径'
ban  "$RULE_SVC" '\.get\(0\)' '服务不得自行取第一条'
# 5) 规则表存的是用户配置：反复执行建表脚本不许清空它
need    "$RULE_SQL" 'CREATE TABLE IF NOT EXISTS `tdsql_shard_rule`' '规则表必须可重复执行'
ban     "$RULE_SQL" 'DROP TABLE IF EXISTS `tdsql_shard_rule`' '规则表不得带删表语句'
for col in logic_db logic_table table_type shard_key shard_num enabled; do
  need "$RULE_SQL" "\`$col\`" "规则表必须有 $col 列"
done

# 逐条判据只置位，最后统一裁决。这一行是本批反证抓出来的洞：第一版把 shape_failed 的落地写在了
# need/ban 之前（只兜"文件缺失"），于是七条形状判据全都"打印 FAIL 但照样绿"——
# 反证 P1–P7 七条腿一次都没让门禁变红。判据没接到退出码，等于没写。
if [ "$shape_checks" -lt 15 ]; then
  echo "FAIL[形状]: 只执行了 ${shape_checks} 条形状判据（应不少于 15 条）—— 判据被删空/被跳过就是自空洞"
  exit 1
fi
if [ "$shape_failed" -ne 0 ]; then
  echo "FAIL[形状]: 见上面逐条原因，本次不进入 mvn"
  exit 1
fi
echo "PASS[形状]: ${shape_checks} 条形状判据全部成立（生成器只有一条产出路径，规则表可重复执行且不丢配置）"

run_test_gate "tdsql-ddl" datax-admin "TdsqlDdlRewriterTest,TdsqlDdlGeneratorTest,TdsqlShardRulesTest" || exit 1
echo "PASS: TDSQL 分布式 DDL 改写规则全部通过"
