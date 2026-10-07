#!/usr/bin/env bash
# GATE: slow
# 批次 16 门禁（T2-B）：SHARDING_BROADCAST 的"每片一份 jobJson"切片链路。
#
# 红线（tmp/draft/b16-t2b-construction-order.md）形状侧钉四条：
#   1) 切片器只有一份实现，且接线只在 JobTrigger 一处（不许第二处各切各的）；
#   2) 全仓 main 源码里不许出现目标侧路由特征（拿 shardkey 去算物理分片的
#      `hash(...)%`/取模到物理库的写法）——路由是 TDSQL 内核的事，自研 = 错误数据发生器；
#   3) querySql 自由文本必须显式拒绝，不许静默改写用户 SQL；
#   4) 取不到规则时必须保持现状（N 份全量）——JobTrigger 里必须有"规则为 null 就原样下发"
#      的回退分支，不许把"没配规则"升级成任务失败。
# 行为侧：TdsqlShardSlicerTest 必须在 check_admin_tests.sh 的名单里（发现层对账）。
set -u

cd "$(dirname "$0")/../.." || exit 1

SLICER=datax-admin/src/main/java/com/wugui/datax/admin/tool/tdsql/TdsqlShardSlicer.java
DISPATCH=datax-admin/src/main/java/com/wugui/datax/admin/tool/tdsql/TdsqlShardDispatch.java
TRIGGER=datax-admin/src/main/java/com/wugui/datax/admin/core/trigger/JobTrigger.java
TEST=datax-admin/src/test/java/com/wugui/datax/admin/tool/tdsql/TdsqlShardSlicerTest.java

fail=0

need() {
  if grep -Eq "$2" "$1"; then
    echo "OK   $3"
  else
    echo "FAIL $1 里找不到「$2」—— $3"
    fail=1
  fi
}

forbid() {
  if grep -Eq "$2" "$1"; then
    echo "FAIL $1 里出现了「$2」—— $3"
    fail=1
  else
    echo "OK   $3"
  fi
}

for f in "$SLICER" "$DISPATCH" "$TRIGGER" "$TEST"; do
  [ -f "$f" ] || { echo "FAIL 缺少文件：$f"; exit 1; }
done

echo
echo "== 形状1：切片器唯一实现，接线只有 JobTrigger 一处 =="
copies=$(grep -rl 'class TdsqlShardSlicer\b' --include=*.java \
        datax-admin datax-core datax-executor datax-rpc 2>/dev/null | grep -v '/src/test/')
if [ -z "$copies" ]; then
  echo "FAIL 一个 TdsqlShardSlicer 实现都没有 —— 切片链路消失了"
  fail=1
elif [ "$(printf '%s\n' "$copies" | wc -l)" -ne 1 ]; then
  echo "FAIL TdsqlShardSlicer 有多份实现（$(printf '%s ' $copies)）—— 不许各抄一套切片"
  fail=1
else
  echo "OK   全仓库只有这一份 TdsqlShardSlicer 实现"
fi
callers=$(grep -rl 'TdsqlShardSlicer\.slice' --include=*.java \
          datax-admin/src/main 2>/dev/null)
if [ "$(printf '%s\n' "$callers" | grep -c .)" -ne 1 ] || ! printf '%s\n' "$callers" | grep -q '^datax-admin/src/main/java/com/wugui/datax/admin/core/trigger/JobTrigger.java$'; then
  echo "FAIL TdsqlShardSlicer.slice 的 main 调用点必须是且仅是 JobTrigger（当前：$(printf '%s ' $callers)）"
  fail=1
else
  echo "OK   切片入口只有 JobTrigger 一处"
fi

echo
echo "== 形状2：不许出现目标侧路由（路由是 TDSQL 内核的事） =="
# 特征三组（2026-10-07 反证腿 B 实测过第一版的盲区：`evilTargetRouting(k, n)` 这种
# 任意命名的 hashCode 取模函数完全绕开按字段名匹配的正则）：
#   a) "散列取模当下标"：hashCode() 后面接取模（.hashCode() 相对专用于路由，无误报面）；
#   b) 函数名/变量名带 routing|routeTo|shardOf|dbIndex|physical 且同行有 %；
#   c) 调用点把 shardKey/shard_key 传进任何取模函数（参数位置出现 shardKey）。
target_side=$(grep -rn --include=*.java -E '\.hashCode\(\)[^;]*%|^[^/]*(routing|routeTo|shardOf|dbIndex|physicalShard)\w*\s*\([^)]*\)\s*\{[^}]*%|%\s*[a-z_]*\(\s*(shardKey|shard_key)' \
        datax-admin/src/main datax-core/src/main datax-executor/src/main 2>/dev/null)
if [ -n "$target_side" ]; then
  echo "FAIL 出现疑似目标侧路由的代码："
  printf '%s\n' "$target_side"
  fail=1
else
  echo "OK   main 源码无目标侧路由特征（分片路由交给 TDSQL 内核）"
fi
# 辅助判据（形状）：切片器只允许出现一种取模形状 —— "(切片列 % N) = index" 的等式；
# Slicer 里出现 "hashCode" 直接红（它不该有散列语义）。
if grep -q 'hashCode' "$SLICER" 2>/dev/null; then
  echo "FAIL TdsqlShardSlicer 里出现 hashCode —— 切片器是纯字符串/JSON 改写器，不该有散列语义"
  fail=1
else
  echo "OK   切片器无散列语义（只有来源侧取模等式）"
fi

echo
echo "== 形状3：querySql 显式拒绝 =="
need "$SLICER" 'containsKey\("querySql"\)' "切片器检查 querySql 形状"
need "$SLICER" '拒绝切片' "拒绝措辞带原因（不许静默改写用户 SQL）"

echo
echo "== 形状4：无规则时保持现状（不许把没配规则升级成失败） =="
need "$TRIGGER" 'processTrigger\(group, jobInfo, finalFailRetryCount, triggerType, shardingParam\[0\], shardingParam\[1\], null\)' "非广播/手工分片路径显式传 null（原样下发）"
need "$DISPATCH" 'return null' "接线层所有不可切片情形返回 null 交回现状行为"

echo
echo "== 行为侧：切片单测必须登记进 admin 回归名单 =="
if grep -q 'TdsqlShardSlicerTest' devops/checks/check_admin_tests.sh; then
  echo "OK   TdsqlShardSlicerTest 已在 check_admin_tests.sh 名单"
else
  echo "FAIL TdsqlShardSlicerTest 没登记进 check_admin_tests.sh —— 新测试不许留在名单外"
  fail=1
fi
# Slicer/Dispatch 的测试存在且非空（防"登记了名单但文件是空壳"）
for t in "$TEST" datax-admin/src/test/java/com/wugui/datax/admin/tool/tdsql/TdsqlShardDispatchTest.java; do
  if [ -f "$t" ] && [ "$(grep -c '@Test' "$t")" -ge 3 ]; then
    echo "OK   $(basename "$t") 存在且有用例"
  else
    echo "FAIL $t 缺失或用例不足 3 条"
    fail=1
  fi
done

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS: 分片切片链路形状与行为判据全部成立（Slicer 唯一实现 + JobTrigger 唯一接线 + 无目标侧路由 + querySql 拒绝 + 无规则回退现状）"
fi
exit "$fail"
