#!/usr/bin/env bash
# GATE: slow
# admin 回归门禁：本批次修的两条 bug 必须一直有人守着，否则上游一合并就悄悄退化。
#
# 覆盖的规则（每一条都做过反证——把修复撤掉后本门禁必须 FAIL）：
#   1) JobScheduleHelperMisfireLogTest —— cron 非法导致 next 原地不动时，misfire WARN 必须限流，
#      5 个扫描周期只许打 1 条；否则日志会被每 5 秒一条刷爆。
#   2) JobDatasourceControllerUpdateTest —— 数据源部分字段更新的两条 NPE 路径：
#      库里查不到该 id 要返回失败而不是 updateById(null 语义)；比较 jdbcUsername 必须以库里值为主语。
#   3) BaseQueryToolMaxIdTest —— 增量主键上界取不到时（#672）必须抛出让触发侧记为失败，
#      不能再静默返回 0 让任务"报成功、零字节"；只有空表的 NULL 才算合法的 0。
#   4) AccessControlTest —— 权限模型：普通用户不得调 add/update/remove，也不得用 updatePwd
#      改别人的密码（原先任何登录用户都能重置 admin 口令）。
#
# 判定标准与 TDSQL 门禁完全一致（同一个 lib）：测试没被编译/没跑起来，本身就判失败。
set -u

cd "$(dirname "$0")/../.." || exit 1
. devops/checks/lib_mvn_test_gate.sh

run_test_gate "admin-regression" datax-admin \
    "JobDatasourceControllerUpdateTest,JobScheduleHelperMisfireLogTest,BaseQueryToolMaxIdTest,AccessControlTest" || exit 1
echo "PASS: admin 回归用例全部通过"
