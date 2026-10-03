#!/usr/bin/env bash
# datax-web fork 维护工作流（本地 only —— 本脚本不含任何 push）
#
# 用法（在仓库根目录 datax-web/ 下执行）:
#   bash devops/fork-workflow.sh status     # 当前分支/领先上游多少/未推送哪些提交/工作区状态
#   bash devops/fork-workflow.sh baseline    # 建/更新本地基线分支 upstream-baseline = upstream/master
#   bash devops/fork-workflow.sh fetch       # 只读拉取上游（git fetch upstream，不改工作区）
#   bash devops/fork-workflow.sh diff        # 我们相对上游基线的全部改动（文件级 + 行数）
#   bash devops/fork-workflow.sh recheck     # 自动发现并复跑 devops/checks 下全部 check_* 门禁 + shell 语法
#                                           # 加速：SKIP_MVN_GATES=1 只跑配置/语法类门禁，结果是 PARTIAL 且退出码非 0；
#                                           #       只想快速过一遍语法时再叠加 GATE_ALLOW_PARTIAL=1 才返回 0
#                                           # 门禁数量下限：MIN_GATES=N（默认 15），发现数不足直接 FAIL
#
# 从外层工作台 tools/fork-workflow.sh 搬进仓库（2026-10-03，#38）。搬进来的理由不是"顺手整理目录"：
# README 与 docs/ 里对外写着 `bash tools/fork-workflow.sh recheck`，而 tools/ 在仓库外 ——
# 任何人 clone 下来照做都得不到这个脚本，那句声明是空头支票。守仓库不变量的东西必须在仓库里。
#
# 搬进来时改了两处实质问题（不只是路径）：
#   1) 上一版把上游写死成 UPSTREAM="origin"。那是 fork 还不存在时的历史状态；现在
#      origin=https://github.com/bianqiang-ui/xinsync.git（**我们自己的 fork**）、
#      gitee=https://gitee.com/brian888/xinsync.git、upstream=WeiYe-Jing/datax-web。
#      再按老写法跑 baseline，就会把"纯上游基线"对齐到 fork 自己的 master，于是
#      git diff upstream-baseline..HEAD **把我们自己的提交当成上游已有的**，自研改动越做越看不见。
#      现在上游只认 upstream，并且 guard() 会主动判"基线落到了 fork 的提交上"这种漂移。
#   2) 自我引用从 $WORK_ROOT/tools/fork-workflow.sh 改成 ${BASH_SOURCE[0]}（见下面 shell 语法段）。
#
# 红线：本脚本永远不执行 git push / git remote set-url / git branch -D。
# 远端分支由维护者本人创建与推送（用户原话："分支等我回来创建，别私自上传"）。

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="upstream-baseline"
# 对外可复现的参照物：本地台账分支不随 fork 推送，陌生 clone 里只有这个 tag。
# 对外文档里的改动数字一律按它计（并由 check_doc_commands.py 第 10 条现场重量）。
UPSTREAM_TAG="v-2.1.2"
UPSTREAM_REMOTE="upstream"   # https://github.com/WeiYe-Jing/datax-web.git
FORK_REMOTE="origin"         # https://github.com/bianqiang-ui/xinsync.git（我们的 fork，只读）
UPSTREAM_URL="https://github.com/WeiYe-Jing/datax-web.git"

cd "$REPO"

