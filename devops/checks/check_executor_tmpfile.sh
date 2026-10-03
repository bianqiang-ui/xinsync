#!/usr/bin/env bash
# GATE: slow
# 批次 10-J / B2 门禁：数据配置临时文件（jobTmp-*.conf，内含解密后的明文数据源口令）
# 既不能有一段"同机其他用户可读"的裸露期，也不能在进程被 kill 之后没人来删。
#
# 为什么两条都要、且形状检查不能省：
#   1) 行为侧（ExecutorTmpFileTest）验的是"最终权限对不对、清理只删陈年文件、开关能关掉、钩子真的挂着"。
#   2) 但"创建即 0600"这件事的**原子性**在测试里根本观测不到：先建后 chmod 与创建时带属性，
#      收尾权限一模一样。所以形状的退化（有人图省事改回 new PrintWriter + setReadable）必须靠读源码来挡 ——
#      这正是本项目踩过的那类假绿：门禁只断言结果，挡不住中间那段裸露窗口被改回去。
#   3) 另一头也要挡"只留形状不留功能"：POSIX 判断、非 POSIX 兜底、告警、逃生口，
#      少任何一个就是把在 Windows 上跑的部署修坏（红线：守卫不许把功能修坏，必须留逃生口）。
set -u

cd "$(dirname "$0")/../.." || exit 1
. devops/checks/lib_mvn_test_gate.sh

HANDLER=datax-executor/src/main/java/com/wugui/datax/executor/service/jobhandler/ExecutorJobHandler.java
PRIVATE=datax-executor/src/main/java/com/wugui/datax/executor/util/PrivateTmpFiles.java
YML=datax-executor/src/main/resources/application.yml

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
  # forbid <文件> <扩展正则> <说明>
  if grep -Eq "$2" "$1"; then
    echo "FAIL $1 里出现了「$2」—— $3"
    fail=1
  else
    echo "OK   $3"
  fi
}

for f in "$HANDLER" "$PRIVATE" "$YML"; do
  [ -f "$f" ] || { echo "FAIL 缺少文件：$f"; exit 1; }
done

echo
echo "== 写入侧形状：权限必须在「创建」那一次就带上 =="
need "$HANDLER" 'PrivateTmpFiles\.writeOwnerOnly\(' "临时配置走唯一实现处写入，不再各自拼一套"
forbid "$HANDLER" 'new[[:space:]]+PrintWriter\([[:space:]]*tmpFile' \
  "handler 里不得再直接建文件后 chmod（先建后改 = 明文口令有一段全局可读的窗口）"
need "$PRIVATE" 'CREATE_NEW' "创建走 CREATE_NEW：不覆盖同名文件，也不会先 truncate 再改权限"
need "$PRIVATE" 'asFileAttribute' "POSIX 上把 0600 作为创建属性交给同一次 open"
need "$PRIVATE" 'rw-------' "口径就是 owner 读写、组内与其他用户全无"
echo
echo "== 写入侧兜底：非 POSIX 平台不得被修坏 =="
need "$PRIVATE" 'supportedFileAttributeViews\(\)\.contains\("posix"\)' "先探测文件系统能力，Windows 上不至于当场抛异常"
need "$PRIVATE" 'writeThenRestrictPermissions' "探测不过去时要有一条兜底写法"
need "$PRIVATE" '\[SECURITY\]' "兜底降级必须显式告警，不能默默放过"
need "$PRIVATE" '窗口' "告警里要说清楚问题是没有创建时权限、存在可读窗口"
echo
echo "== 清理侧接缝：这个方法历史上零调用 =="
# 必须匹配"注解独占一行"的形状，不能只 grep '@PostConstruct'：
# `import javax.annotation.PostConstruct;` 里也含这个串，摘掉注解后 import 还在，
# 上一版因此报 OK —— 反证 B（摘注解）跑出来形状全绿、只有单测红，这条形状检查等于白写。
need "$HANDLER" '^[[:space:]]*@PostConstruct[[:space:]]*$' "启动钩子挂着注解，不靠人记得调用"
need "$HANDLER" 'public void cleanStaleTmpFilesOnStartup' "钩子本身是 public，被 Spring 生命周期调用"
forbid "$HANDLER" 'public void cleanStaleTmpFiles\(\)' \
  "不得留一个没人调的无参版本迷惑后来人"
need "$HANDLER" 'tmpclean\.enabled' "必须有 enabled 逃生口（同机多实例共享 jsonpath 时一个都不删）"
need "$HANDLER" 'tmpclean\.staleMinutes' "必须有陈年阈值（否则会把别的实例正在跑的长任务临时文件删掉）"
need "$HANDLER" 'jobTmpFiles\.values\(\)\.contains' "本 JVM 在跑任务的临时文件先跳过"
echo
echo "== 配置口径：@Value 的默认值与 yml 出厂值不得两套 =="
need "$YML" 'enabled: true' "yml 出厂开着启动清理"
need "$YML" 'staleMinutes: 1440' "yml 出厂阈值 1440 分钟"
need "$HANDLER" 'tmpclean\.staleMinutes:1440' "代码默认值与 yml 一致"
need "$HANDLER" 'tmpclean\.enabled:true' "代码默认值与 yml 一致"

echo
echo "== 行为侧回归用例 =="
run_test_gate "executor-tmpfile" datax-executor "ExecutorTmpFileTest" || exit 1

if [ "$fail" -ne 0 ]; then
  echo "FAIL: 数据配置临时文件的形状检查有未通过项（见上面 FAIL 行）"
  exit 1
fi
echo "PASS: 数据配置临时文件「创建即 0600 + 启动清理有钩子有阈值有逃生口」全部通过"
