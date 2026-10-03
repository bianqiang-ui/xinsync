#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""门禁：对外"照做类"文档里的每一条命令，都必须在这台机器上真的能跑通形状。

守的是什么（批次 #38，2026-10-03；四条都是实测出来的"照做必失败"）：

1. `mysql -u root -p < doc/db/datax_web.sql` —— 仓库里**没有** `doc/db/`，真路径是
   `bin/db/datax_web.sql`。
2. `cd build/docker && docker-compose up -d` —— 全仓库 `Dockerfile*` 与 `docker-compose*`
   **各 0 命中**（`build/` 里只有一个 223MB 的 tar，而且是构建产物、在 .gitignore 里）。
3. `java -jar datax-admin-*.jar` / `java -jar data-executor-*.jar` —— 全仓库 pom 里
   **没有 `spring-boot-maven-plugin`**（只有 `maven-jar-plugin`/`exec-maven-plugin`/
   `maven-assembly-plugin`），产出的 jar 的 MANIFEST 里没有 `Main-Class`/`Start-Class`，
   所以 `java -jar` 必然报"没有主清单属性"。部署包里的 jar 是**故意不能直接跑**的，
   必须由 `bin/datax-admin.sh` 起（classpath、`conf/`、logback 都靠脚本注入）。
4. `bash tools/fork-workflow.sh recheck` —— README 让读者 clone 之后跑这条，而 `tools/`
   在**仓库外**（外层工作台目录）。clone 下来根本没有这个文件：这是一张空头支票。
   （本脚本自己就是这件事的修法：脚本搬进 `devops/fork-workflow.sh`，README 改指它。）

为什么只扫 ``` 围栏代码块：围栏里的内容才是"读者会整行复制"的东西。
正文里写"**不要**用 `java -jar`，本仓库不打 fat jar"同样是这句话，但它不是步骤 ——
按行扫全文会把正确的反面教材判成违规，于是下一次改文档的人只能把话删掉，
门禁反而把好内容赶跑了。**范围窄但判据硬**，是这条门禁能活下去的前提。

为什么"仓库内相对路径"才查、绝对路径不查：部署文档里 `/usr/local/datax-web`、
`C:\datax-web` 这类是**目标机**上的路径，本机当然不存在，查它们等于天天红灯。
判据划在"这个路径若来自本仓库，就必须真的在仓库里"。构建产物（`build/`、`packages/`、
`<模块>/target/`）按 .gitignore 的口径豁免 —— 它们是 `mvn install` 之后才有的东西，
要求它们在仓库里存在是错的。

第 5 条是这条门禁自带的元规则：**门禁引用的数字必须自己数出来**。
README 里"9 个自动化安全检查""9 道门禁"这类声明就是靠人记得改数字维持的，
而人改过一次不会改第二次（实测：门禁早已 12 条，README 还写 9）。
现在把"文档声明的门禁条数 == `devops/checks/check_*` 实际条数 ==
`devops/fork-workflow.sh` 里的 `MIN_GATES` 默认值"三者钉成一条，
加门禁时忘抬下限、或者 README 数字没跟着改，都会在这里变红灯。
数字对账的作用域是"现状文档"（README*、`doc/XinSync-*`）：
台账（保真、不外发）里那些"N 个门禁"是当时那条 recheck 输出的原文引用，
把它们改成现在的数字等于把开发日志改成假事实 —— 与命令扫描同一取舍。
同理，`新增第 9 个门禁` 这种带"第"字的序数句是历史陈述，永久为真，不参与对账。

第 6 条：recheck 的入口必须随仓库交付。README/手册教读者 `bash tools/fork-workflow.sh recheck`，
而 `tools/` 在外层工作台目录里、clone 下来不存在（就是第 4 条那个空头支票的另一半）。
修法是把脚本搬进 `devops/fork-workflow.sh`，文档改指它；门禁两侧都钉：
旧写法命中即 FAIL，同时要求现状文档里**至少有一处**写了对内入口 ——
只删旧的不写新的，读者照样复跑不了。

第 7 条：markdown 表格行必须在**同一行**闭合（行首是 `|` ⇒ 去掉空白后行尾也必须是 `|`）。
实测教训（第十八轮 #40，就在写这一轮文档的时候发生的）：给技术手册 §7 那张三列表补
"不含携带该数字的提交本身"这半句时，一次编辑把内容敲成了两个物理行 ——
第一行 `…+14,800 / −654**，` 结尾没有 `|`，第二行从"口径为 …"裸起。GFM 里第二行**不再是表格的一部分**，
整行的单元格随之错位；而 git 不报错、其余门禁全绿、diff 里人眼看不出（正因为它是"合法散文"）。
所以这条只能机器钉。
范围与命令扫描**不同**：命令扫描只动对外照做类文档，因为那里的命令是"当时实测"的历史记录；
表格语法不是事实而是**装订**，把断行接回去不改变任何一句陈述的内容，所以台账一起查。
围栏内的行一律跳过 —— 文档里的 mermaid 边标签、ASCII 样例都有以 `|` 开头的行，那是图不是表。
"""
import io
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# "照做类"文档：读者会一行行复制的对外入口。开发台账与技术手册（保真、不外发）**不在内** ——
# 那里的命令是"当时在哪台机器、哪个状态下实测的"历史记录，把它们批量改对等于把日志改成假事实。
HOWTO_PATTERNS = ("README*.md", "doc/XinSync-*.md", "doc/datax-web/*deploy*.md")

MODULES = ("datax-admin", "datax-core", "datax-executor", "datax-rpc", "datax-assembly")
# .gitignore 里就是被忽略的构建产物：要求它们"在仓库里存在"是错的
BUILD_OUTPUT_PREFIXES = ("build/", "packages/")

FENCE_RE = re.compile(r"^\s*(```|~~~)\s*([A-Za-z0-9_+-]*)")