guard() {
  # 任何写远端的行为都不在这里发生。这里只做三件读检查，每件都对应一种"静默失效"：
  #   a) 上游远端还在不在、地址对不对（被人 set-url 指到 fork 上，fetch 就再也拿不到上游更新）；
  #   b) 基线是不是真的等于 upstream/master（漂到 fork 上会让 diff 少算我们自己的改动）；
  #   c) 基线有没有正好落在 fork 自己的提交上（这是 b 的具体形态，单独报，因为它最容易发生）。
  local upstream_url baseline_sha upstream_sha fork_sha
  upstream_url="$(git remote get-url "$UPSTREAM_REMOTE" 2>/dev/null || echo NONE)"
  if [ "$upstream_url" != "$UPSTREAM_URL" ]; then
    echo "[FAIL] $UPSTREAM_REMOTE 的地址不是上游原始地址：$upstream_url"
    echo "       上游取不到，'我们落后多少'就会假 0。"
    return 1
  fi
  if git rev-parse --verify --quiet "$BASELINE" >/dev/null; then
    baseline_sha="$(git rev-parse "$BASELINE")"
    upstream_sha="$(git rev-parse "$UPSTREAM_REMOTE/master" 2>/dev/null || echo NONE)"
    fork_sha="$(git rev-parse "$FORK_REMOTE/master" 2>/dev/null || echo NONE)"
    if [ "$baseline_sha" != "$upstream_sha" ]; then
      echo "[WARN] $BASELINE($(git rev-parse --short "$BASELINE")) != $UPSTREAM_REMOTE/master($(git rev-parse --short "$UPSTREAM_REMOTE/master" 2>/dev/null || echo 未知))"
      echo "       要对外说'相对上游的改动'之前先跑 baseline 对齐基线。"
    fi
    if [ "$baseline_sha" = "$fork_sha" ] && [ "$fork_sha" != "$upstream_sha" ]; then
      echo "[FAIL] $BASELINE 被对齐到了 fork 自己的提交（$FORK_REMOTE/master），不是纯上游。"
      echo "       这个状态下 git diff $BASELINE..HEAD 会把我们的历史提交算成'上游已有'，自研改动凭空消失。"
      return 1
    fi
  else
    echo "[WARN] 还没有 $BASELINE 分支，先跑：bash devops/fork-workflow.sh baseline"
  fi
}

cmd_status() {
  echo "== remotes =="
  git remote -v
  echo
  echo "== current branch =="
  git rev-parse --abbrev-ref HEAD
  echo
  echo "== 未推送的本地提交（相对 $FORK_REMOTE/master）=="
  git log --oneline "$FORK_REMOTE/master..HEAD" 2>/dev/null || echo "  （没有 $FORK_REMOTE/master 这个引用）"
  echo
  echo "== 相对上游的提交（相对 $UPSTREAM_REMOTE/master）=="
  git log --oneline "$UPSTREAM_REMOTE/master..HEAD" 2>/dev/null | sed 's/^/  /' || echo "  （没有 $UPSTREAM_REMOTE/master 这个引用）"
  echo
  echo "== 上游有没有新提交我们没跟（ahead/behind：HEAD 相对 $UPSTREAM_REMOTE/master）=="
  git rev-list --left-right --count "$UPSTREAM_REMOTE/master...HEAD" 2>/dev/null \
    | awk '{print "  上游多 "$1" 条（我们落后），我们多 "$2" 条（自研）"}' || true
  echo
  echo "== working tree =="
  git status --short
  echo
  echo "== stash =="
  git stash list | sed 's/^/  /' || true
  echo
  guard
}

cmd_fetch() {
  echo "只读拉取 $UPSTREAM_REMOTE（不改工作区、不 merge）"
  git fetch "$UPSTREAM_REMOTE" --prune
  echo "已更新: $UPSTREAM_REMOTE/master = $(git rev-parse --short $UPSTREAM_REMOTE/master)"
}

cmd_baseline() {
  git rev-parse --verify --quiet "$UPSTREAM_REMOTE/master" >/dev/null \
    || { echo "[FAIL] 没有 $UPSTREAM_REMOTE/master 这个引用，先跑 fetch"; exit 1; }
  # 基线只准指向**上游**的提交。上一版这里用的是名为 origin 的远端，而 origin 现在是我们的 fork。
  if git rev-parse --verify --quiet "$BASELINE" >/dev/null; then
    git branch -f "$BASELINE" "$UPSTREAM_REMOTE/master"
    echo "基线分支 $BASELINE 已对齐 $UPSTREAM_REMOTE/master = $(git rev-parse --short $UPSTREAM_REMOTE/master)"
  else
    git branch "$BASELINE" "$UPSTREAM_REMOTE/master"
    echo "已创建基线分支 $BASELINE = $UPSTREAM_REMOTE/master = $(git rev-parse --short $UPSTREAM_REMOTE/master)"
  fi
  echo "用途: git diff $BASELINE..HEAD 即我们相对上游的全部改动"
}

