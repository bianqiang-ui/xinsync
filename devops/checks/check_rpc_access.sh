#!/usr/bin/env bash
# GATE: slow
# 批次 10-B 门禁：RPC 服务端（执行器 9999 端口）的令牌判定只有一个实现处，
# 且两条入口都过它 —— 服务调用（invokeService）与 /services 服务清单查询。
#
# 为什么两条入口都要管、且形状与行为都要钉：
#   1) /services 历史上挂在令牌校验之外：匿名 GET 就把"这台执行器暴露哪些 RPC 接口、
#      由哪个 Bean 实现"整张表读走。它不在 Spring 过滤器链上，admin 那 33 条越权接缝
#      一条都管不到它 —— 只能在这里单独钉。
#   2) 判定如果两处各写一份，就会漂移（本项目已有前例：入库前的脱敏自己重抄正则，
#      漏掉转义形态与 URL 里的 username）。所以形状侧查"只有一份、两处都委托它"。
#   3) 行为侧（ServiceListingAccessTest）用 EmbeddedChannel 喂真请求、读真响应，
#      断言"状态码 + 响应体里到底有没有服务名"；反向也要钉：带对令牌、以及旧部署
#      显式打开 allowEmptyAccessToken 时清单照旧可得 —— 守卫不许把排障通道关掉。
set -u

cd "$(dirname "$0")/../.." || exit 1
. devops/checks/lib_mvn_test_gate.sh

DECISION=datax-rpc/src/main/java/com/wugui/datax/rpc/util/RpcAccessDecision.java
FACTORY=datax-rpc/src/main/java/com/wugui/datax/rpc/remoting/provider/XxlRpcProviderFactory.java
HTTP_HANDLER=datax-rpc/src/main/java/com/wugui/datax/rpc/remoting/net/impl/netty_http/server/NettyHttpServerHandler.java
NETTY_HANDLER=datax-rpc/src/main/java/com/wugui/datax/rpc/remoting/net/impl/netty/server/NettyServerHandler.java

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

for f in "$DECISION" "$FACTORY" "$HTTP_HANDLER" "$NETTY_HANDLER"; do
  [ -f "$f" ] || { echo "FAIL 缺少文件：$f"; exit 1; }
done

echo
echo "== 唯一实现处：判定只有一份，且在依赖最底层的 datax-rpc =="
# 与 check_log_secret_mask.sh 同一条教训：grep 命中 import 行会假绿，所以判"类声明"形状
# 并逐文件核对份数，而不是数命中行数。
copies=$(grep -rl 'class RpcAccessDecision\b' --include=*.java \
        datax-admin datax-core datax-executor datax-rpc 2>/dev/null | grep -v '/src/test/')
if [ -z "$copies" ]; then
  echo "FAIL 一个 RpcAccessDecision 实现都没有 —— RPC 服务端令牌判定消失了"
  fail=1
elif [ "$(printf '%s\n' "$copies" | wc -l)" -ne 1 ]; then
  echo "FAIL RpcAccessDecision 有多份实现（$(printf '%s ' $copies)）—— 各模块不许各抄一套判定"
  fail=1
elif ! printf '%s\n' "$copies" | grep -q '^datax-rpc/src/main/java/'; then
  echo "FAIL 唯一那份 RpcAccessDecision 不在 datax-rpc 的 main 源码里（$copies）"
  fail=1
else
  echo "OK   全仓库只有这一份 RpcAccessDecision 实现，且在 datax-rpc"
fi
need "$DECISION" 'DENY_TOKEN_NOT_CONFIGURED[[:space:]]*=[[:space:]]*"The access token is not configured on provider side\."' "未配令牌的拒绝措辞沿用历史值（改文案会让既有排障脚本认不出来）"
need "$DECISION" 'DENY_TOKEN_WRONG[[:space:]]*=[[:space:]]*"The access token is wrong\."' "令牌不符的拒绝措辞沿用历史值"
need "$DECISION" 'SERVICE_LISTING_HEADER[[:space:]]*=[[:space:]]*"X-Xxl-Rpc-Access-Token"' "服务清单的令牌载体钉成请求头：进 URL 就会落进代理与访问日志"

