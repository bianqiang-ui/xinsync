#!/usr/bin/env bash
# GATE: slow
# 批次 10-J / B1 门禁：凭据不许进 toString —— 泄漏面收在**值的来源**，不是收在打印处。
#
# 背景：RPC 超时那条链上有三条出口，同源于同一个 request.toString()：
#   1) XxlRpcReferenceBean 的三条 logger.info(..., xxlRpcRequest) —— 明文进 admin 日志文件；
#   2) XxlRpcFutureResponse.get(..) 抛的 XxlRpcException("... request:" + request.toString())；
#   3) 这条异常消息被 JobTrigger 摘出来当 ReturnT.msg，再拼进 triggerMsg 落到 job_log.trigger_msg 列 ——
#      等于把口令持久化进库，日志面收口之后还要再吃一遍。
# 在打印处逐个加脱敏追不上出口逐个增加（下一个加 log 的人不会想到先脱敏），
# 所以 TriggerParam.toString / XxlRpcRequest.toString 本身必须不产出凭据，三条出口一起断掉。
#
# 与第十五轮 B3 同一条口径：**先让产物不含敏感物，再谈权限**。
#
# 顺手把同一族的另外三处一起堵了（都是"对象自己就持有凭据"，出口数量还会往上长）：
#   JwtUser.toString —— AbstractAuthenticationToken.toString() 会把 principal 原样拼进去；
#   LoginUser / JobDatasource —— @Data 生成的 toString 连 password / jdbcPassword 一起打，
#   后者内存里那一份是**解密后的明文**（AESEncryptHandler 只在读写库时加解密）。
# 这三处不给长度指纹：登录口令和数据源口令的"多长"本身就是可枚举信息，
# 和 accessToken（配置项，看长度是为了排查没配对）不是一回事。
#
# 为什么形状检查和用例都要：
#   - 用例（ToStringSecretMaskTest / SensitiveLogMaskTest / JobTriggerSanitizeTest）钉的是行为：
#     明文不许出、长度与定位信息要留下、转义形态也要认、键名清单不许被放宽或收窄。
#   - 形状检查钉的是"收口点还在不在源头"：有人图省事把 toString 改回原样、改用另一份本地正则，
#     行为用例可能照样绿（打印处补了遮罩），但第 N+1 条出口又开了。
#   - 两侧各自可反证，缺一不可。
set -u

cd "$(dirname "$0")/../.." || exit 1
. devops/checks/lib_mvn_test_gate.sh

MASK=datax-rpc/src/main/java/com/wugui/datax/rpc/util/SensitiveLogMask.java
PARAM=datax-core/src/main/java/com/wugui/datatx/core/biz/model/TriggerParam.java
REQ=datax-rpc/src/main/java/com/wugui/datax/rpc/remoting/net/params/XxlRpcRequest.java
TRIG=datax-admin/src/main/java/com/wugui/datax/admin/core/trigger/JobTrigger.java
INVOKER=datax-rpc/src/main/java/com/wugui/datax/rpc/remoting/invoker
JWTUSER=datax-admin/src/main/java/com/wugui/datax/admin/entity/JwtUser.java
LOGIN=datax-admin/src/main/java/com/wugui/datax/admin/entity/LoginUser.java
DS=datax-admin/src/main/java/com/wugui/datax/admin/entity/JobDatasource.java

fail=0

need() {
  # need <文件> <扩展正则> <说明>
  if grep -Eq "$2" "$1"; then
    echo "OK   $3"
  else
    echo "FAIL $1 里找不到「$2」—— $3"
    fail=1
  fi
}

forbid() {
  # forbid <文件|目录> <扩展正则> <说明>
  local target="$1"
  if [ -d "$target" ]; then
    if grep -REq "$2" "$target"; then
      echo "FAIL $target 里出现了「$2」—— $3"
      fail=1
    else
      echo "OK   $3"
    fi
  else
    if grep -Eq "$2" "$target"; then
      echo "FAIL $target 里出现了「$2」—— $3"
      fail=1
    else
      echo "OK   $3"
    fi
  fi
}