cmd_diff() {
  # 参照物优先用本地台账基线分支（维护者口径：纯上游 sha 对齐）。
  # 但 $BASELINE 没随 fork 推送，陌生 clone 里根本不存在 —— 上一版这里直接
  # "先执行 baseline 子命令" 退出 1，而 CHANGELOG 把 `fork-workflow.sh diff` 当作
  # "复核口径与实测命令"写给读者，等于又一张空头支票（与门禁第 4 条同一类缺陷）。
  # 所以取不到基线分支时退回公开 tag：任何 clone 里都解析得到。
  local ref="$BASELINE"
  if ! git rev-parse --verify --quiet "$ref" >/dev/null; then
    ref="$UPSTREAM_TAG"
    git rev-parse --verify --quiet "$ref" >/dev/null || {
      echo "[FAIL] 既没有本地分支 $BASELINE，也找不到公开 tag $ref —— 无从对账"
      exit 1
    }
    echo "[提示] 本地没有 $BASELINE（它不随 fork 推送），改用公开 tag $ref 作参照物。"
    echo "       这个区间含上游 2.1.2 之后的提交，只按作者隔离才是我们自己的改动："
    echo "       git log --author=bianqiang@gmail.com --oneline $ref..HEAD"
  fi
  echo "== 相对 $ref 的文件级改动 =="
  git diff --stat "$ref"..HEAD
  echo
  echo "== 提交清单 =="
  git log --oneline "$ref"..HEAD
}

