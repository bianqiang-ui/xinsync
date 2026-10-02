#!/usr/bin/env bash
# GATE: slow
# 批次 10-F 门禁：作业参数是命令行片段，不是数据。
#
# 背景：执行器把 jvmParam / replaceParam / partitionInfo 拼成 -j"..." -p"..." 交给 datax.py，
# 而 datax.py 收尾是 subprocess.Popen(startCommand, shell=True) —— 带反引号或美元符的
# JVM 参数等于"在执行器主机上以执行器用户的身份跑任意命令"。加权限判定挡不住这一层
# （权限只管谁能存，不管存进去的东西被执行时是什么），所以两道关口都要有人守着：
#   1) JobParamSafetyTest（datax-core，唯一实现处）——
#      双引号内依旧生效的 shell 字符（引号/反引号/美元符/反斜杠）与换行必须拒；
#      同时钉住合法写法必须放行：WHERE 条件里常用的 > < ; & ( ) %s 在双引号内是普通字面量，
#      守卫把它们一并禁掉就是把功能修坏；分区信息与日期样式的结构错误要在入库时报清楚，
#      不能等到执行器线程抛 NumberFormatException / IllegalArgumentException。
#   2) BuildCommandShellSafetyTest（datax-executor，挨着 shell 的最后一道）——
#      库里可以有历史脏行、批量建任务会把模板的 jvmParam 原样拷进新任务，所以拼 argv 之前
#      必须自己再判一次；用例里故意把 datax.py 路径写成不存在，断言拿到的是注入文案，
#      以此钉住"安全判定排在路径检查与拼命令之前"这个顺序。
#
# 批次 10-H 在这个门禁上补了三条（复核发现的，都是"看着已修、实际还通"的缺口）：
#   3) 日期格式那一栏同样是注入点。反引号/美元符不是 SimpleDateFormat 的样式字母，会被当成
#      字面量留在 sdf.format() 的产出里，再随 String.format(replaceParam, ...) 进 -p"..."。
#      10-F 只判了 jvmParam 与 replaceParam，add 路径靠那条"不在下拉列表就退回 Timestamp"的
#      白名单侥幸挡住了，update 路径没有白名单 —— 于是一条命令注入的成敗取决于用户点的是
#      "新建"还是"修改"。现在 replaceParamType 也过 shell 字符判定，且判定顺序排在样式合法性
#      之前（样式不合法时文案会回显原文，注入串不该进响应体）。
#   4) 判哪几个字段跟着 incrementType 走，条件与 BuildCommand 的消费条件逐字一致。
#      全判是守卫过宽：主键增量的任务里 partition_info 常残留早先的分区配置，此时那段
#      一个字节都不进命令行，却会让用户改不动自己的任务。
#   5) 判定对象必须等于执行器实际吃进去的那串。历史上"判的时候 trim、拼的时候不 trim"，
#      于是 "ds , 0 , yyyy-MM-dd" 绿灯入库、红灯在 Integer.parseInt(" 0 ") 上炸；
#      " Timestamp " 同理被当成日期样式抛 IllegalArgumentException。现在两边都 trim。
#
# 判定标准与 TDSQL 门禁完全一致（同一个 lib）：测试没被编译/没跑起来，本身就判失败。
set -u

cd "$(dirname "$0")/../.." || exit 1
. devops/checks/lib_mvn_test_gate.sh

# 唯一实现处在 core，执行器侧那道顺序也必须有测试 —— 两个模块都要跑，缺一个就是半覆盖
run_test_gate "job-param-core" datax-core "JobParamSafetyTest" || exit 1
run_test_gate "job-param-executor" datax-executor "BuildCommandShellSafetyTest" || exit 1

echo "PASS: 作业参数的 shell 注入判定（入库 + 拼命令两道关口）全部通过"