# 围栏语言标里**不**算"可照抄命令"的那些：图、配置、示例数据。
# 判据用"排除表"而不是"白名单"——白名单会漏掉下一个新写法（```zsh、```console-session），
# 一漏就静默不扫；排除表漏了只会多扫，多扫由 `looks_like_repo_path` 那一层兜住。
# 最典型的一条：技术手册里的 mermaid 边标签 `J -->|java -jar| X1[失败：没有主清单属性]`
# 是**反面教材**，扫它就把正确内容判成违规。
NON_COMMAND_LANGS = {
    "mermaid", "json", "yaml", "yml", "xml", "html", "java", "python",
    "sql", "ini", "csv", "dot", "graphviz", "plantuml", "puml",
}

# 这几个语言标**不说明内容不是命令**（`text` 里完全可以放一条能跑的命令），
# 所以不能直接进上面的排除表 —— 否则"把违规命令改标成 ```text"就是门禁的静默逃生口。
# 处理见 fenced_lines：只有块内或围栏上方写着失效声明才豁免。
DEFERRABLE_LANGS = {"text", "plain", "console"}
# 失效声明的措辞（写给读者看的那句，不是写给门禁看的）
DECLAIM_RE = re.compile(r"已失效|勿照抄|不再可用|起不来")

# 一条命令里"可能是路径"的位置：< 重定向、java -jar、cd、bash/sh/source、tar -f、markdown 链接目标
PATH_REF_RES = (
    ("输入重定向", re.compile(r"(?:^|\s)<\s*([A-Za-z0-9_./*-][^\s;|&)`'\"]*)")),
    ("java -jar", re.compile(r"java\s+(?:-\S+\s+)*-jar\s+([A-Za-z0-9_./*-][^\s;|&)`'\"]*)")),
    ("cd 到目录", re.compile(r"(?:^|[;&|]\s*)cd\s+([A-Za-z0-9_./-][^\s;|&)`'\"]*)")),
    ("解释器执行脚本", re.compile(r"(?:^|\s)(?:bash|sh|source)\s+([A-Za-z0-9_./-][^\s;|&)`'\"]*)")),
    ("tar 读文件", re.compile(r"tar\s+[^\n|;]*?(?:-f|--file)\s+([A-Za-z0-9_./-][^\s;|&)`'\"]*)")),
)