cmd_recheck() {
  # 自动发现 devops/checks 下（含子目录）全部 check_* 门禁：新增门禁不必再改本脚本。
  # 上一版是手写清单，漏掉了 check_tdsql.sh，导致我说过"recheck 全绿"其实是假陈述——
  # 清单驱动的写法让这类"加了门禁没接上"的失误无处藏身。
  #
  # 再补三条，都是复核点出来的"假绿通道"：
  #   1) 发现方式改成 find 递归：ls 只扫顶层，门禁被挪进子目录就静默不跑；
  #   2) 文件名是 check_* 但后缀不是 .py/.sh 时**判失败**，而不是悄悄忽略
  #      （上一版 ls 用 glob 过滤，check_authz_seams.bak 这种文件会直接消失）；
  #      __pycache__ 里的 .pyc 是 python 自动产物，同名的 .py 已被扫到，这里显式排除；
  #   3) 门禁数量下限 MIN_GATES：devops/checks 被误删/改名时，"发现 0 个" 不该是好消息。
  #      下限跟着实际门禁数走（当前 15 条）：**加一条门禁必须同时把这里抬上去**，
  #      否则"新增 9 条、被人无声删掉 3 条"依旧能报全绿。下限写在代码里而不是文档里，
  #      是为了让漏改在 recheck 当场变红灯，而不是等下一轮复核才发现。
  local -a gates=()
  local g name rc
  local min_gates="${MIN_GATES:-15}"
  while IFS= read -r g; do
    [ -n "$g" ] || continue
    case "$g" in
      *.py|*.sh) gates+=("$g") ;;
      *)
        echo "[FAIL] ${g#$REPO/} 以 check_ 开头但不是 .py/.sh，recheck 不会执行它。"
        echo "       要么改回可执行后缀，要么改名让它不再是门禁 —— 不许留成「看起来有门禁」。"
        exit 1
        ;;
    esac
  done < <(find "$REPO/devops/checks" -type f -name 'check_*' \
                  -not -path '*/target/*' -not -path '*/__pycache__/*' 2>/dev/null | sort)

  if [ "${#gates[@]}" -lt "$min_gates" ]; then
    echo "[FAIL] 只发现 ${#gates[@]} 个门禁，少于下限 ${min_gates}（可用 MIN_GATES=N 调整）"
    echo "       recheck 的「全绿」必须有可数的门禁撑着，清单缩水本身就是失败"
    exit 1
  fi

  local fail=0 slow_skipped=0
  for g in "${gates[@]}"; do
    name="$(basename "$g")"
    echo
    echo "== gate ${name} =="
    case "$g" in
      *.py)
        # JDK8 镜像里只有 python3、没有 python 这个名字（宿主 Git-Bash 反之），
        # 上一版写死 python，在容器里跑 recheck 时 7 个 python 门禁全部 "command not found"，
        # 靠 127 退出码才没变成假绿 —— 但门禁本身一条都没执行。探测顺序固定：python3 优先。
        #
        # 但"能 command -v 到"不等于"能用来跑门禁"：Windows 的 PATH 上
        # `python3` 是 Microsoft Store 的占位程序（AppInstallerPythonRedirector.exe），
        # command -v 找得到它，跑起来 rc=49、**一行输出都没有** —— 于是 7 个 python 门禁
        # 全部静默不执行，日志里只有 "== gate xxx.py" 后面一片空白。
        # 所以候选必须**真跑一句 print** 才算数，不能只看命令是否存在。
        PYBIN=""
        for cand in python3 python; do
          if command -v "$cand" >/dev/null 2>&1 \
             && [ "$("$cand" -c 'print("gate-interpreter-ok")' 2>/dev/null)" = "gate-interpreter-ok" ]; then
            PYBIN="$cand"; break
          fi
        done
        if [ -z "$PYBIN" ]; then
          echo "[FAIL] 找不到可用的 python3/python（存在但跑不出输出的占位程序不算），${name} 未执行"
          fail=1; continue
        fi
        # 只判退出码不够：解释器被占位程序顶掉时 rc 非 0 能抓到，但"脚本自己提前 return、
        # 什么都没打印却回 0"这类通道抓不到。门禁必须**自报结论**（输出一行 PASS 开头的总结），
        # 没有结论就判失败 —— 宁可红灯，也不要一片空白配一句"全绿"。
        gate_out="$("$PYBIN" "$g" 2>&1)"; gate_rc=$?
        [ -n "$gate_out" ] && echo "$gate_out"
        if [ "$gate_rc" -ne 0 ]; then fail=1; fi
        if ! printf '%s\n' "$gate_out" | grep -q '^PASS'; then
          echo "[FAIL] ${name} 没有自报结论（rc=${gate_rc}，输出里没有 PASS 行）—— 静默不执行不得当作通过"
          fail=1
        fi
        ;;
      *.sh)
        if grep -q '^# GATE: slow' "$g"; then
          # mvn 类门禁只认 JDK8 容器：宿主机 PATH 上确实有一个 mvn（Android Studio 的 jbr 当 JAVA_HOME，
          # JDK17/21），拿它跑会在 datax-core 报 "程序包 javax.annotation 不存在" 而整个门禁假失败。
          # 所以判据不是"有没有 mvn"，而是"这是不是 JDK8 环境"——除非显式点名要用宿主 mvn。
          if [ "${SKIP_MVN_GATES:-0}" = "1" ]; then
            echo "[SKIP] ${name} 被 SKIP_MVN_GATES=1 跳过（mvn 类门禁未执行，本次不是完整复跑）"
            slow_skipped=$((slow_skipped + 1))
            continue
          fi
          if [ "${GATE_USE_HOST_MVN:-0}" = "1" ]; then
            echo "[WARN] ${name} 用宿主 mvn 跑（GATE_USE_HOST_MVN=1），JDK 版本不对会假失败"
            bash "$g" || fail=1
          elif command -v javac >/dev/null 2>&1 && javac -version 2>&1 | grep -Eq '(^|[^0-9])1\.8[._]'; then
            # 判据改成"PATH 上的 javac 报 1.8"，而不是"存在 /usr/bin/javac 且输出里带引号的 1.8"。
            # 上一版两条都不成立：maven:3.8-openjdk-8 里 javac 在 /usr/local/openjdk-8/bin，
            # 而 javac -version 的输出是「javac 1.8.0_342」——根本没有引号。
            # 结果就是在 JDK8 容器内跑 recheck 时三个 mvn 门禁全被判成"既不在 JDK8 也没有 docker"。
            bash "$g" || fail=1
          elif command -v docker >/dev/null 2>&1; then
            MSYS_NO_PATHCONV=1 docker run --rm \
              -v "$REPO":/work -v datax-m2:/root/.m2 -w /work \
              maven:3.8-openjdk-8 bash "/work/devops/checks/${name}" || fail=1
          else
            echo "[FAIL] ${name} 既不在 JDK8 环境也没有 docker —— 不得当作通过"
            fail=1
          fi
          continue
        fi
        bash "$g" || fail=1
        ;;
    esac
  done

  echo
  echo "== shell 脚本语法（仓库内全部 .sh，含本工作流脚本自己）=="
  # 必须覆盖顶层 bin/*.sh：上一版只扫 */src/main/bin/*.sh，install.sh / start*.sh 漏检。
  # 上一版还要在这里额外补一句外层 tools/fork-workflow.sh，因为那个脚本在仓库外、find 扫不到；
  # 现在脚本本体已经搬进 devops/，仓库内的 find 自动覆盖它，多余的补行删掉（免得同一文件查两遍、
  # 计数虚高一倍，把"一个 .sh 都没匹配到"那条下限检查也一起骗过去）。
  # 计数按**仓库内**口径排除 `tmp/`（草稿与反证用的整树沙箱都住在那儿）：
  # 实测上一版把工作树整个扫了一遍，16 个真实脚本 + 沙箱副本 = 汇总行报"62 个 shell 脚本"，
  # 数字随沙箱增减而漂（同一轮里 46→62），而且沙箱是"故意改坏"的地方 ——
  # 扫它会把一次正常的反证读成 recheck 变红，把真正的防线和沙箱搅成一锅。
  # `*/.git/*` 一并排掉：hook 样例不是交付物。
  local sh_checked=0 sh_fail=0 f
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    if bash -n "$f"; then
      echo "OK   ${f#$REPO/}"
    else
      echo "[FAIL] ${f#$REPO/} 语法不通过"
      sh_fail=1
    fi
    sh_checked=$((sh_checked + 1))
  done < <(find "$REPO" -name '*.sh' -not -path '*/target/*' -not -path '*/tmp/*' -not -path '*/.git/*' 2>/dev/null | sort)
  if [ "$sh_checked" -eq 0 ]; then
    echo "[FAIL] 一个 .sh 都没匹配到，门禁等于没跑"
    fail=1
  fi
  if [ "$sh_fail" -ne 0 ]; then
    fail=1
  fi

  echo
  echo "== 远端拓扑与基线体检（本脚本不向任何远端 push）=="
  git remote -v
  guard || fail=1

  echo
  if [ "$fail" -ne 0 ]; then
    echo "RECHECK FAIL: 有门禁未通过（发现 ${#gates[@]} 个门禁）"
    exit 1
  fi
  if [ "$slow_skipped" -gt 0 ]; then
    echo "RECHECK PARTIAL: 配置/语法类门禁通过，但有 ${slow_skipped} 个 mvn 门禁被跳过，不得对外声称全绿"
    # 上一版这里 exit 0：跳过 mvn 门禁的"部分复跑"和"完整复跑"在退出码上长得一模一样，
    # 任何 `if recheck; then 声明全绿` 的调用方都会被带偏。现在 PARTIAL 一律非 0，
    # 只有显式 GATE_ALLOW_PARTIAL=1（例如只想快速看语法）才放行，且放行会再打一行 WARN。
    if [ "${GATE_ALLOW_PARTIAL:-0}" = "1" ]; then
      echo "[WARN] GATE_ALLOW_PARTIAL=1：本次 PARTIAL 被人为放行，结论仍不得对外签收"
      exit 0
    fi
    exit 1
  fi
  echo "RECHECK PASS: ${#gates[@]} 个门禁 + ${sh_checked} 个 shell 脚本全部复跑通过"
}

case "${1:-status}" in
  status)   cmd_status ;;
  fetch)    cmd_fetch ;;
  baseline) cmd_baseline ;;
  diff)     cmd_diff ;;
  recheck)  cmd_recheck ;;
  *) echo "未知子命令: $1（可用: status|fetch|baseline|diff|recheck）"; exit 1 ;;
esac
