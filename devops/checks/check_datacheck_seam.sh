#!/usr/bin/env bash
# GATE: slow
# 批次 17 门禁：一致性比对的**开源接缝**与**闭源隔离**。
#
# 背景：商业包（一致性比对引擎）是闭源交付件，物理上放在公共仓库之外。
# 公开仓库里只留"接缝"（DataCheckSupport：配对校验/共有表/逐表行数/checksum 模板），
# 闭源引擎通过 spring.factories 落到 classpath 即生效。本门禁钉两组事：
#   1) 接缝形状 —— 接缝存在、不自建 JDBC 连接（凭据解密与连接生命周期只跟随平台
#      BaseQueryTool 一条口径）、checksum 模板的 BIT_XOR 形状在、先校验后连接的顺序在；
#   2) 隔离红线 —— 公共仓库里不许出现闭源坐标（com.xinsync / xinsync-pro）：
#      依赖泄漏 = 商业资产泄漏，等价于把该收钱的代码推上了 github/gitee。
set -u

cd "$(dirname "$0")/../.." || exit 1

SEAM=datax-admin/src/main/java/com/wugui/datax/admin/tool/datacheck/DataCheckSupport.java
QT=datax-admin/src/main/java/com/wugui/datax/admin/tool/query/BaseQueryTool.java

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

[ -f "$SEAM" ] || { echo "FAIL 缺少接缝文件：$SEAM"; exit 1; }

echo
echo "== 接缝形状（闭源引擎赖以工作的最小原语） =="
need "$SEAM" 'checkPair' "配对校验原语在"
need "$SEAM" 'commonTables' "共有表清单原语在"
need "$SEAM" 'countCompare' "逐表行数比对原语在"
need "$SEAM" 'checksumSql' "checksum 模板原语在"
need "$SEAM" 'new StringBuilder\("SELECT BIT_XOR\(CRC32\(CONCAT_WS' "checksum 模板形状（代码侧锚定，注释里出现同款文本不算 —— 反证腿A实测抓过这个假绿）"
need "$QT" 'public long countTable' "countTable 原语是 public（闭源侧复用平台连接）"
need "$QT" 'public List<String> getPrimaryKeys' "主键元数据是 public（闭源侧不自带 JDBC）"

echo
echo "== 接缝纪律（错误输入不消耗连接；连接只走平台口径） =="
# countCompare 里"先 SqlSafeIdentifier.check 全部表名，再 QueryToolFactory 建连接"：
# 顺序钉法 = 在 countCompare 方法体内（签名行之后）check 的首次出现早于 getByDbType 的首次出现。
sig=$(grep -n 'countCompare' "$SEAM" | head -1 | cut -d: -f1)
line_check=$(tail -n "+$sig" "$SEAM" | grep -n 'SqlSafeIdentifier.check' | head -1 | cut -d: -f1)
line_conn=$(tail -n "+$sig" "$SEAM" | grep -n 'QueryToolFactory.getByDbType' | head -1 | cut -d: -f1)
if [ -n "$line_check" ] && [ -n "$line_conn" ] && [ "$line_check" -lt "$line_conn" ]; then
  echo "OK   标识符校验在建立连接之前（方法内相对行 $line_check < $line_conn）"
else
  echo "FAIL 接缝里的校验/连接顺序反了（countCompare 签名行=$sig, check 相对行=$line_check, 连接相对行=$line_conn）"
  fail=1
fi
forbid "$SEAM" 'DriverManager|new HikariDataSource' "接缝不自建 JDBC 连接（凭据解密只有平台一条口径）"

echo
echo "== 隔离红线（闭源坐标不许进公共仓库） =="
leak=$(grep -rn --include=*.java --include=*.xml --include=*.md -E 'com\.xinsync|xinsync-pro' \
       datax-admin datax-core datax-executor datax-rpc pom.xml 2>/dev/null | grep -v '/target/')
if [ -n "$leak" ]; then
  echo "FAIL 公共仓库出现闭源坐标（把该收钱的代码推上了公网）："
  printf '%s\n' "$leak"
  fail=1
else
  echo "OK   全仓库无 com.xinsync / xinsync-pro 引用（闭源件在仓库外物理隔离）"
fi
# 接缝文件本身也要查（防止有人图省事把引擎类直接拷进来）
forbid "$SEAM" 'com\.xinsync' "接缝文件无闭源 import"

echo
echo "== 行为侧：接缝单测必须登记进 admin 回归名单 =="
for t in BaseQueryToolCountTest DataCheckSupportTest; do
  if grep -q "$t" devops/checks/check_admin_tests.sh; then
    echo "OK   $t 已在名单"
  else
    echo "FAIL $t 未登记进 check_admin_tests.sh"
    fail=1
  fi
done

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS: 比对接缝形状与闭源隔离判据全部成立（接缝最小原语 + 平台唯一连接口径 + 先校验后连接 + 仓库无闭源坐标）"
fi
exit "$fail"