DOCKER_RE = re.compile(r"\bdocker-compose\b|\bdocker\s+compose\b|\bDockerfile\b")
BOOT_PLUGIN_RE = re.compile(r"spring-boot-maven-plugin")
MIN_GATES_RE = re.compile(r'MIN_GATES:-(\d+)\}')
# 门禁条数只在"现状文档"里对账：README 与启动指南。
# 开发台账（保真、不外发）整体排除 —— 里面那些"N 个门禁"要么是当时那条
# `RECHECK PASS: 8 个门禁…` 输出的**原文引用**，要么是"这一轮从 7 抬到 8"的过程记录；
# 把它们改成现在的数字等于把开发日志改成假事实（与文件头对命令扫描的同一取舍）。
CLAIM_PATTERNS = ("README*.md", "doc/XinSync-*.md")
# 现状文档里照抄的一行复跑输出（`RECHECK PASS: 11 个门禁 + …`）记的是**当时**的实测值，
# 不参与对账。判据必须精确到"这一小段"，不能整行跳过：
# 上一版用 `if 'RECHECK PASS' in line: continue`，结果技术手册里那条**门禁清单表格行**
# 因为描述文字中引用了一次 `RECHECK PASS`，整行不被采集，门禁反过来报"清单少了一个"。
GATE_OUTPUT_QUOTE_RE = re.compile(r"RECHECK\s+(?:PASS|FAIL)[:：]?\s*\d+")
# "9 个自动化安全门禁 / 9 道门禁 / 9 automated security gates / 9 个安全检查"
# 第 1 组是"第"字序数标记，命中即视为**历史陈述**豁免：`新增第 9 个门禁`、
# `第 10 个门禁检查的是…` 记的是"当时排到第几"，永远为真；光板计数 `9 个门禁` 才是
# "现在有几道防线"的现状声明，必须等于实际条数。
# （不用 `(?<!第)` 零宽断言：实际文本是"第 9"带空格，定长断言挡不住，会误判 20 余条台账为谎报，
#   那种门禁只会被下一次改文档的人整体关掉。）
GATE_CLAIM_RES = (
    (re.compile(r"(第\s*)?(\d+)\s*(?:个|道)\s*(?:(?:自动化|质量|安全|可复跑|内置)\s*)*(?:检查|门禁)"), 1, 2),
    (re.compile(r"(\d+)\s+automated\s+security\s+(?:gates|checks)", re.I), None, 1),
    (re.compile(r"\|\s*`(?:[A-Za-z0-9_./-]+/)?(check_[a-z_]+\.(?:py|sh))`\s*\|"), "TABLE", None),
)
# ↑ 表格行**必须允许 `devops/checks/` 这类目录前缀**。实测教训：第一版只匹配裸文件名
# `` `check_x.py` ``，而新写的 README 每行都带目录 → 命中 0 条 →
# "表格行数不得少于实际条数"这条规则静默失效、门禁照样报绿（与第十六轮
# "`grep '@PostConstruct'` 命中 import 行造成假绿"同一类自空洞缺陷）。
# 判据因此升级为**集合相等**：列了门禁表的文档，门禁集合必须与实际一一对应 ——
# 少列 = 对外少报防线，多列 = 对外宣传了已经不存在的防线。
GATE_TABLE_MIN_ROWS = 3

failures = []
notes = []


def fail(msg):
    failures.append(msg)


