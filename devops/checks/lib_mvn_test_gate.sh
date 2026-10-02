#!/usr/bin/env bash
# 被 devops/checks/check_*.sh source 的共享判定逻辑：跑一组测试，并拒绝"假绿"。
#
# 为什么存在：根 pom.xml 里 <maven.test.skip>true</maven.test.skip>，默认构建**既不编译
# 也不执行测试源码**。少了 -Dmaven.test.skip=false 时，测试是"看起来绿、其实没跑"。
# 所以判定顺序是固定的三步，缺一不可：
#   1) 日志里出现 "Not compiling test sources" —— 直接判失败；
#   2) 抓不到 "Tests run: ... Failures: ... Errors:" 汇总行 —— 说明测试根本没跑起来，判失败；
#   3) mvn 返回码非 0，或汇总行不是 "Tests run: <≥1>, Failures: 0, Errors: 0, Skipped: 0" —— 判失败。
#      条数必须是正整数：测试类被改名/删除、或被 @Ignore 掉时，surefire 照样打
#      "Tests run: 0, Failures: 0, Errors: 0"，mvn 也是 rc=0 —— 只看"0 失败"就会假绿，
#      规则其实一条都没验。Skipped 必须为 0，跳过等于没跑。
#
# 用法（在仓库根目录）：
#   . devops/checks/lib_mvn_test_gate.sh
#   run_test_gate "标签" "模块" "测试类A,测试类B"
#
# 两个 surefire 开关都要给：
#   -DfailIfNoTests=false                    是 surefire 2.x 的属性名
#   -Dsurefire.failIfNoSpecifiedTests=false  是 3.x 的属性名。只给前者时，-am 链上
#     "没有同名测试类"的模块（datax-rpc）会直接 BUILD FAILURE 把整条门禁带崩
#     —— clean 之后 datax-rpc/target/test-classes 一出现就触发，实测踩过。
#   两者都只影响"该模块没有匹配测试"，不影响"测试跑了但失败"，判定依旧严格。

run_test_gate() {
  local label="$1" module="$2" tests="$3"
  local log rc summary
  log=$(mktemp)

  mvn -B -pl "$module" -am install \
      -Dmaven.test.skip=false \
      -Dtest="$tests" \
      -DfailIfNoTests=false \
      -Dsurefire.failIfNoSpecifiedTests=false > "$log" 2>&1
  rc=$?

  echo "[${label}] mvn exit = ${rc}  (tests=${tests})"

  if grep -q "Not compiling test sources" "$log"; then
    echo "FAIL[${label}]: 测试源码被跳过，本次没有验证任何规则（检查 maven.test.skip 是否真的关掉了）"
    rm -f "$log"
    return 1
  fi

  summary=$(grep -E "^Tests run:.*Failures:.*Errors:" "$log" | tail -1)
  if [ -z "$summary" ]; then
    echo "FAIL[${label}]: 没有测试执行汇总，说明 [${tests}] 根本没跑起来"
    tail -20 "$log"
    rm -f "$log"
    return 1
  fi
  echo "${summary}"

  if [ "$rc" -ne 0 ] || ! echo "$summary" | grep -Eq "^Tests run: [1-9][0-9]*, Failures: 0, Errors: 0, Skipped: 0"; then
    echo "FAIL[${label}]: 回归未通过。汇总行必须是「至少 1 条、0 失败、0 错误、0 跳过」，当前是：${summary}"
    grep -E "Tests run|FAIL|ERROR" "$log" | head -20
    rm -f "$log"
    return 1
  fi

  rm -f "$log"
  echo "PASS[${label}]: 全部通过"
  return 0
}
