#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""部署包依赖判定的实现体（由 devops/checks/check_package_deps.sh 调用，本身不是门禁入口）。

为什么不叫 check_*：recheck 会自动发现并直跑 devops/checks/check_*，而这里判的是 packages/*.tar.gz，
必须先有一次全量构建才有东西可判 —— 产物从哪来、多久重跑一次由外面那层 shell 门禁决定。

为什么要有这条门禁（批次 10-K 的根因，第十九轮实测）：
上一轮把 logback 1.2.13 / log4j2 2.17.2 的 pin 从 `datax-admin/pom.xml` 上移到根 pom 的
`<dependencyManagement>`，执行器包里的旧版本才真的消失 —— 说明"**只有 `<properties>` 里的版本，
没有 `<dependencyManagement>` 条目，就管不住传递依赖**"。`netty` 当时只加了 property
（`netty.version=4.1.100.Final`，只被 `datax-rpc/pom.xml` 的直接声明引用），dM 里没有 io.netty 条目，
于是从 `datax-executor` / `datax-admin` 视角看 netty-all 是传递依赖，被这两个模块各自 import 的
`spring-boot-starter-parent:2.1.18.RELEASE` BOM 压回 4.1.53.Final。A/B 实测（tmp/evidence/10K-A/B-tree-*.txt）：
补 dM 之前 datax-admin 与 datax-executor 的树里是 `netty-all:jar:4.1.53.Final`，
补之后四个模块全是 `4.1.100.Final`。

但"补 dM"只做对了一半，这是本门禁第 5 条的来历：**netty-all 从 4.1.7x 起是空聚合 jar**
（实测 `netty-all-4.1.100.Final.jar` 里 0 个 `.class`），类全在 netty-buffer / netty-codec /
netty-common / netty-transport … 单模块里。netty-all 一升到 4.1.100，它带出的这三十几个单模块
就被 Boot 的 BOM 逐个钉回 4.1.53 —— 产出的包里 netty 家族两个版本并存
（tmp/evidence/10K-mixed-netty-inventory.json：admin 35 个、executor 34 个家族 jar，版本 {4.1.53, 4.1.100}）。
真正的修法是把 `io.netty:netty-bom` 排在 `spring-boot-starter-parent` 的 import **前面**
（同一个 pom 的 dependencyManagement 按声明顺序取先者；上面 logback 显式条目能生效是同一个机制）。
反面教训也实测过一次：给 netty-all 加通配 `<exclusion>io.netty:*</exclusion>` 挡掉单模块，
`datax-rpc` 立刻编译失败 `package io.netty.buffer does not exist` —— 空聚合 jar 挡掉模块等于挡掉全部类。

为什么不能只靠单测或只读 pom：单测跑的是 Maven 解析出来的 classpath，不是 assembly 打出来的 tar；
而上一轮的 `-pl datax-admin -am` 根本不构建 datax-executor / datax-assembly，
"改了声明、包没变"完全静默。只有读**产出的 tar** 才能发现。

七条判据（每条都有配套反证）：
  1) 根 pom 的 `<properties>` 里每个安全版本 pin 必须存在（否则后面全是空判）；
  2) 该 pin 对应的 groupId:artifactId 必须在根 pom 的 `<dependencyManagement>` 里有条目，
     且条目版本就是这个 property 或这个值 —— **只有 property 没有 dM 条目 = FAIL**（netty 的错法），
     两处各说一套也 FAIL；
  3) `packages/` 下必须真的找到 admin 与 executor 两个部署包，找不到就 FAIL，
     绝不因为"没有包所以没得查"报绿；包读断了也 FAIL（半写的 tar 会伪装成"包里没有"）；
  3b) 包必须比所有 `pom.xml` 新 —— 改完 pom 不重跑构建，包里读到的还是老结果，
     这条门禁会退化成"给旧包盖章"。上一轮"改了声明、包没变"就是靠这个时间差蒙过去的；
  4) 包里每个被跟踪的 jar，版本必须等于 pin；同名 artifact 在一个包里出现多个版本也 FAIL；
     某个包里一个被跟踪的 jar 都没查到 → 该包 FAIL（门禁自证，逐包判不被另一个包凑数）；
  5) netty 家族逐模块判版本：任何 `netty-<模块>-<版本>.jar` 必须是 pin 那个版本；
     某个包一个家族模块都没查到也 FAIL（netty-all 是空聚合 jar，没有模块 = 该包类路径空）；
  6) EOL/危险 jar 走**按包登记的存量台账**：包内实际命中的集合必须与台账逐条相等 ——
     多一条（新引进 log4j 1.x / netty 3.x）FAIL，少一条（真排掉了）也 FAIL，逼收口的人同步删台账，
     这样"已知残留"既不会假装不存在，也不会悄悄扩大。当前存量（实测，未收口，属待拍板 #10/S4）：
     `log4j:log4j:1.2.17` 与 `io.netty:netty:3.6.2.Final` 都在 datax-admin 包里，executor 包里都没有。
     盲目排除会让 DataX 的 HBase/Hive/Zookeeper 插件 NoClassDefFoundError，所以先登记、显式打在输出里。
"""
import re
import sys
import tarfile
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[2]
ROOT_POM = ROOT / "pom.xml"
PACKAGES_DIR = ROOT / "packages"

# (property 名, groupId, artifactId)：既钉 pom 的形状，也钉包里的文件名
TRACKED_PINS = [
    ("logback-classic.version", "ch.qos.logback", "logback-classic"),
    ("logback-classic.version", "ch.qos.logback", "logback-core"),
    ("log4j2.version", "org.apache.logging.log4j", "log4j-api"),
    ("log4j2.version", "org.apache.logging.log4j", "log4j-core"),
    ("log4j2.version", "org.apache.logging.log4j", "log4j-1.2-api"),
    ("log4j2.version", "org.apache.logging.log4j", "log4j-to-slf4j"),
    ("netty.version", "io.netty", "netty-all"),
]

PACKAGE_PATTERNS = ["datax-admin_*.tar.gz", "datax-executor_*.tar.gz"]
BUILD_CAP = "mvn -B clean install -DskipTests"
TRACKED_ARTIFACTS = set(a for _, _, a in TRACKED_PINS)
NETTY_PIN_PROP = "netty.version"

# EOL / 有 CVE 且尚未收口的 jar，按包名前缀登记存量
KNOWN_EOL_RESIDUE = {
    "log4j:log4j:1.2.17": {
        "pattern": r"^log4j-1\.2\.[0-9][^.]*\.jar$",
        "tars": ["datax-admin"],
    },
    # netty 3.x 经典版（多个 CVE 的承载体）。datax-admin/pom.xml 已排掉两条来路（io.netty:netty），
    # 包里仍然有 = 还有第三条来路（hadoop/hbase/zookeeper 一侧）。
    "io.netty:netty:3.6.2.Final": {
        "pattern": r"^netty-3\.[0-9A-Za-z.\-]*\.jar$",
        "tars": ["datax-admin"],
    },
}

# artifact 段必须**贪婪**：log4j-1.2-api-2.17.2.jar 要切成 (log4j-1.2-api, 2.17.2)，
# 非贪婪会切成 (log4j, 1.2-api-2.17.2)，于是被跟踪项永远对不上号。
JAR_NAME_RE = re.compile(r"^(?P<artifact>.+)-(?P<version>[0-9][0-9A-Za-z.\-]*)\.jar$")

# netty 家族（含带 classifier 的 native jar，如 netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar）
NETTY_FAMILY_RE = re.compile(
    r"^(?P<artifact>netty-[a-z0-9\-]+?)-(?P<version>\d+\.\d+\.\d+\.Final)"
    r"(?:-(?P<classifier>[A-Za-z0-9_\-]+))?\.jar$")

failed = []


def fail(msg):
    failed.append(msg)
    print("FAIL %s" % msg)


def pom_text():
    if not ROOT_POM.exists():
        fail("找不到根 pom：%s" % ROOT_POM)
        return ""
    return ROOT_POM.read_text(encoding="utf-8", errors="replace")


def dm_section(text):
    m = re.search(r"<dependencyManagement>(.*?)</dependencyManagement>", text, re.S)
    if not m:
        fail("根 pom 里没有 <dependencyManagement> 段，版本 pin 无从生效")
        return ""
    return m.group(1)


def property_value(text, name):
    m = re.search(r"<%s>\s*([^<\s]+)\s*</%s>" % (re.escape(name), re.escape(name)), text)
    return m.group(1) if m else None


def expected_versions(text):
    out = {}
    for prop, gid, aid in TRACKED_PINS:
        value = property_value(text, prop)
        if value:
            out[aid] = value
    return out


def check_pin_declared_and_managed(text):
    """判据 1 + 2。"""
    dm = dm_section(text)
    for prop, gid, aid in TRACKED_PINS:
        value = property_value(text, prop)
        if not value:
            fail("根 pom 的 <properties> 里没有 <%s>，%s:%s 的 pin 无从取值" % (prop, gid, aid))
            continue
        if not dm:
            continue
        entry = re.search(
            r"<dependency>\s*<groupId>%s</groupId>\s*<artifactId>%s</artifactId>\s*"
            r"<version>\s*([^<\s]+)\s*</version>" % (re.escape(gid), re.escape(aid)),
            dm)
        if not entry:
            fail(
                "%s:%s 只在 <properties> 里有版本（%s），<dependencyManagement> 里没有条目 —— "
                "传递依赖会被子模块 import 的 BOM（spring-boot-starter-parent）压成旧版；"
                "必须补一条 dM 条目用 ${%s}" % (gid, aid, value, prop))
            continue
        dm_version = entry.group(1)
        if dm_version not in ("${%s}" % prop, value):
            fail(
                "%s:%s 在 <dependencyManagement> 里的版本是 %s，与 <properties> 的 pin %s（${%s}）不一致 —— "
                "两处各说一套，包里的版本就取决于 Maven 的解析顺序" % (gid, aid, dm_version, value, prop))


def find_packages():
    out = []
    for pat in PACKAGE_PATTERNS:
        hits = sorted(PACKAGES_DIR.glob(pat)) if PACKAGES_DIR.exists() else []
        if not hits:
            fail("找不到部署包 packages/%s —— 依赖门禁没有实际生效，必须先跑全量构建产出包" % pat)
        out.extend(hits)
    return out


def jars_in(tar_path):
    try:
        with tarfile.open(str(tar_path)) as tf:
            return [m.name.split("/")[-1] for m in tf.getmembers()
                    if m.isfile() and m.name.endswith(".jar")]
    except (tarfile.TarError, EOFError, OSError) as e:
        fail("部署包 %s 读不出来（%s）—— 包可能没写完就断了，重跑构建后再判" % (tar_path.name, e))
        return None


def expected_eol_for(tar_name):
    return set(label for label, cfg in KNOWN_EOL_RESIDUE.items()
               if any(tar_name.startswith(prefix) for prefix in cfg["tars"]))


# 沙箱与构建产物不是判据对象：tmp/ 下的整树沙箱里有一份复制来的 pom.xml，
# 过滤必须按 **相对** 段走（绝对路径的 parts 同样含 "tmp"，在沙箱里会把整棵沙箱排除掉，
# 于是"最新 pom"取不到 —— 这是第 13 道门禁踩过的同一个坑）。
SKIP_SEGMENTS = ("target", ".git", "node_modules", "tmp", "build", "packages")


def check_freshness(tars):
    """判据 3b：包必须比所有 pom.xml 新，否则读包等于给旧构建盖章。"""
    candidates = []
    for path in ROOT.rglob("pom.xml"):
        if set(path.relative_to(ROOT).parts) & set(SKIP_SEGMENTS):
            continue
        candidates.append(path)
    if not candidates:
        fail("一个 pom.xml 都没扫到 —— 陈旧判定没有实际生效（扫描根或过滤条件写坏了）")
        return
    newest = max(candidates, key=lambda p: p.stat().st_mtime)
    newest_mtime = newest.stat().st_mtime
    stale = [p for p in tars if p.stat().st_mtime < newest_mtime]
    if stale:
        fail("包比 pom 旧：%s 早于 %s —— 改过声明没重跑构建，读包读到的还是上一版结果；"
             "重跑 %s 再来判（沙箱里跑本门禁请让 pom 早于合成包）"
             % ("、".join(p.name for p in stale), newest, BUILD_CAP))


def check_package_versions(all_jars, expected):
    """判据 3 + 4 + 5 + 6。"""
    netty_pin = expected.get("netty-all")
    matched = 0
    family_checked = 0
    for tar_name, jars in sorted(all_jars.items()):
        if jars is None:
            continue
        versions_seen = {}
        eol_hits = set()
        tar_matched = 0
        tar_family = 0
        for name in jars:
            fam = NETTY_FAMILY_RE.match(name)
            if fam and fam.group("artifact") != "netty-all":
                family_checked += 1
                tar_family += 1
                if netty_pin and fam.group("version") != netty_pin:
                    fail(
                        "%s 里的 %s 是 %s，而 netty pin 是 %s —— netty 家族版本混装："
                        "netty-all 升上去了，它带出来的单模块还被本模块 import 的 "
                        "spring-boot-starter-parent BOM 钉在旧版。修法是把 netty-bom 排在那个 BOM "
                        "前面（同一 pom 的 dependencyManagement 按声明顺序取先者）；"
                        "不能用通配 exclusion 挡单模块，netty-all 是空聚合 jar，挡掉等于挡掉全部类"
                        % (tar_name, name, fam.group("version"), netty_pin))
                continue
            m = JAR_NAME_RE.match(name)
            if not m:
                continue
            artifact, version = m.group("artifact"), m.group("version")
            if artifact in TRACKED_ARTIFACTS:
                matched += 1
                tar_matched += 1
                want = expected.get(artifact)
                if want is None:
                    fail("%s 里出现 %s，但门禁取不到它的 pin 版本" % (tar_name, name))
                elif version != want:
                    fail(
                        "%s 里的 %s 是 %s，与根 pom 的 pin %s 不一致 —— 构建没吃到 pin："
                        "检查是不是只有 <properties> 没有 <dependencyManagement> 条目，"
                        "或该模块 import 的 BOM 把它压回了旧版"
                        % (tar_name, name, version, want))
                versions_seen.setdefault(artifact, set()).add(version)
            # 台账模式对**每个包**都扫一遍，再与该包应有的集合比对。
            # 上一版只扫 `expected_eol_for(tar_name)` 里那几个标签，于是"在 executor 包里新引进
            # log4j 1.2.17"永远扫不到（executor 的应扫集合是空）—— 反证 D 当场跑出 rc=0 抓出来的洞。
            for label, cfg in KNOWN_EOL_RESIDUE.items():
                if re.match(cfg["pattern"], name):
                    eol_hits.add(label)
        for artifact, vers in versions_seen.items():
            if len(vers) > 1:
                fail("%s 在 %s 里有多个版本 %s（assembly 里出现重复 jar）"
                     % (artifact, tar_name, sorted(vers)))
        want_eol = expected_eol_for(tar_name)
        extra = sorted(eol_hits - want_eol)
        missing = sorted(want_eol - eol_hits)
        if extra:
            fail("%s 里出现台账之外的 EOL jar：%s —— 新引进的危险依赖，"
                 "要么排除掉，要么先写清理由再进 KNOWN_EOL_RESIDUE" % (tar_name, extra))
        for label in missing:
            prefixes = "/".join(KNOWN_EOL_RESIDUE[label]["tars"])
            fail("%s 里已经没有 %s，但台账的 tars（%s）还声明它有 —— 确实收口了就把这个包前缀"
                 "从该条里删掉，别让它变成'其实早没了'的假陈述" % (tar_name, label, prefixes))

        # 两条"采集量=0 必须自 FAIL"按**逐包**判，不按全局汇总判：
        # 汇总写法下，把 admin 包里的 netty 模块全删掉仍能靠 executor 包的 34 个模块凑出非零数，
        # 于是"某个交付物的类路径是空的"这一条恰恰是静默的（反证 L 抓的就是这个）。
        if tar_matched == 0:
            fail("%s 里一个被跟踪的 jar 都没查到（%s）—— 依赖门禁在这个包上空转，判据形同虚设"
                 % (tar_name, sorted(TRACKED_ARTIFACTS)))
        if tar_family == 0:
            fail("%s 里一个 netty 家族模块都没查到 —— netty-all 是空聚合 jar（实测 0 个 class），"
                 "类只能来自 netty-buffer/codec/common/transport 等单模块；家族数为 0 意味着这个包的 "
                 "RPC 类路径是空的。若确实改成按模块直依赖，请连同本条与 NETTY_FAMILY_RE 一起改写，别留空转"
                 % tar_name)
    return matched, family_checked


def main():
    text = pom_text()
    if not text:
        print("FAIL: 部署包依赖门禁未通过（读不到根 pom）")
        return 1
    check_pin_declared_and_managed(text)
    expected = expected_versions(text)

    all_jars = {}
    found = find_packages()
    check_freshness(found)
    for p in found:
        all_jars[p.name] = jars_in(p)

    matched, family = check_package_versions(all_jars, expected)

    if failed:
        print("FAIL: 部署包依赖门禁未通过（%d 条）" % len(failed))
        return 1
    print("PASS: 部署包依赖与根 pom 的 pin 一致（%d 份包、%d 个被跟踪 jar、%d 个 netty 家族模块版本统一；"
          "EOL 存量台账 %s 逐条对上，未扩大也未偷偷消失）"
          % (len(all_jars), matched, family, sorted(KNOWN_EOL_RESIDUE)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