for f in "$MASK" "$PARAM" "$REQ" "$TRIG" "$JWTUSER" "$LOGIN" "$DS"; do
  [ -f "$f" ] || { echo "FAIL 缺少文件：$f"; exit 1; }
done

echo
echo "== 唯一实现处：判定只有一份，且在依赖最底层的模块 =="
need "$MASK" 'public static String describeBlob' "大段不透明内容换成掩码+长度的入口"
need "$MASK" 'public static String describeSecret' "单个凭据字段的入口（空值要能原样回显，见用例）"
need "$MASK" 'public static String maskSecretValues' "自由文本里按值遮蔽的入口"
# 逃生口：没配令牌时 describeSecret 必须原样回显空串。掩成 ****** 就把"根本没开鉴权"和
# "开了但令牌不对"两种故障压成同一条日志 —— 同类红线：守卫不许把可诊断性修坏。
need "$MASK" 'return "";' "空令牌原样回显空串，这条逃生口要在"
need "$MASK" 'userPassword' "Mongo 的 userName/userPassword 必须在取值域里，老实现只认 username/password"
need "$MASK" 'accessToken' "RPC 通道令牌走同一条判定"
need "$MASK" 'CASE_INSENSITIVE' "键名大小写不敏感（异常消息里出现过 PASSWORD 这种形态）"
# 逐字钉住清单与**顺序**：正则分支是同位置先到先得，短键 user 排在长键之前会把长键吞掉
need "$MASK" '"password\|passwd\|username\|userName\|userPassword\|jdbcPassword\|jdbcUsername\|accessToken\|user"' \
  "键名清单与顺序逐字在岗：长键在前、短键 user 在最后"
# 必须带词边界：`grep -rl "class SensitiveLogMask"` 连 SensitiveLogMaskTest 一起算成 2 份，
# 于是这条永远 FAIL —— 和上一轮 '@PostConstruct' 匹配到 import 行是同一类形状误判。
copies=$(grep -rl "class SensitiveLogMask\b" --include=*.java datax-admin datax-core datax-executor datax-rpc)
if [ "$(printf '%s\n' "$copies" | grep -c .)" -ne 1 ]; then
  echo "FAIL SensitiveLogMask 的实现类有 $(printf '%s\n' "$copies" | grep -c .) 份（明细：$(echo $copies | tr '\n' ' ')）—— 判定必须收在唯一一处，多份一定漂移"
  fail=1
elif ! printf '%s\n' "$copies" | grep -q '^datax-rpc/src/main/java/'; then
  echo "FAIL 唯一那份 SensitiveLogMask 不在 datax-rpc 的 main 源码里（$copies）—— 它在依赖最底层，放别处上层模块就引用不到"
  fail=1
else
  echo "OK   全仓库只有这一份 SensitiveLogMask 实现，且在 datax-rpc（各模块不许各抄一套）"
fi

echo
echo "== 源头：两个 toString 不得再产出凭据 =="
need "$PARAM" 'describeBlob\(jobJson\)' "派发用的明文 jobJson 从 toString 出去之前先换成掩码"
need "$PARAM" 'describeBlob\(glueSource\)' "GLUE 脚本内容同为大段不透明内容"
forbid "$PARAM" '\+ jobJson \+' "不得把 jobJson 原文再拼回 toString（第 3 条出口的源头就是它）"
forbid "$PARAM" '\+ glueSource \+' "不得把脚本原文再拼回 toString"
need "$REQ" 'describeSecret\(accessToken\)' "RPC 令牌从 toString 出去之前先换成掩码"
need "$REQ" 'maskSecretValues\(Arrays\.toString\(parameters\)\)' \
  "parameters 里跑的是执行器回传的 handleMsg，DataX 报错原文常自带凭据片段，要再扫一遍值"
# 打印处不许绕过 toString 自己把令牌拼进日志文案
forbid "$INVOKER" 'logger\.[a-zA-Z]+\(.*getAccessToken\(\)' "日志调用不得直接取令牌值拼串"