def read(path):
    with io.open(str(path), encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")


def fenced_lines(text):
    """只吐围栏代码块里的行，且按围栏语言标剔掉"不是命令"的那些块。

    围栏外的散文不参与判定 —— 见文件头。

    特例：`text`/`console`/`plain` 这一类**语言标本身不说明内容不是命令**（把命令写进
    ```text 就能躲过扫描，等于给门禁开了个静默逃生口）。所以这一类只有当块内或围栏前
    三行里出现"已失效 / 勿照抄"声明时才豁免 —— **声明必须写在文档里给读者看**，
    不是写在这个门禁的白名单里。没有任何失效声明的 ```text 块照旧逐行扫。
    """
    lines = text.split("\n")
    out = []
    inside = False
    fence_marker = None
    lang = None
    start_no = 0
    block = []
    deferred = False

    def block_exempted(open_no, body_lines):
        head = "\n".join(lines[max(0, open_no - 4):open_no])
        return bool(DECLAIM_RE.search(head) or DECLAIM_RE.search("\n".join(body_lines)))

    for no, line in enumerate(lines, start=1):
        m = FENCE_RE.match(line)
        if m:
            marker = m.group(1)
            if not inside:
                inside = True
                fence_marker = marker
                lang = m.group(2).lower()
                start_no = no
                block = []
                deferred = lang in DEFERRABLE_LANGS
            elif line.strip().startswith(fence_marker):
                inside = False
                body_texts = [l for _n, l in block]
                if deferred:
                    if not block_exempted(start_no, body_texts):
                        out.extend(block)
                elif lang not in NON_COMMAND_LANGS:
                    out.extend(block)
                deferred = False
                block = []
            continue
        if inside:
            block.append((no, line))
    return out


def looks_like_repo_path(tok):
    """只在"这个串像是从仓库根出发的相对路径"时判定。绝对路径、变量、URL 一律放过。"""
    if not tok or tok.startswith(("/", "~", "$", "%")):
        return False
    if re.match(r"^[A-Za-z]:[\\/]", tok):
        return False
    if tok.startswith("http://") or tok.startswith("https://"):
        return False
    if tok in ("-", "..", "."):
        return False
    # 纯选项或纯数字（-d、9527）不是路径
    if tok.startswith("-"):
        return False
    return bool(re.search(r"[/.*]", tok)) or "/" in tok


# "当前这条命令还在不在仓库根里"的跟踪。没有这一层就会两处误判：
#   - `git clone …` 后紧跟的 `cd xinsync`：那是读者刚 clone 出来的本仓库，仓库内路径当然成立，
#     但 `xinsync` 这个名字在**我们这份**工作副本里不存在；
#   - 部署段的 `cd /opt/datax-web/admin` 之后的 `bash bin/datax-admin.sh start`：
#     那是 tar 包解开后的目录，仓库里 `bin/` 下确实没有这个脚本（它在 `datax-admin/src/main/bin/`），
#     按仓库根判就会红 —— 而这条命令本身是对的。
# 规则：cd 进绝对路径 / 变量 / `<占位符>` / 仓库里不存在的相对路径 ⇒ 之后进入"外部目录"，
# 外部目录里的相对路径不参与仓库存在性判定；cd 回 clone 目录或仓库内真实目录 ⇒ 恢复判定。
# 实测教训（反证 A 的第一遍判成"不成立"）：上一版把源地址写成
# `[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*`，字符类里没有 `:`，于是
# `git clone https://github.com/bianqiang-ui/xinsync.git` 只捕获到 `https`，
# clone_dirs={'https'} → README 的 `cd xinsync` 落到"仓库里没有这个目录"分支
# → in_repo_dir 转 False → **整篇文档的路径判定静默跳过**。
# 后果不是报错而是假绿：把 SQL 路径改回不存在的 doc/db/ 门禁依然 PASS。
# 所以这里改成"先取整条命令的源地址，再按选项表跳过取值"，URL/scp 形态都能识别。
CLONE_RE = re.compile(r"\bgit\s+clone\s+(.+)")
CD_RE = re.compile(r"(?:^|[;&|]\s*)cd\s+([^\s;|&)`'\"]+)")
# `git clone` 里带独立取值的选项。必须跳过它们的取值，否则 `--depth 1 <url>` 会把 `1`
# 当成源地址。其余一律按"源地址"处理：只允许这几种形式（https://、ssh://、git@host:path、
# 裸 .git 结尾、本地相对路径），而文档实测写法就是前两种。
CLONE_VALUE_OPTS = {"-b", "--branch", "-o", "--origin", "--depth", "-j", "--jobs",
                    "--reference", "--reference-if-able", "-c", "--config", "--filter",
                    "--url", "--template", "--separate-git-dir"}


def clone_basename(tok):
    last = tok.rstrip("/").split("/")[-1]
    if last.endswith(".git"):
        last = last[:-4]
    return last


def clone_dir_names(line):
    """这条 `git clone` 会在当前目录生成哪个目录名（显式目标目录优先）。"""
    m = CLONE_RE.search(line)
    if not m:
        return set()
    args = []
    skip_next = False
    for tok in re.split(r"[\s;|&]+", m.group(1)):
        if not tok:
            continue
        if skip_next:                       # 上一个 token 是选项的取值，不是位置参数
            skip_next = False
            continue
        if tok.startswith("-"):
            if "=" not in tok and tok.split("=")[0] in CLONE_VALUE_OPTS:
                skip_next = True
            continue
        args.append(tok)
    if not args:
        return set()
    names = {clone_basename(args[0])}
    if len(args) > 1:
        names.add(args[1].strip("/"))
    return names


def is_external_dir(tok):
    return bool(tok) and (tok.startswith(("/", "~", "$", "<", "%")) or re.match(r"^[A-Za-z]:[\\/]", tok))


def check_path_token(rel, lineno, tok, label, howto_rel):
    if not looks_like_repo_path(tok):
        return
    head = tok.split("/")[0]
    if tok.startswith(BUILD_OUTPUT_PREFIXES) or (head in MODULES and "target" in tok.split("/")):
        notes.append("%s:%d 按构建产物豁免（mvn install 之后才有）：%s" % (howto_rel, lineno, tok))
        return
    if head not in MODULES and not (ROOT / head).exists():
        fail("%s:%d 的「%s」引用路径 `%s` 在本仓库里不存在（顶层目录就没有）"
             % (howto_rel, lineno, label, tok))
        return
    target = (ROOT / tok)
    if "*" in tok or "?" in tok:
        parent = target.parent
        if not parent.exists():
            fail("%s:%d 的「%s」通配路径的父目录不存在：`%s`" % (howto_rel, lineno, label, tok))
        elif not any(parent.glob(Path(tok).name)):
            fail("%s:%d 的「%s」通配路径匹配不到任何东西：`%s`" % (howto_rel, lineno, label, tok))
    elif not target.exists():
        fail("%s:%d 的「%s」引用路径 `%s` 在本仓库里不存在" % (howto_rel, lineno, label, tok))


def count_gates():
    checks = ROOT / "devops" / "checks"
    gates = [p for p in checks.rglob("check_*")
             if p.is_file() and "__pycache__" not in p.parts and p.suffix in (".py", ".sh")]
    return sorted(gates)


# recheck 入口的"现状文档"范围：与门禁条数对账同一批文件
ENTRY_PATTERNS = ("README*.md", "doc/XinSync-*.md")
# clone 之后 `bash tools/fork-workflow.sh` 必然报 No such file —— 那一层在外层工作台目录里，
# 随 clone 交付不了。入口已搬进仓库（devops/fork-workflow.sh），旧写法不得残留。
STALE_ENTRY_RE = re.compile(r"(?:bash|sh|source)\s+tools/fork-workflow\.sh")

# 第 7 条（表格行闭合）的扫描范围：所有会被渲染成交付物的 markdown。
# 与 HOWTO/CLAIM 两组的取舍不同，这里**包含** CHANGELOG.md ——
# 查的是装订不是事实，接回断行不改变任何一句陈述的内容。
TABLE_DOC_PATTERNS = ("README*.md", "CHANGELOG.md", "doc/*.md", "doc/**/*.md")
# 行首的表格标记：允许前导空白（缩进表格在 GFM 里同样是表）
TABLE_ROW_RE = re.compile(r"^\s*\|")


def fenced_lineno_set(text):
    """任意 ``` / ~~~ 围栏**内部**（含围栏行本身）的行号集合。

    与 `fenced_lines` 的区别：那个按语言标筛过、只服务于命令扫描；这里要的是"全部代码样例"，
    因为 mermaid / ASCII 图里的 `|` 行不是表格，判它们断行就是误报。
    闭合判定与 `fenced_lines` 保持同一口径（同种标记、行首即闭合）。
    """
    out = set()
    marker = None
    for no, line in enumerate(text.split("\n"), start=1):
        m = FENCE_RE.match(line)
        if m:
            if marker is None:
                marker = m.group(1)
            elif line.strip().startswith(marker):
                marker = None
            out.add(no)
            continue
        if marker is not None:
            out.add(no)
    return out


def main():
    howto = sorted({p for pat in HOWTO_PATTERNS for p in ROOT.glob(pat) if p.is_file()})
    if not howto:
        sys.stdout.write("FAIL: 一份照做类文档都没扫到（README*/doc/XinSync-*/doc/datax-web/*deploy*.md），"
                         "门禁本身失效\n")
        return 1

    blocks_total = 0
    for path in howto:
        rel = path.relative_to(ROOT).as_posix()
        text = read(path)
        lines = fenced_lines(text)
        blocks_total += len(lines)
        has_docker_step = False
        in_repo_dir = True
        clone_dirs = set()
        for lineno, line in lines:
            # 先维护"当前目录"，再判这一行里的路径
            cd = CD_RE.search(line)
            if cd:
                target = cd.group(1).rstrip(".,;")
                if target in ("", "."):
                    pass
                elif target == ".." or target.startswith("../"):
                    in_repo_dir = False      # 往上跳就走出了仓库根，不再判
                elif is_external_dir(target):
                    in_repo_dir = False
                elif target in clone_dirs:
                    in_repo_dir = True       # clone 出来的就是本仓库
                elif (ROOT / target).is_dir():
                    in_repo_dir = True
                else:
                    in_repo_dir = False      # 相对但仓库里没有：由下面的路径判定报错
            clone_dirs |= clone_dir_names(line)
            for label, rx in PATH_REF_RES:
                for m in rx.finditer(line):
                    tok = m.group(1).rstrip(".,;")
                    if label == "cd 到目录" and tok in clone_dirs:
                        continue        # clone 出来的目录就是本仓库，名字不该按工作副本判
                    # 已经 cd 到仓库外的目录（部署目录等）时，这条命令里的相对路径属于
                    # **那台机器上的目录结构**，不按本仓库判存在性
                    if label == "cd 到目录" or in_repo_dir:
                        check_path_token(rel, lineno, tok, label, rel)
            if DOCKER_RE.search(line):
                has_docker_step = True
        if has_docker_step:
            if not any((ROOT / name).exists() for name in ("Dockerfile", "docker-compose.yml", "docker-compose.yaml")) \
                    and not list(ROOT.glob("*/Dockerfile")) and not list(ROOT.glob("**/docker-compose.y*ml")):
                fail("%s 把 docker/compose 当成可用步骤，但本仓库里没有任何 Dockerfile / docker-compose 文件"
                     "（Docker 部署不得写成可照抄的步骤）" % rel)
        # java -jar 需要 fat jar；本仓库不打 fat jar，除非 pom 里真有 spring-boot-maven-plugin。
        # 模式必须容得下"java 与 -jar 之间夹一堆 -Xmx/-XX 参数"的写法：
        # 上一版写的是 `java\s+\S*\s*-jar`（只允许一个中间 token），于是历史部署文档里
        # `nohup java -Xmx1024M -Xms1024M ... -jar xxx.jar` 整段静默躲过 —— 那是门禁自己的假绿。
        jar_lines = [(n, l) for n, l in lines if re.search(r"\bjava\b[^|;&]*?\s-jar\b", l)]
        if jar_lines:
            poms = list(ROOT.glob("**/pom.xml"))
            poms = [p for p in poms if "target" not in p.parts]
            if not any(BOOT_PLUGIN_RE.search(read(p)) for p in poms):
                fail("%s 里有 %d 行 `java -jar`，但全仓库 pom 没有 spring-boot-maven-plugin："
                     "产出的 jar 没有 Main-Class，照做必失败（必须走 tar 包 + bin/*.sh 启动）"
                     % (rel, len(jar_lines)))

    # 门禁条数三方对账：文档声明 == 实际文件数 == 工作流脚本的 MIN_GATES 默认值
    gates = count_gates()
    actual = len(gates)
    wf = ROOT / "devops" / "fork-workflow.sh"
    if not wf.is_file():
        fail("devops/fork-workflow.sh 不存在 —— recheck 的对外入口必须随仓库一起交付")
        declared_min = None
    else:
        m = MIN_GATES_RE.search(read(wf))
        declared_min = int(m.group(1)) if m else None
        if declared_min is None:
            fail("devops/fork-workflow.sh 里找不到 MIN_GATES 默认值，门禁数量下限失守")
        elif declared_min != actual:
            fail("devops/fork-workflow.sh 的 MIN_GATES 默认值 = %d，但实际门禁 %d 条 —— "
                 "加/删门禁必须同步这里（下限不等于实际条数，就等于允许无声删门禁）"
                 % (declared_min, actual))

    claims = []
    table_names = {}
    for path in sorted({p for pat in CLAIM_PATTERNS for p in ROOT.glob(pat) if p.is_file()}):
        rel = path.relative_to(ROOT).as_posix()
        text = read(path)
        for lineno, line in enumerate(text.split("\n"), start=1):
            # 只把"照抄的那一行复跑输出"里的小结数字摘出对账，其余部分（同一行里的表格单元格、
            # 别的现状声明）照常判 —— 整行跳过会把表格行一起吞掉。
            claim_text = GATE_OUTPUT_QUOTE_RE.sub(" ", line)
            for rx, ordinal_group, number_group in GATE_CLAIM_RES:
                for hit in rx.finditer(claim_text):
                    if ordinal_group == "TABLE":
                        table_names.setdefault(rel, set()).add(hit.group(1))
                        continue
                    if ordinal_group is not None and hit.group(ordinal_group):
                        continue                             # "第 N 个门禁" = 历史陈述
                    claims.append((rel, lineno, int(hit.group(number_group))))
    if not any(c[2] is not None for c in claims):
        fail("一份对外文档都没有声明门禁条数 —— 现状数字无人钉，第 5 条元规则失效")
    for rel, lineno, num in claims:
        if num != actual:
            fail("%s:%d 声明 %d 个门禁，实际 devops/checks 下是 %d 个 —— 对外数字必须实测归一"
                 % (rel, lineno, num, actual))
    # 门禁清单表格：集合相等（缺列=少报防线，多列=宣传已不存在的防线）
    actual_names = set(p.name for p in gates)
    for rel in sorted(table_names):
        names = table_names[rel]
        if len(names) < GATE_TABLE_MIN_ROWS:
            continue
        missing = sorted(actual_names - names)
        stale = sorted(names - actual_names)
        if missing:
            fail("%s 的门禁清单少了 %d 个（实际 %d 个）：%s —— 表不全等于对外少报了防线"
                 % (rel, len(missing), actual, "、".join(missing)))
        if stale:
            fail("%s 的门禁清单列了 %d 个仓库里已经不存在的门禁：%s"
                 % (rel, len(stale), "、".join(stale)))

    # recheck 入口必须指向仓库内脚本：README 让 clone 的人跑 `bash tools/fork-workflow.sh`，
    # 而 tools/ 在外层工作台目录、clone 下来根本没有 —— 空头支票
    entry_files = sorted({p for pat in ENTRY_PATTERNS for p in ROOT.glob(pat) if p.is_file()})
    good_hits = 0
    for path in entry_files:
        rel = path.relative_to(ROOT).as_posix()
        for lineno, line in enumerate(read(path).split("\n"), start=1):
            if STALE_ENTRY_RE.search(line):
                fail("%s:%d 让读者跑仓库外工作台目录的 `tools/fork-workflow.sh`，"
                     "clone 后该文件不存在，应改为 `bash devops/fork-workflow.sh recheck`" % (rel, lineno))
            if "devops/fork-workflow.sh" in line:
                good_hits += 1
    # 反空洞守卫：只删旧写法、不写新写法，读者同样找不到入口
    if good_hits == 0:
        fail("现状文档里一处都没提 `devops/fork-workflow.sh` —— 门禁入口没对外交付，"
             "读者无从复跑防线")

    # 第 7 条：表格行必须同一行闭合
    # 过滤条件必须建立在**相对**路径上。实测教训（第一遍就在反证沙箱里翻车）：
    # 上一版写的是 `"tmp" not in p.parts`，而 `p.parts` 含绝对路径前缀 ——
    # 沙箱本身位于 `<repo>/tmp/doccmd_sandbox/` 下，于是**整个沙箱树被当成草稿排除**，
    # 报"一个 markdown 都没匹配到"。真实树恰好不在 tmp 下，所以本地跑是绿的：
    # 典型的"门禁在工作副本里对、在交付/复现环境里瞎"。
    table_files = set()
    for pat in TABLE_DOC_PATTERNS:
        for p in ROOT.glob(pat):
            if not p.is_file():
                continue
            rel_parts = p.relative_to(ROOT).parts
            if "target" in rel_parts or "tmp" in rel_parts:
                continue
            table_files.add(p)
    table_files = sorted(table_files)
    if not table_files:
        fail("TABLE_DOC_PATTERNS 一个 markdown 都没匹配到 —— 表格闭合检查失效（glob 静默失配的老毛病）")
    table_rows = 0
    for path in table_files:
        rel = path.relative_to(ROOT).as_posix()
        text = read(path)
        skip = fenced_lineno_set(text)
        for lineno, line in enumerate(text.split("\n"), start=1):
            if lineno in skip or not TABLE_ROW_RE.match(line):
                continue
            if line.rstrip().endswith("|"):
                table_rows += 1
                continue
            # 只记 1 条：把 offending 内容并进同一条 FAIL，否则"违规处数"会被成倍虚高，
            # 而对外声明用的是处数。
            fail("%s:%d 表格行没有在同一行闭合（行尾缺 `|`）—— 这一行的单元格在渲染时全部错位，"
                 "下一物理行会被当成散文丢掉。 offending 行：`%s`"
                 % (rel, lineno, line.strip()[:120]))
    if table_rows == 0:
        fail("交付文档里一个闭合的表格行都没找到 —— 表格检查没有实际生效（要么表全被删了，要么判据写坏了）")

    sys.stdout.write("扫描照做类文档 %d 份（围栏内命令行 %d 行），实际门禁 %d 个\n"
                     % (len(howto), blocks_total, actual))
    for note in notes[:5]:
        sys.stdout.write("  注：%s\n" % note)
    if blocks_total == 0:
        fail("所有照做类文档里一个围栏代码块都没有 —— 提取规则失效，本门禁等于没跑")
        sys.stdout.write("FAIL: 提取不到任何命令行\n")
        return 1

    if failures:
        for f in failures:
            sys.stdout.write("FAIL %s\n" % f)
        sys.stdout.write("FAIL: 对外文档有 %d 处不合格（照做必失败的路径/命令，或损坏的表格行）\n"
                         % len(failures))
        return 1
    sys.stdout.write("PASS: 照做类文档 %d 份、%d 行命令行全部指向仓库里真实存在的路径；"
                     "门禁条数三方一致（实际 %d / MIN_GATES %s / 文档声明与表格 %s）；"
                     "交付文档 %d 份、%d 个表格行全部在同一行闭合\n"
                     % (len(howto), blocks_total, actual, declared_min,
                        "一致" if not any(c[2] not in (None, actual) for c in claims) else "见上",
                        len(table_files), table_rows))
    return 0


def _table_count(claims):
    from collections import Counter
    return Counter(rel for rel, lineno, num in claims if num is None).items()


if __name__ == "__main__":
    sys.exit(main())