echo
echo "== 入口一：服务调用必须委托那份判定，不再内联比对 =="
need "$FACTORY" 'RpcAccessDecision\.denyReason\(' "invokeService 走唯一实现处"
# 逐行比对（跳过注释行）：历史上这里就是自己写 accessToken 的 null/trim/equals 三段式，
# 留一份在工厂里 = 将来改判定只改一处、另一处静静漂移。
inline=$(grep -Ec '^[^/]*accessToken\.trim\(\)\.equals\(' "$FACTORY")
if [ "$inline" -ne 0 ]; then
  echo "FAIL $FACTORY 里还有 $inline 处内联的 accessToken.trim().equals(...) —— 判定必须收归 RpcAccessDecision"
  fail=1
else
  echo "OK   工厂里没有残留的内联令牌比对"
fi
forbid "$DECISION" 'System\.getProperties|Thread|ServerSocket' "判定必须是纯函数：不读环境、不起线程，否则单测跑不到它"
need "$FACTORY" 'allowEmptyAccessToken' "兼容旧部署的逃生口还在"
need "$FACTORY" 'datax\.rpc\.allowEmptyAccessToken", "false"' "逃生口默认关闭（配置键名与默认值一起钉，改名会让旧部署的 true 失效变成全拒）"

echo
echo "== 入口二：/services 清单必须先判定、后拼清单 =="
need "$FACTORY" 'public String serviceListingDenyReason' "清单入口有一条对外的判定方法（读的是私有字段，判定不外泄）"
need "$HTTP_HANDLER" 'serviceListingDenyReason\(' "netty_http 处理器调用同一条判定"
need "$HTTP_HANDLER" 'HttpResponseStatus\.FORBIDDEN' "拒绝时回 403，不再是 200 带内容"
need "$HTTP_HANDLER" 'RpcAccessDecision\.SERVICE_LISTING_HEADER' "处理器从请求头取清单令牌，而不是从 uri 里拆"
forbid "$HTTP_HANDLER" 'uri\.(contains|indexOf)\("accessToken|uri\.split\(' \
  "不许从 URL 里拆令牌（那是把凭据请进访问日志）"

# 顺序判据：判定必须早于 getServiceData() 的第一次读取。
# 只 grep "两者都存在"是假绿通道 —— 把判定挪到拼完清单之后再 return，泄漏照旧发生，
# 只是多算了一次 map 遍历，两条 grep 都还是绿的。
deny_line=$(grep -n 'serviceListingDenyReason(' "$HTTP_HANDLER" | head -1 | cut -d: -f1)
list_line=$(grep -n 'getServiceData()\.keySet()' "$HTTP_HANDLER" | head -1 | cut -d: -f1)
if [ -z "$deny_line" ] || [ -z "$list_line" ]; then
  echo "FAIL 找不到判定行或服务清单拼装行（deny=${deny_line:-none} list=${list_line:-none}）—— 本条规则没实际生效"
  fail=1
elif [ "$deny_line" -ge "$list_line" ]; then
  echo "FAIL $HTTP_HANDLER:$deny_line 的判定不在 $HTTP_HANDLER:$list_line 拼清单之前 —— 未授权时服务名已经拼出来了"
  fail=1
else
  echo "OK   判定（第 $deny_line 行）早于清单拼装（第 $list_line 行）：未授权时一个服务名都不出去"
fi

echo
echo "== 另一条 netty（非 http）入口不得被绕过 =="
# 只有 netty_http 有 /services；纯 netty 通道走 deserialize + invokeService，
# 判定天然在里面。这里钉的是"别在 NettyServerHandler 里再开一条不判定的快捷分支"。
need "$NETTY_HANDLER" 'invokeService\(' "纯 netty 通道仍然经由同一个服务调用入口"
forbid "$NETTY_HANDLER" 'getServiceData\(\)' "纯 netty 通道不许自己读服务表回内容（那等于再造一个无判定的 /services）"

echo
echo "== 行为侧回归用例 =="
run_test_gate "rpc-access" datax-core "RpcAccessDecisionTest,ServiceListingAccessTest" || exit 1

if [ "$fail" -ne 0 ]; then
  echo "FAIL: RPC 服务端令牌接缝的形状检查有未通过项（见上面 FAIL 行）"
  exit 1
fi
echo "PASS: RPC 服务端「判定唯一实现处 + 服务调用与 /services 两条入口都过判定 + 先判定后拼清单 + 403 不外泄服务名」全部通过"