echo
echo "== 第二层：入库前那道脱敏必须复用同一份判定 =="
need "$TRIG" 'SensitiveLogMask\.maskSecretValues\(msg\)' "sanitizeTriggerMsg 走共享实现"
forbid "$TRIG" 'replaceAll' \
  "admin 侧不得再自己抄一份脱敏正则 —— 上一版就是这么漏掉转义形态与 URL 里的 username 的"

echo
echo "== 另外三个凭据持有者：自己的 toString 也不许交明文 =="
need "$JWTUSER" "password='\\[PROTECTED\\]'" "照 Spring 自己 UserDetails 实现的口径整段不给（连长度都不给：这一栏可能是明文登录口令，不是可排查用的令牌指纹）"
forbid "$JWTUSER" '\+ password \+' "不得把 principal 的口令再拼回 toString —— AbstractAuthenticationToken.toString() 会把它整条带出去"
need "$LOGIN" '@ToString\(exclude = "password"\)' "@Data 生成的 toString 必须把登录明文口令排掉"
need "$DS" '@ToString\(exclude = "jdbcPassword"\)' "@Data 生成的 toString 必须把解密后的数据源口令排掉"
# 上面两条只管"已经改到的这三处"，下面两条管"第四处不许再冒出来"：
#   scan1：@Data 会连着凭据字段一起生成 toString()，有这类字段却没 @ToString 处理就是出口；
#   scan2：手写 toString 的方法体里裸拼凭据字段。
# scan2 的 A/B 实测：拿改之前的 JwtUser（git show HEAD:）跑同一条扫描，命中
# `", password='" + password +`；拿现在的四处跑，输出为空 —— 扫描真的在识别，不是恰好为空。
MOD_MAIN="datax-admin/src/main/java datax-core/src/main/java datax-executor/src/main/java datax-rpc/src/main/java"
data_leaks=""
for f in $(grep -rl "@Data" --include=*.java $MOD_MAIN 2>/dev/null); do
  if grep -qiE "private String [A-Za-z]*([Pp]assword|[Pp]asswd|[Tt]oken|[Ss]ecret|[Cc]redential)[A-Za-z]*;" "$f" \
     && ! grep -q "@ToString" "$f"; then
    data_leaks="$data_leaks ${f##*src/main/java/}"
  fi
done
if [ -n "$data_leaks" ]; then
  echo "FAIL 这些 @Data 类含凭据字段却没有 @ToString 处理，Lombok 生成的 toString 会把口令一起打出来：$data_leaks"
  fail=1
else
  echo "OK   含凭据字段的 @Data 类都显式处理了 toString"
fi

raw_concat=$(awk '/public String toString\(\)/{inbody=1} inbody{print FILENAME"\t"$0} inbody&&/^[[:space:]]*\}[[:space:]]*$/{inbody=0}' \
  $(grep -rl "public String toString()" --include=*.java $MOD_MAIN 2>/dev/null) \
  | grep -E "\+ *(password|passwd|pwd|accessToken|token|secret|credential|jdbcPassword|userPassword|jsonAttribute)[^A-Za-z0-9_]")
if [ -n "$raw_concat" ]; then
  echo "FAIL 手写 toString 的方法体里还有裸拼的凭据字段："
  echo "$raw_concat"
  fail=1
else
  echo "OK   全仓库 main 源码里没有任何 toString 裸拼凭据字段"
fi

echo
echo "== 回归用例：core（含 rpc 类的行为）与 admin 两个模块都要跑 =="
run_test_gate "logmask-core" datax-core "SensitiveLogMaskTest,ToStringSecretMaskTest" || exit 1
run_test_gate "logmask-admin" datax-admin "JobTriggerSanitizeTest,CredentialEntityToStringTest" || exit 1

if [ "$fail" -ne 0 ]; then
  echo "FAIL: 凭据遮蔽的形状检查有未通过项（见上面 FAIL 行）"
  exit 1
fi
echo "PASS: 凭据遮蔽收在值的来源（唯一实现处 + RPC 两个 toString + 入库前第二层 + 用户/数据源三个凭据持有者）全部通过"
