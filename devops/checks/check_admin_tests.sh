#!/usr/bin/env bash
# GATE: slow
# admin 回归门禁：本批次修的 bug 必须一直有人守着，否则上游一合并就悄悄退化。
#
# 覆盖的规则（每一条都做过反证——把修复撤掉后本门禁必须 FAIL）：
#   1) JobScheduleHelperMisfireLogTest —— cron 非法导致 next 原地不动时，misfire WARN 必须限流，
#      5 个扫描周期只许打 1 条；否则日志会被每 5 秒一条刷爆。
#   2) JobDatasourceControllerUpdateTest —— 数据源写操作收归管理员；部分字段更新的两条 NPE 路径
#      库里查不到该 id 要返回失败而不是 updateById(null 语义)；比较 jdbcUsername 必须以库里值为主语。
#   3) BaseQueryToolMaxIdTest —— 增量主键上界取不到时（#672）必须抛出让触发侧记为失败，
#      不能再静默返回 0 让任务"报成功、零字节"；只有空表的 NULL 才算合法的 0。
#   4) AccessControlTest —— 权限模型：普通用户不得调 add/update/remove，不得用 updatePwd 改别人密码，
#      也不得按 id 操作别人的资源（IDOR）。
#   5) JobServiceBatchAddOwnerTest —— 批量建任务的归属必须落到调用者本人：写路径开始判归属之后，
#      沿用模板 copyProperties 带出来的 user_id 会让"普通用户批量建的一整批任务自己改不了"。
#   6) SqlSafeIdentifierTest + BaseFormOrderByWhitelistTest —— 分页接口的 ascs/descs 与列查询 key
#      是"用户可控且必然落进 SQL 结构位置"的字符串（mybatis-plus 的列名参数不走预编译），
#      只许是裸列名；同时钉住合法用法（ascs=datasource_name、驼峰 key 规范化）不被白名单误杀。
#   7) JobLogControllerOwnershipTest —— 运行日志面在"地址/PID 只认库里那行"之后剩下的另一半：
#      谁能按 logId 调。读别人的日志正文（含目标库连接串与数据样本）、kill 别人正在跑的作业，
#      都必须"管理员或本人"，且判定要排在发 RPC 之前。
#   8) JwtAuthFailurePathTest —— 接了归属判定之后，"从 token 取调用者 id"成了每个受管接口的必经
#      步骤：缺头、非 Bearer、签名不符都必须是"没有身份"（null → 最小权限拒绝），不能是 500；
#      同时钉住正常 token 照样解得出 id、无头请求仍旧交给 Spring Security 兜。
#
# 判定标准与 TDSQL 门禁完全一致（同一个 lib）：测试没被编译/没跑起来，本身就判失败。
set -u

cd "$(dirname "$0")/../.." || exit 1
. devops/checks/lib_mvn_test_gate.sh

GATE_TESTS="JobDatasourceControllerUpdateTest,JobScheduleHelperMisfireLogTest,BaseQueryToolMaxIdTest,AccessControlTest,JobServiceBatchAddOwnerTest,SqlSafeIdentifierTest,BaseFormOrderByWhitelistTest,JobLogControllerOwnershipTest,JwtAuthFailurePathTest"

# 上游自带的测试类大多要连真库/真服务，在这个 fork 的门禁环境里跑不了。
# 但"新写了一个 *Test 却没进任何名单"必须当场 FAIL —— 否则门禁名单会变成静默漏跑的黑名单，
# 而 recheck 照样全绿（复核时抓到的那条通道就是这么开的）。
EXEMPT_TESTS="AbstractSpringMvcTest AdminBizTest DataxJsonHelperTest ExecutorBizTest Hbase11xsqlToolTest Hbase20xsqlQueryToolTest I18nUtilTest JacksonUtilTest JobGroupMapperTest JobInfoMapperTest JobLogGlueMapperTest JobLogMapperTest JobRegistryMapperTest MySQLQueryToolTest OracleQueryToolTest PostgresqlQueryToolTest SqlServerQueryToolTest"

# 第三类出口：被别的门禁跑着的测试（例如 TdsqlDdlRewriterTest 由 check_tdsql.sh 执行）。
# 不手写清单，直接扫 devops/checks/check_*.sh 里有没有出现这个类名 —— 手写会漂，
# 上一版就是因为它只认"GATE_TESTS / EXEMPT"两类，把 tdsql 门禁正在跑的用例误判成漏跑。
covered_by_other_gate() {
  local name="$1" other
  for other in devops/checks/check_*.sh; do
    [ "$other" = "devops/checks/check_admin_tests.sh" ] && continue
    if grep -q "\b${name}\b" "$other"; then
      echo "${other##*/}"
      return 0
    fi
  done
  return 1
}

uncovered=""
for name in $(find datax-admin/src/test/java -name '*Test.java' | sed 's#.*/##; s#\.java$##' | sort); do
  case ",$GATE_TESTS," in *",$name,"*) continue ;; esac
  case " $EXEMPT_TESTS " in *" $name "*) continue ;; esac
  if gate=$(covered_by_other_gate "$name"); then
    echo "OK   ${name} 由 ${gate} 覆盖"
    continue
  fi
  uncovered="$uncovered $name"
done
if [ -n "$uncovered" ]; then
  echo "FAIL: 这些测试类既不在门禁名单、不在豁免清单、也没被别的门禁跑到：$uncovered"
  echo "      要么加进 GATE_TESTS，要么进 EXEMPT_TESTS 并写清为什么跑不了"
  exit 1
fi

run_test_gate "admin-regression" datax-admin "$GATE_TESTS" || exit 1
echo "PASS: admin 回归用例全部通过"
