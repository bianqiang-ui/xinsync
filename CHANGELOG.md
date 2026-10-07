# Changelog — XinSync 信数通

所有重要变更记录在此文件中。格式基于 [Keep a Changelog](https://keepachangelog.com/)。

> **本文件在 2026-10-03 做过一次全量对账**（第十八轮，任务 #40；统计数字由 `tmp/draft/fill_changelog.py`
> 现场从 git 与文件系统数出来，不在文档里手抄）。对账原因是上一版里有
> **四处对外假陈述**和**一整张错配的 Issue 表**：
> ① "分页查询根据用户角色自动过滤（非 admin 仅见自己的数据）" —— 实际 `JobInfoController#pageList`
>    把 `userId` 硬写成 `0`，`JobInfoMapper.xml` 的 `<if test="userId gt 0">` 永不成立，读侧根本不过滤；
> ② "`DataxJsonController.buildJobJson` 增加 `AccessControl.adminDeny()` 守卫" —— 该守卫已被撤销
>    （它把普通用户的建作业向导整个关掉了），现在这个类里没有任何鉴权判定；
> ③ "`denyGlueScriptIfNotAdmin()` 限制脚本型任务" —— 主代码 0 命中，真实实现是
>    `security/GlueScriptAccess`（白名单：只放开 `BEAN`）；
> ④ "netty 已升到 4.1.100.Final" —— pom 里确实 pin 了，但第十八轮把 `mvn install` 产出的两个 tar 包
>    逐个 `tar -tzf` 列过，**包内实际是 `netty-all-4.1.53.Final.jar`**，所以这句在交付物层面不成立，
>    当时改写成"pom 已 pin / 包内 4.1.53（未收口，批次10-K）"。**第十九轮批次 10-K 已收口**：
>    根 pom 补 `io.netty:netty-all` 的 `dependencyManagement` 条目 + 两个消费模块把 `netty-bom`
>    排在 Boot BOM 前面，重建后两个包内 netty 家族（`netty-all` 与它带出的 34 个单模块）全部 4.1.100.Final，
>    并由第 14 道门禁 `devops/checks/check_package_deps.sh` 读 tar 钉住。
> 另外原表把 #587/#652/#698/#389/#348/#265/#444/#487/#672/#632/#478/#492/#336/#512 的**主题写串了**
> （例如把 #487 写成"进程树残留"、把 #389 写成"Hive Kerberos"）。以下每一条都以本仓源码与实测结论为准。

---

## [2.1.2-xinsync] — 2026-10-03

基于 DataX-Web v2.1.2 的深度安全加固版本。

### 🔒 安全修复

#### CRITICAL

- **S1 RPC 反序列化 RCE**（社区 #587 / CVE-2022-46478）— 执行器 RPC 端口原先零鉴权，Hessian 反序列化可达 RCE。
  新增 `WhitelistSerializerFactory` 只放白名单类，`datatx.job.accessToken` 两侧强制校验且默认 fail-closed。
  ⚠️ **公开 PoC（ysomap）端到端复测未做**（缺攻击环境），档位是"代码面收口 + 单测"。
- **S2 元数据接口 SQL 注入**（社区 #698）— `/api/metadata/getColumnsByQuerySql` 的 `querySql` 可注释绕过 `where 1=0`。
  `buildLimitSql` 改为只允许 SELECT、拒多语句，控制器不再回显数据库错误原文。
- **S3 命令注入**（代码通读 + 社区 #552 相关）— `JobParamSafety` 双关口：入库校验 + 执行器拼命令前二次校验。
  判据是"最终会进 `datax.py` 的 `-p`/`-j` 的字段一律过同一份 shell 元字符白名单"，**日期格式串也算**
  （SimpleDateFormat 里的非字母字符是字面量，会活着穿过 `format()` 回到命令行）。
- **S7 越权（IDOR）**（代码通读，社区零星"越权"抱怨）— `security/AccessControl` 单点实现"角色 + 归属"双层判定，
  归属只从库里那一行取、不回信请求体；33 条接缝由 `check_authz_seams.py` 逐条闭环判定。
- **S15 GLUE 脚本 RCE**（代码通读）— 任何登录用户存一段脚本等于在执行器主机执行任意代码。
  收口在 `security/GlueScriptAccess`，口径是**白名单"只放开 `BEAN`"**，五个 `glue_type/glue_source` 写入口全覆盖。
  > 为什么不按 `GlueTypeEnum.isScript()` 判：`GLUE_GROOVY` 的 `isScript` 是 **false**，
  > 而执行器会对它 `GroovyClassLoader.parseClass` —— 按 `isScript` 筛漏掉的正是任意代码执行那一类。

#### HIGH

- **S5 Log4Shell**（社区 #504/#674）— `dependency:tree` 证实 admin 经 `hive-jdbc` 带入 `log4j-core 2.11.2:compile`
  （不是误报），已在根 `dependencyManagement` 逐条压到 **2.17.2**。残留 `log4j:1.2.17`（hadoop-hdfs 带入）待 Hive/HBase 实测后再排。
- **S6 依赖面**（社区 #599/#652/#587 相关）— fastjson 1.2.83、hessian 4.0.66、
  logback 1.2.13、mysql-connector 8.0.33。**Boot 2.1.4→2.1.18**；Boot 2.1.x 仍锁 spring-security 5.1.x（EOL），
  彻底解需 2.7 + springdoc，属独立批次，需拍板。
  ✅ **netty 这句在第十九轮批次 10-K 起在包层面也成立**：根 `pom.xml` 的 `<dependencyManagement>` 补了
  `io.netty:netty-all` 条目（版本取 `${netty.version}`），`datax-admin` / `datax-executor` 把
  `io.netty:netty-bom` 的 import 排在 `spring-boot-starter-parent` **前面**——同一个 pom 的
  `dependencyManagement` 按声明顺序取先者，排在后面就等于没写。重建后 `tar -tzf` 实测：
  admin 包 301 个 jar、executor 包 81 个 jar，两个包里的 netty 家族（含 `netty-all` 带出的 **34 个单模块**）
  **全部 4.1.100.Final**。这一层的来历值得记一笔：`netty-all` 从 4.1.7x 起是**空聚合 jar**
  （实测 4.1.100 那个包 4.4 KB、**0 个 `.class`**），类全在单模块里，所以"只升 netty-all"会得到一个
  家族版本混装的包；而用通配 `<exclusion>` 挡掉单模块会当场编译失败（`package io.netty.buffer does not exist`）。
  这就是 #32 的门禁判据定为**读 `packages/*.tar.gz` 里的 jar 名**而不是读 pom 的原因。
  ⚠️ 结论只到"包内版本与 pin 一致"，**没有**跑真 RPC 端到端冒烟，不得称"运行时已验证"。
- **S8 排序/筛选标识符注入**（代码通读）— `SqlSafeIdentifier` 单点校验 `ascs`/`descs`/查询 key，
  ORDER BY 组装点必须唯一，`JobRegistryController` 里抄的那份重复实现已收回。
- **S9 口令泄漏 - API 响应** — 三个数据源读出口回**固定掩码** `******`（不是 null：前端把口令定为必填，
  回空会把编辑弹窗卡死）；`update()` 把"空 / 掩码 / 与库里逐值相同"三种都解释为"本次不改口令"。
- **S10 口令泄漏 - 日志与 RPC 出口** — `SensitiveLogMask`（唯一实现处，在 `datax-rpc`）在**值的源头**遮蔽：
  `TriggerParam.toString`（含已解密明文 jobJson 与 glueSource）、`XxlRpcRequest.toString`（含 accessToken 与 parameters）、
  `XxlRpcFutureResponse` 超时消息（原样把 request.toString() 拼进异常，最终落进 `job_log.trigger_msg` 列）、
  以及 `JwtUser`/`LoginUser`/`JobDatasource` 三个实体的 `toString`。`JobTrigger.sanitizeTriggerMsg` 退为**第二层**。
- **S16 job_json 不含凭据**（代码通读）— reader/writer 只写 `@@DATAX_DS_USER:<id>@@` / `@@DATAX_DS_PWD:<id>@@` 引用，
  明文只在派发那一刻按 id 现查现填；还原不了就**可见失败且不下发**。因此 `/api/dataxJson/buildJson` 不需要管理员判定
  （加了会把普通用户的建作业向导关掉）。顺带修好一条老 bug：MongoDB 写的是 `userName`/`userPassword`，
  原还原逻辑只认 `username`/`password`，Mongo 任务一直在把密文当口令发出去。
- **S3 存储型 XSS**（社区 #652）— 账号字符集/长度正则校验 + 出参抹掉密码哈希（按 xxl-job 官方补丁口径）。

#### MEDIUM

- **S11 口令泄漏 - 执行器临时文件** — `jobTmp-<uuid>.conf` 改为**创建即 0600**（`PrivateTmpFiles`，
  POSIX 上把 `rw-------` 作为创建属性在同一次 `FileChannel.open(CREATE_NEW, asFileAttribute)` 给出；
  非 POSIX 退回"先建后改"并打 `[SECURITY]` 告警），且 `cleanStaleTmpFiles()` **真的被调用**
  （执行器 `@PostConstruct` 启动清理，带 `staleMinutes` 阈值 1440、`enabled` 逃生口、本 JVM 引用表跳过）。
- **S12 JWT 硬编码密钥** — `datax.jwt.secret: ${DATAX_JWT_SECRET:}`，不再内置可用密钥。
- **S13/S14 日志与依赖组件统一** — 版本 pin 上移到根 POM `dependencyManagement`。
  第十八轮用 `mvn -B clean install` 产出 tar 后 `tar -tzf` 逐项核过，**日志组件这一半是真的**：
  执行器包与管理员包内都是 `logback-classic/core 1.2.13` + `log4j-api 2.17.2`
  （不再是历史报障里的 1.2.3 / 2.11.2）。第十九轮批次 10-K 又核了一次，这次核的是**产出的 tar**：
  被跟踪项（logback-classic/core 1.2.13、log4j-api/core/1.2-api/to-slf4j 2.17.2、netty-all 4.1.100.Final
  与 netty 家族 34 个单模块）在两个包里**全部与根 pom 的 pin 一致**。
  **仍有两项 EOL 残留在管理端包里**（第十九轮 `tar -tzf` 实测）：
  ① `log4j-1.2.17.jar`（hadoop 一侧带入，与 2.17.2 的 `log4j-1.2-api` 并存）；
  ② `netty-3.6.2.Final.jar`（同为传递依赖，`datax-admin/pom.xml` 已排掉两条来路仍剩第三条）。
  执行器包里两项都没有。排除动作要等 Hive/HBase 实测条件，属待拍板 #10 / S4；
  这两条现在以**逐包台账**被第 14 道门禁钉住（多一条判"新引进危险依赖"、少一条判"台账失真"），
  所以准确口径是"已登记、不会悄悄扩大"，**不是**"已清除"。
  ✅ 因此"部署包依赖版本已锁定"这句从第十九轮起**只对被跟踪项成立**，且是由读 tar 的门禁钉的，
  不再是一句没有防线的声明。

#### 部分修复 / 未收口（不得对外称已完成）

- **S4 Spring Boot EOL** — 已识别，需 2.1→2.7+ 大版本升级，影响前端与 doc.html，待拍板。
- **S6 AES 默认密钥** — 支持环境变量覆盖，但 `datasource.aes.key` 仍有出厂默认值；
  换 AES/GCM + 强制配密钥属破坏性变更，**待拍板 P5**（要配存量密文迁移脚本）。
- **读侧多租户（待拍板 P7）** — 列表接口仍不按归属过滤：`JobInfoController#pageList` 传 `userId=0`，
  mapper 遇 0 跳过过滤。**对外不得称"已实现多租户隔离"**。
- **元数据浏览接口（待拍板 P6）** — 六个 GET 只有登录判定；普通用户建作业要浏览库表列，
  直接收归管理员会砍掉作业编辑能力，正解是"数据源可见性"模型。
- **历史数据** — 库里 `job_log.trigger_msg` 的**历史行**仍可能含明文口令，本轮只保证"从现在起不再写入"；
  存量清理需真实库执行并验证，尚未做。

### 🐛 社区 Issue 修复

| Issue | 社区反馈的主题 | 本 fork 的处置 | 验证档位 |
|-------|--------------|---------------|---------|
| #587 | RPC 无鉴权 → Hessian 反序列化 RCE（CVE-2022-46478） | `WhitelistSerializerFactory` + accessToken 两侧 fail-closed | 代码面 + 单测；**PoC 端到端未复测** |
| #698 | `getColumnsByQuerySql` 可注释绕过 → SQL 注入 | 只允许 SELECT、拒多语句、不回显驱动错误 | 单测 |
| #652 | 新增用户存储型 XSS | 账号字符集/长度校验 + 出参抹哈希 | 单测 |
| #599 | spring-security-core 5.1.5 CVE | Boot 2.1.4→2.1.18（2.1.x 锁 5.1.x，彻底解需 2.7） | 部分 |
| #504/#674 | log4j2 漏洞告警 | `dependency:tree` 实测确认带入 2.11.2，压到 2.17.2 | 依赖树实测 |
| #265 | 添加 HBase 数据源报错 | 根因字节码级确认：`hbase-protocol` 被 exclude 掉，删除该 exclude | **仍缺真实 HBase 集群端到端复测** |
| #296 | Hive 连接 `Method not supported` | Hive 显式 `SHOW DATABASES` 校验 + 失败降级 `show tables` + 缓存连接 `isValid` 异常不再带崩构造 | 假驱动 + 真 HikariCP 复现验证（PASS）；连真 Hive 1.1 待环境 |
| #389 | 定时任务不触发/执行 | 两条根因已修：调度循环缺 per-item 隔离；`job_json` 不全时 `changeJson` NPE 逃进触发线程池导致零日志 | 容器内正反控实测 |
| #348 | 相同子任务重复执行 | **只收数据面那半**：任务超时/被打断时 DataX JVM 变孤儿继续写同一批表 → 改整树回收 | 容器内 A/B 实测；控制面竞态未复现，属观察项 |
| #250 | Oracle 取不到 schema/视图名/字段 | `all_*` 视图系改写 + 标识符防护 | **无真实 Oracle 实例，未实测** |
| #336/#512 | python 路径错误 / 找不到 python | `datax.executor.python` 可配 + `datax.py` 缺失时抛带指引异常 | 单测 + 启动实测 |
| #444 | `logback.xml` 配置错误 | 已修 | 启动实测 |
| #492 | 找不到主类 `com.alibaba.datax.core.Engine` | 部署文档收口（必须 `mvn install` 取 tar 包 + `DATAX_HOME` 定位） | 文档 |
| #632/#478 | 部署后登录报账号密码错误 | SQL 脚本标注真实明文 + 启动检测初始哈希打 ERROR；**实测初始口令是 admin/123456，不是社区传的 12345** | BCrypt 实测 |
| #552 | SqlServer 时间增量转换失败 | **本 fork 暂未改**：需把 `-p` 改成逐 argv 传参并端到端验证，本地无 DataX 实例可测 | 待复测 |
| #630 | 总限速下 channel bps 计算非法 | **本 fork 暂未改**：需与前端限速输入校验协同，单边改会两头不一致 | 待设计 |
| #487 | 执行器输出读取（stderr 管道）导致任务挂住 | `errThread.start()` 必须先于 `futureTask.get()`，否则子进程写满 stderr 缓冲后父子互等 | 由 `check_executor_streams.py` 钉住 |

**本 fork 额外发现并修好的（无社区 Issue 编号）**：
- 端口口径三处各写一遍且 yml 里 `${server.port}` 无默认值 → 脱离脚本 `java -jar` 直接抛
  `Could not resolve placeholder`；同名自引用还会触发 `Circular placeholder reference`。
  口径统一为 admin **9527** / executor web **9504** / RPC **9999**，由 `check_ports.py` 钉住。
- 启动脚本 `status_class()` 只信 `jps`，而 `jps` 会列出僵尸 JVM → `start` 报"已启动"却不启动、
  `stop` 空等 20s。改为 `alive_pid()`（`kill -0` + `/proc/<pid>/status` 的 `State` 非 `Z`）。
- `I18nUtil` 在非 Spring 环境取 `JobAdminConfig` 会 NPE（报错比被报的错先炸）→ 加空判退回默认语言文件。

### 🏗️ 基础设施

- **15 道自动化质量门禁**（`devops/checks/`，随仓库交付，clone 后可直接复跑）：
  `check_yaml.py`、`check_ports.py`、`check_executor_streams.py`、`check_authz_seams.py`、
  `check_sql_identifiers.py`、`check_job_param_safety.sh`、`check_datasource_secret_scrub.py`、
  `check_log_secret_mask.sh`、`check_rpc_access.sh`、`check_executor_tmpfile.sh`、`check_doc_secrets.py`、
  `check_doc_commands.py`（十条规则，含“交付文档的表格行必须同一行闭合”“对外改动数字必须按锚点与 git 实测一致”）、`check_package_deps.sh`、`check_admin_tests.sh`、`check_tdsql.sh`
- **统一入口搬进仓库**：`bash devops/fork-workflow.sh recheck`（此前 README 指的 `tools/fork-workflow.sh`
  在工作台目录里，clone 下来不存在 —— 是一张空头支票，现已由 `check_doc_commands.py` 反向钉住）
- 第 13 道门禁的**十六条反证 A–P**全部成立（整树沙箱每轮重建，判“成立” = 基线 rc=0 且改坏 rc≠0 且输出点名到本次规则）；K 拆表格行、L 掏空表格判据，一抓一放；
  M–P 是本轮新加的第 10 条规则自己那四条（M 改一个数字、N 把锚点换成不存在的 sha、O 把三份文档的声明**全部**抹掉证明规则会自曝“没有输入”、P 让 git 解析不出仓库证明“量不到”是红灯而不是静默跳过）。
  P 的第一版写的是“删掉沙箱的 .git”，实测**不成立**（rc 仍为 0）：`git -C 沙箱` 会向上走到外层仓库继续量 —— 改成把 gitfile 指向不存在的目录才真的量不到。
  同轮的一次性清点 `tmp/draft/audit_inline_paths.py`（**未进门禁** —— 行内反引号里的“像路径的串”绝大多数是解包后目录、列名/方法名缩写或故意的错误示例，判据一宽就天天红灯）抓出并改正了一处本轮自己写错的代码定位（包根与 mapper 目录名）。
- 第 14 道门禁 `check_package_deps.sh`（判定体 `lib_package_deps.py`）判的是**产出的 tar**而不是 pom，**十二条反证 A–L 全部成立**（`tmp/evidence/falsify-pkg-deps-{A..L}.txt`；沙箱里的合成 tar 逐字抄真实部署包的 jar 文件名清单，跑的是外层 shell 门禁，所以“缺产物”那条分支也在范围内）；`MIN_GATES` 同步 13→14。
  第一遍 **D 不成立**，抓出的是门禁自己的洞：EOL 残留原先只按“该包应有的标签”去匹配，于是“在 executor 包里新引进 `log4j-1.2.17.jar`”永远扫不到（executor 的应扫集合是空）—— 改成对每个包扫全部台账模式再与应有集合比对后 D 才真的红。
  同一轮的教训还有两条：`netty-all` 4.1.100 是**空聚合 jar**（0 个 `.class`，类在 34 个单模块里，所以只升 netty-all 会得到家族混装的包），以及“包必须比 pom 新”这条判据（改完声明不重跑构建，读包等于给旧构建盖章）。
- 门禁判据的三条元规则：发现方式是递归 `find`（glob 静默失配会假绿）、数量下限 `MIN_GATES` 等于实际条数
  （删门禁必须当场红灯）、`SKIP_MVN_GATES` 的结果是 PARTIAL 且退出码非 0（部分复跑不得冒充全绿）
- Shell 脚本统一 LF 换行符（`.gitattributes` 强制）
- 49 个测试类；管理端回归 129 条用例，core/executor/tdsql/临时文件/遮蔽/RPC 令牌/登录请求体/分片切片各条链路都有行为用例
- 门禁入口随仓库交付：`bash devops/fork-workflow.sh recheck` 一条命令复跑全部 16 道门禁
- Docker 镜像 `maven:3.8-openjdk-8` 仅用作**构建与验证环境**（本仓库不提供 Dockerfile / docker-compose，
  README 里原来的 "Docker 部署" 步骤照做必失败，已删除并如实说明）
- Assembly 打包输出 `packages/datax-admin_2.1.2_1.tar.gz`、`packages/datax-executor_2.1.2_1.tar.gz`、
  `build/datax-web-2.1.2.tar.gz`（**必须 `mvn install`，`package` 不产出**）

### 🔧 批次11-T0/T1 — 2026-10-03 复审收口

外部复审（逐条复核本仓声明）点出三件事，本轮全部按"实测 → 修 → 反证"处理：

- **TDSQL 改写产物建表必失败（已证实，非"可能"）**：`TdsqlDdlRewriter` 把 `NOT NULL` 插在 `DEFAULT`
  之前，对 mysqldump 的标准产出 `` `uid` bigint DEFAULT NULL `` 会得到 `NOT NULL DEFAULT NULL`。
  复审当时标注"是否报错未实测"，本轮把改写产物逐条喂给真实 MySQL **8.0.46**（默认 `STRICT_TRANS_TABLES`）：
  **3 条 `ERROR 1067 Invalid default value`**。修法：补非空时**同时摘掉 `DEFAULT NULL`**，
  并把语义变化写进 notes（"不填这一列"从写入 NULL 变成报错；历史 NULL 行必须先在库里补非空，否则 ALTER 失败）；
  显式默认值 `DEFAULT '7'` 原样保留，不被误删。
- **同一文件的 `contains("NOT NULL")` 误判**：列定义里 `COMMENT '这里写着 NOT NULL 也只是注释'`
  会被当成"该列已非空"而漏补 `NOT NULL`；实测该表因分片键进了主键 ⇒ **`ERROR 1171 All parts of a
  PRIMARY KEY must be NOT NULL`**，同样是建表期硬失败。修法：改用按词边界、跳过引号区的
  `indexOfTokenOutsideQuotes()`，`AUTO_INCREMENT`/`DEFAULT`/`COMMENT` 的定位共用同一个函数。
  两条都由新增单测钉住（当时 `check_tdsql.sh` 跑 12 条；批次11-T2 之后是 22 条），反证三段式：实现退回修复前 → 门禁红
  （`Tests run: 12, Failures: 2`，点名的正是这两条测试）→ 换回修复版 → 12 条全绿。
- **对外统计数字第三次对不上，这次把"数字"本身交给机器量**：README 曾写 46/135/14,800、
  CHANGELOG 曾写 139/+15,468，而 `git diff --shortstat` 实测是另一组值。根因有两层：
  ① 参照物用了**维护者本地的台账分支**（没随 fork 推送，别人 clone 下来命令根本跑不出来）；
  ② 终点写成 `HEAD` ⇒ "携带这组数字的提交"自己会被算进去，谁跑都比文档大一点（自指失配）。
  现在：起点固定公开 tag `v-2.1.2`、终点写成**对账锚点 sha**、提交数按作者隔离，
  并由第 13 道门禁新增的**第 10 条规则**现场跑 git 重量（改错数字、锚点不存在、声明被整体删光、
  git 解析不到仓库，四种情况都会红灯）。`devops/fork-workflow.sh diff` 同步改为：
  本地没有台账分支时退回公开 tag 并打印口径提示，陌生 clone 里不再报"先执行 baseline 子命令"。

### 🔐 批次12（10-B）— 2026-10-04 执行器 RPC 端口的两条入口收口

执行器在 9999 端口上跑的是 netty_http，它有**两条**入口，而历史上只有第一条过令牌：

- **`/services` 匿名可读**（已证实，不是推测）：`NettyHttpServerHandler` 在 `"/services".equals(uri)` 分支里
  直接把 `getServiceData()` 整张表拼进响应，**判定一行都没有** —— 匿名 GET 就拿到"这台执行器暴露哪些 RPC 接口、
  由哪个 Bean 实现"，是一张现成的攻击面地图。它不在 Spring 过滤器链上，管理端那 33 条授权接缝门禁一条都管不到它。
  修法：**先判定、后拼清单**；未授权回 `403` 且响应体里一个服务名都不出现。令牌走请求头
  `X-Xxl-Rpc-Access-Token`，**不走 URL 查询参数**（进 query 就会落进 nginx / 代理 / 浏览器历史，
  而这条接口本来就是给人 `curl` 排查用的）。
- **判定收到唯一实现处** `datax-rpc/.../util/RpcAccessDecision.java`：`invokeService` 与 `/services` 都委托它，
  工厂里那份内联的 `accessToken.trim().equals(...)` 三段式已回收（同一常量在两处各写一遍，是本项目反复吃过的那种漂移）。
  拒绝措辞沿用历史值，`datax.rpc.allowEmptyAccessToken` 逃生口的语义与默认值（false）都不动 ——
  旧部署显式打开它时清单照旧可得，守卫不替运维改部署语义。
- **第 15 道门禁 `devops/checks/check_rpc_access.sh`**：形状侧钉"只有一份判定 / 两条入口都委托 /
  判定行必须早于拼清单的行 / 纯 netty 通道不得自己读服务表"；行为侧 `RpcAccessDecisionTest` 5 条 +
  `ServiceListingAccessTest` 5 条（用 `EmbeddedChannel` 喂真 `FullHttpRequest`、读真出向响应，
  断言状态码与响应体内容，不看代码里有没有那行 `if`）。`MIN_GATES` 同步 14→15，测试类 42→44
  （口径当场量：`find <各模块>/src/test/java -name '*Test.java'` 递归计数；
  不计 1 个 `*Tests.java` 与 4 个放在测试目录里的工具类。逐模块的实测条数只写在"当前状态"那条里，
  历史条目不留旧值 —— 留了就会在下一次改动后变成假陈述，而这正是本条判据要防的事）。
- **顺序判据为什么单独写**：只 grep"判定和拼清单两处都存在"是假绿通道 —— 把判定挪到拼完清单之后再 `return`，
  泄漏照旧发生，两条 grep 依然全绿。所以门禁比的是**行号先后**。

### 🧩 批次11-T2 — 2026-10-04 TDSQL DDL 改写器的四条产出正确性返修

外部复审点出的 T0.5 四条（全部无需实例即可修）逐条收口。共同特征是**产物看着成功、建表时炸或漏改**，
比不改更危险，所以每条都配"逐字符断言 + 改坏即红"的反证：

- **表类型互转必须真的换掉子句**：旧实现只在"没有子句"时追加，于是
  SHARD→BROADCAST 留下原来那条 `SHARDKEY = 分片键列`（说明写广播表、DDL 还是分片表），
  SHARD→SINGLE 同样留着子句（"单表"其实还分布）。现在 `setClause()` 按现有子句的**值**比对：
  相同则逐字保留（幂等前提），不同则整段替换；`dropClause()` 在单表时把子句连同前导空白一起去掉。
  小写、不带反引号的历史写法（`shardkey=uid`）会被归一而不是又追加一条 —— 重复 `SHARDKEY` 直接语法不合法。
  复合分片键（`SHARDKEY =` 后面跟括号列清单的写法）**不猜**：明确报错并还原文，猜一个值等于换掉别人的分片键。
- **注释不是代码**：`-- ` / `#` / 块注释此前三处都没跳过 —— 括号配对会把行注释里未配对的 `(` 当表体括号
  （结果整段定位失败或定位到错误位置），顶层逗号切分会被注释里的逗号切开，关键字定位会把注释里的
  `SHARDKEY = xxx`、`DEFAULT NULL` 当成真的表选项。现在共用同一个 `skipComment()`/`skipIgnorable()`，
  配对、切分、定位三个扫描器口径一致。
  反面教训记一条：`firstContent()` 最初连反引号标识符一起跳过，于是**每个列名都"不存在"** ——
  引号内容是数据不是注释，跳东西的粒度必须按用途分开，不能复用同一个"扫描器"。
- **入口只接受单条 `CREATE TABLE`**：`ALTER TABLE`、`CREATE INDEX`、`CREATE TABLE … LIKE`、
  `CREATE TABLE … SELECT`、以及分号之后还有内容的**多语句脚本**，全部明确报错并把原文还给调用方。
  上一版对多语句是"只改最后一条却把整份还回去"，操作者以为整份都过了改写。
  多语句的**逐条改写**没做：本工具的定位是"一次一张表"，批量编排要等接进生产入口（见下方档位）再说。
- **实测**：`check_tdsql.sh` 由 12 条扩到 **22 条**，基线 `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`。
  反证六条腿 A–F 全部成立（把实现逐条退回修复前 → 门禁 rc=1 且点名本次用例 → 还原逐字节一致 → 复跑回绿）：
  A 只追加不替换 / B 注释当代码 / C 入口校验关掉 / D 复合分片键靠猜 / E 单表不删子句 / F 子句掉到分号之后。
  证据 `tmp/evidence/falsify-tdsql-{A..F}.txt` 与两份汇总（A–F 一轮、修驱动后 D 腿端到端一轮）。
- **驱动自身的一个缺陷（不算门禁的洞，但要说清）**：反证脚本在末尾把返回 bool 的函数按元组解包，
  六条腿的结论都落盘了，**汇总行却没写**。修法是让收尾统计进 `finally`，并重跑 D 腿验证端到端能收口。
- **能力档位（对外措辞按这版）**：`TdsqlDdlRewriter` 当时**没有生产调用方**，只有单测在跑。
  所以"③对原程序代码自动改造"仍是**规则就绪、接线未做、无 TDSQL 实例验收**，不得称已完成。
  （这一档由批次14 改变，最新措辞见批次14 一节。）

### 🧹 批次13（#47）— 2026-10-04 第 11 条判据：测试类数量交给门禁现场数文件

本轮批次11-T2 写文档时又踩了同一块砖：测试类条数先写成了 46，当场 `find` 量出来是 44。
改动统计有第 10 条钉（跑 `git` 按锚点重量），测试类数量此前**没有任何机器判据**，
所以本轮把第 47 项做成 `devops/checks/check_doc_commands.py` 的**第 11 条判据**。
它不新增门禁文件，`MIN_GATES` 仍是 15。

- **钉三类数字，口径只有一份实现**（`measure_test_classes()`）：各模块 `src/test/java` 下递归的
  `*Test.java` 条数。模块**按"顶层目录里真有 `src/test/java`"自动发现**，不写死清单 ——
  写死了新增模块会静默漏计。它比的值永远是**当场量到的那一个**，所以文档里的条数只能跟着仓库走：
  共 **49 个测试类**，逐模块 admin 39 / core 6 / executor 2 / rpc 2；不计 1 个 `*Tests.java`
  与 4 个放在测试目录里的工具类，这两个排除口径文档里写了也同样对账。
- **三点边界写在判据里**：跳过 `target/` 与 `tmp/` 里的拷贝（构建产物和反证沙箱各有一整份测试树，
  计进去数字直接翻倍，而"翻倍"看起来比"少一个"更像真的）；`… | wc -l` = N 这种"把复跑命令抄进文档"
  的写法要求同一行真的出现 `*Test.java` 才采信，否则会把 `git log … | wc -l` 的提交数抢过来判错；
  `测试类 42→44` 这种 A→B 迁移句是历史陈述（记的是"那一批从几抬到几"），与"第 N 个门禁"同理不参与对账。
- **两个反空洞守卫**（本仓同类缺陷已经犯过六次，这次一并钉）：文档里一处测试类声明都没匹配到 ⇒ FAIL；
  逐模块口径解析不出**至少 2 条** ⇒ FAIL。第二条是必要的：只钉总数时，"漏掉一个模块"和"同时漏加漏删"
  都能对上总数，而正则形状一变就会静默解析成 0 条 —— 那正是上一批 `grep` 命中 import 行的同一类自空洞。
- **不钉 `管理端回归 101 条用例`**：用例数要跑 mvn 才量得到，那是慢门禁 `check_admin_tests.sh` 的活
  （它判 `Tests run` 为正且 `Failures/Errors/Skipped` 为 0）。快门禁不去背慢门禁的账，
  否则每次改文档都要等一次编译，只会逼人把判据关掉。
- **反证五腿 G1–G5 全部成立**（证据 `tmp/evidence/falsify-docgate-{G1..G5}.txt` 与汇总）：
  G1 量数口径漏掉一个模块 / G2 逐模块正则静默失配 / G3 文档把条数写成 46 /
  G4 文档把 admin 的条数写成 35 / G5 扫描范围 glob 失配 —— 每条都是"改坏 → rc=1 且点名本次判据 →
  还原逐字节一致 → 复跑回绿"。
- **驱动自身又翻过一次车**（记下来，因为它不是门禁的洞）：第一版把三元的 edits 元组按两个变量解包，
  第一条腿就抛 `ValueError`，`finally` 里的收尾校验因此报"未还原"——靠 `atexit` 兜底把文件还了回去。
  这正是本批判据要求的东西：**中断必须还原**，不然盘上留一份改坏的门禁，下一个人复跑看到的就是假绿。

### 🧩 批次14（T2-A）— 2026-10-04 改写器有了第一个调用方：分片规则表 + 离线 DDL 生成器

批次11-T2 的收尾结论是"改写器没有生产调用方"。本批补的就是这一格：规则从哪来、谁去调它、
调用之前必须先过哪些校验。**没有加 REST 入口**（没有归属校验的入口一律不开），
也**没有在 TDSQL 实例上验收** —— 对外口径写在下面"能力档位"。

- **规则表 `tdsql_shard_rule`**（`bin/db/datax_web.sql`，建表脚本现在 13 张表）：一张逻辑表一条规则，
  ①导入 / ②DDL 生成 / ③SQL 改造共用这一份配置，不做三套。
  与运行期那些表刻意不同：写成 `CREATE TABLE IF NOT EXISTS` 且**不带删表语句** ——
  这张表存的是用户配置，建表脚本被重复执行（重装、修环境）时一次删表就把规则清空，
  而清空之后生成器只会报"没有登记规则"，看起来跟用户还没配一模一样，查不出来。
- **读侧唯一口径 `TdsqlShardRules.singleEnabled`**：取不到启用规则、取到多条、
  甚至"按 A 表查却拿回 B 表的行"（mapper 少写一个条件时的形态）一律抛异常。
  不让调用方各自 `list.get(0)`：多条启用时取到哪条取决于数据库返回顺序，
  同一个作业两次跑出不同 DDL 是最难查的那类错误。空列表和"全停用"分两条消息写，
  运维看一眼就知道是没配还是被停了。
- **`TdsqlDdlGenerator`**：产出路径只有 `TdsqlDdlRewriter.rewrite` 一条，生成器自己不拼分布式子句。
  规则不自洽时拒绝生成并把**原文**还回去：分片表没写分片键、分片键不是裸列名（复合键不猜）、
  分片表没写分片数、广播表/单表却带了分片键、单表带了分片数、自增列没配序列名、
  表类型不认识、规则说的表与传进来的 DDL 不是同一张表。
  这些形态的共同点是"表照样建得起来"，错要等到数据倾斜或主键冲突才暴露 —— 在这里失败的代价小两个数量级。
- **改写器新增公开方法 `tableNameOf`**：规则与 DDL 的核表名必须有唯一解析口径
  （反引号、库名前缀、大小写不敏感都沿用改写器那一套），不能在生成器里再写一份 `split`。
- **服务 `TdsqlShardRuleService.findEnabledRule`** 出口直接给单条而不是 List，把"一张表只许一条启用规则"
  钉在服务边界上，调用方没有自己取第一条的机会。mapper 只用 MyBatis-Plus 通用方法、不留 XML
  —— 少一个文件就少一处"改了实体忘了改 resultMap"。
- **门禁 `check_tdsql.sh` 加了形状段**：15 条形状判据（生成器必须经改写器 / 源码里不得出现子句字面量 /
  必须核表名 / 服务不得 `get(0)` / 规则表必须可重复执行且不得带删表语句 / 六个必需列都在），
  跑在 mvn 前面；段尾按 `shape_checks < 15` 兜住"判据被删空就静默全绿"。
- **形状段第一版是惰性的，被反证当场抓出来**：`need`/`ban` 只把 `shape_failed` 置位，
  而落地那句 `[ "$shape_failed" -ne 0 ] && exit 1` 写在判据**之前**，只管到了"文件缺失"。
  于是七条形状判据全是"打印 FAIL 但退出码 0"，反证 P1–P7 一次都没让门禁变红。
  改成统一裁决 + 条数下限后重跑，七条腿才真的红。**判据没接到退出码，等于没写**
  —— 这是本仓同类自空洞的第七次，抓取方式依旧是反证而不是复盘。
- **第二条抓出来的洞：注释可以给形状判据供分**。收紧退出码后重跑，P2（把报错文案改成"不太对劲"）
  仍然不成立 —— 因为判据写的是 `grep '表名对不上'`，而生成器上方那行注释里就有这四个字，
  文案被改掉、注释还在，门禁照绿。改法是让判据必须命中字符串本身（`表名对不上，拒绝生成"` 带收尾引号），
  并新增 P8 反证"条数下限"这条守卫本身（删掉一条判据必须当场红灯）。
  与上一批 `grep` 命中 import 行是同一类：**形状判据要钉住"能被执行的那份"，不是"提到过的那份"**。
- **能力档位（对外措辞按这版）**：③从"规则就绪、无生产调用方"进到
  "**规则表 + 离线生成器已接线**（规则 → DDL 一条链，无 REST 入口、无界面录入）"，
  仍然**无 TDSQL 实例验收**；"能导入 TDSQL / 分库分表 / 自动改造原程序代码"三件套里
  ②③都还没有端到端跑过真实例，不得称已完成。
- 测试类 44→46；`check_tdsql.sh` 现在跑 47 条用例（改写器 22 / 生成器 15 / 读侧口径 10）。

### 🐞 批次15 — 2026-10-04 建库脚本可重放 + 登录请求体的两条"静默失败"（收口 2026-10-07）

这一批的起因是把批次14 的产物真正装起来跑一遍：`bin/db/datax_web.sql` 导进 MySQL 8、
用交付的部署包起 admin、再打真 HTTP。两条缺陷都不是读代码读出来的 —— 单看形状什么都对
（try/catch 在、字段在、token 也在），只有真发一次请求才知道"接住了异常"和"回答了客户端"
是两件事。

**一、第 16 道门禁 `devops/checks/check_sql_replay.py`（建库脚本必须可重放）**

- 上游缺陷：`bin/db/datax_web.sql` 里 12 张建表只有 11 张配了 `DROP TABLE IF EXISTS`，
  独独漏了 `job_project` —— 第二次导入同一份脚本时 `CREATE TABLE job_project` 撞 1050
  报表已存在并中断。补的就是那一条 `DROP TABLE IF EXISTS \`job_project\`;`。
  现状实测：13 张表 = 12 张先删再建 + 1 张刻意保留（`tdsql_shard_rule`）。
- 门禁四条判据：① 除例外表外每张表都要有 `DROP TABLE IF EXISTS`（不带 IF EXISTS 也算红，
  干净库第一遍就会中断）；② 重复执行不重建的表不许被裸 `INSERT`（第二遍插出重复出厂数据）；
  ③ 例外表 `tdsql_shard_rule` 存用户配置，既不许被删、也要钉住它必须用
  `CREATE TABLE IF NOT EXISTS` 这种例外写法；④ 解析器自证 —— 原文里的 `CREATE TABLE` 条数
  与解析出来的条数对不上就红，一条都没解析出来也红（防"判据写在文件里但静默漏表"）。
- 复跑证据：同一份脚本在 MySQL 8.0.12（主机版）与 8.0.46（容器版）连导两遍不中断，
  第二遍 `tdsql_shard_rule` 的行数不变。

**二、登录请求体这一侧的两条缺陷（`POST /api/auth/login`）**

1. `LoginUser.rememberMe` 声明成 `Integer`，而客户端按 JSON 惯例传布尔量 `true/false`
   时整份请求体读不进来；读进来失败那一支又 `return null`，`AbstractAuthenticationProcessingFilter`
   把 null 解释成"子类还没走完"直接 return —— 客户端拿到 **HTTP 200 + Content-Length: 0**，
   既没有 token 也没有原因，用户侧表现就是"点了登录没反应"。
   - 修法分两处：字段改 `Boolean`，并且**装机 UI 原来的 `1/0` 写法必须继续可用**
     （jackson-databind 2.9.10.6 的整数转布尔接住它，两种形状各有一条用例钉着）；
     读不进来改为抛 `AuthenticationServiceException` 交给失败分支，写一个带原因的身体。
   - HTTP 状态维持 200、失败信息放 body 的 `code`：装机前端的响应拦截器按 body 判失败，
     改成 401 会让它走另一条分支、错误文案反而变空。
2. `rememberMe` 挂在 Tomcat 工作线程上、只 set 不清：线程跨请求复用，上一位的"记住我 = 7 天"
   会被下一位继承。与批次7 的 ShardingUtil 同一条口径，成功与失败两个出口都在 `finally` 里清；
   成功出口的清理另有一条契约用例单独钉住（不靠邻居那行无条件 `set()` 顺带救）。
3. 分档只到"请求体格式不对"为止：账号不存在与口令错仍是同一句话，登录接口不许变成账号枚举器。
4. 日志只记异常类型，不记消息也不记栈：实测这一路 Jackson 的消息里只有
   `[Source: (CoyoteInputStream); line: 1, column: 54]` 这样的定位、没有原文片段
   （本轮 grep 过 `console.out`，明文口令命中 0 次）；但"有没有原文"取决于请求体以什么来源
   喂进解析器 —— 换成先读成 `byte[]`/`String`（很常见的一次重构）就会带上出错位置附近的原文，
   而那一栏的邻居就是 password。按最小面记。

**三、文案退回层：配置层可以被单独留在旧版**

跑 A/B 时先撞上的是自己这次的改动：换上重新构建的 jar、`conf/` 却还是旧包解出来的，坏体响应
从"零字节"变成 12 字节的 `{"code":500}` —— 有状态码、一个字的理由都没有。原因在部署形状：
i18n 不在 jar 内，而在 `conf/i18n/` 下（`src/main/assembly/deploy.xml` 的 fileSet +
`bin/datax-admin.sh` 的 `CLASSPATH=lib/*:conf:.`），那是运维可编辑、也可被单独替换的外部文件。
所以新增文案键不能只存在于配置文件里：失败分支按"专用键 → 通用键 `login_param_invalid` →
字面兜底"三层取值，最后一层保证身体永远带理由。分层写成纯静态 `firstNonBlank`，
另加一条把 i18n 缓存换成空 `Properties` 再走完整失败分支的端到端用例 —— 只测分层函数打不到
调用点（源码树的 conf 齐全，调用点写回裸读取也照样绿）。

**四、用例与反证**

- 新用例类 `JwtLoginBodyTest`（11 条）：两种请求体形状都能登录、`1` 与 `true` issued 出同一档
  token、缺键 conf 下响应仍带理由、两档文案在两份语言文件里都存在且不同、成功/失败都不把
  `rememberMe` 留在线程上（成功出口另有独立契约断言）。`CredentialEntityToStringTest` 跟着字段类型改。
- 管理端回归 `Tests run: 112, Failures: 0, Errors: 0, Skipped: 0`（101 → 111 → 112，本批 +1 契约用例）。
- 反证三段式 11 条腿（改坏→点名本次规则→还原逐字节一致并复跑回绿），驱动
  `tmp/draft/falsify_login_body_b15.py`：L1a–L10 十条打业务规则，L7 打发现层
  （新用例不登记进门禁名单时门禁自己会响）；汇总 `腿数=11 成立=11 不成立=0`
  （`tmp/evidence/falsify-login-b15-summary.txt`）。其中两条特意去打"驱动自己"：
  一条把调用点改回裸 `I18nUtil.getString`、一条把分层判据改成只认非 null。
  运行期另有三腿对照（出厂 jar/修复 jar × 缺 key conf/齐全 conf，真 HTTP），
  见 `tmp/evidence/b15-ab-conf-tier.txt`：出厂 jar 给 0 字节、修复 jar + 缺 key conf 给通用文案、
  修复 jar + 齐全 conf 给专用文案。
- 收口环境记录（DeepSeek Harness 会话首跑）：`bash` 命中 `C:\Windows\system32\bash.exe` WSL 桩、
  `python3` 命中 WindowsApps 占位程序（rc=49 零输出）、Git-Bash 直呼时 Docker bin 的无扩展名
  `docker` shim 因 `env: sh` 不可解析 —— 三条都修在驱动/会话层（find_bash 排除 system32、
  python3 用真解释器 shim、call() 注入 Git usr/bin PATH），门禁与脚本本体零改动。

**五、③ 能力口径（自动改造原程序代码）本轮没变**

批次14 的措辞继续适用：能生成改写产物、能落库、能重放，**还没有**接进调度的执行链，
所以对外仍按"离线可用的规则与产物生成"讲，不讲"已自动改造"。

### 🧩 批次16 — 2026-10-07 分片广播接真分片（T2-B：每片一份 jobJson）

**问题**：`SHARDING_BROADCAST` 历史上把同一份静态 `job_json` 发给每个执行器 —— DataX 任务选
分片广播等于**在一张表上跑 N 份完整全量**（重复写 + 假分片）。`broadcastIndex/Total` 只有
GLUE 脚本消费，`ExecutorJobHandler` 与 `BuildCommand` 从不读它们。

**切片口径（红线先行）**：

1. **只做来源侧切片**：reader.where 追加 `AND (pk % total) = index`（pk 取规则
   `pk_columns` 快照第一列，过 `SqlSafeIdentifier` 闸口）。**绝不实现目标侧路由**——
   `hash(shardkey)%N` 算物理分片是 TDSQL 内核的事，自研路由 = 错误数据发生器；
   写入仍按逻辑表进 proxy，由内核落分片。来源侧"我们的数据怎么分批搬"与内核路由正交。
2. **querySql 自由文本显式拒绝**：改写别人的 SQL = 静默错误源，报错优于猜测（口径同改写器）。
3. **无规则 = 现状行为**：`TdsqlShardDispatch.findSlicableRule` 的所有"切不了"
   （GLUE 任务 / 老密文无占位符 / 目标非 TDSQL / 规则缺如·停用·多条 / 表名不符）一律返回
   null，触发链路原样下发 N 份全量——**没配规则的老任务零感知**；绝不把"没配规则"
   升级成任务失败，也绝不静默回退切片（切片失败 = 该片记 trigger 失败日志并中止，
   不给"看起来分了片、实际每片全量"的表象）。
4. **规则类型必须是 SHARD**：BROADCAST/SINGLE 的切片语义未定义，拒绝。

**实现**：`TdsqlShardSlicer`（纯函数：jobJson+规则+index/total → 切片 jobJson；fastjson
深解析，writer/settings 原样）、`TdsqlShardDispatch`（接线层：从 writer 侧
`@@DATAX_DS_USER/PWD:<id>@@` 占位符解出目标数据源 → 判 `datasource=tdsql` → 按
(datasource_id, logic_db, logic_table) 取唯一启用规则）、`JobTrigger`（`processTrigger`
新增 `sliceRule` 参数，广播分支解析一次、每片现场切片；手工分片路径显式传 null）。
执行器与 BuildCommand **零改动**——每片仍是一次普通 DataX 进程，部署面无感。

**验证**：第 17 道门禁 `check_shard_slice.sh`（形状：Slicer 唯一实现 + JobTrigger 唯一接线 +
main 源码无目标侧路由特征 + querySql 拒绝分支 + 无规则回退分支；行为：切片单测登记进回归名单）；
`TdsqlShardSlicerTest`（每片取模条件、where 两条拼接路径、writer 侧不动、querySql/多表/表名
不符/无 pkColumns/越界 index 拒绝）+ `TdsqlShardDispatchTest`（占位符解构、类型判定、
无占位符 = 不可切片）。门禁 16→17，`MIN_GATES` 与两份 README、CHANGELOG 同步。
门禁反证 4 腿全成立（querySql 判据失效 / 注入 hashCode 取模路由 / 手工分片回退被删 /
切片单测退出名单 → 各自改坏即红且点名 → 还原逐字节一致 → 复跑回绿），
其中"任意命名的取模路由函数"实测绕开了第一版按字段名匹配的正则，判据据此加了
`hashCode()%` 与路由命名两组特征（`tmp/evidence/falsify-shard-slice-b16-summary.txt`）。

**诚实边界**：切片正确性目前由离线单测 + 门禁反证保证；**真实 TDSQL 集群上的端到端对照**
（切片后总行数 = 全量行数、无重复无遗漏）仍属"未实测清单"，与 T1 同挂——不因本批宣布
② 分库分表"已完成"。

### 📊 统计（相对上游 v2.1.2 发布点 tag `v-2.1.2`，对账锚点 `67c1004`，实测值）

> 口径一：**参照物用公开的 tag**。`upstream-baseline` 是维护者本地的台账分支，没有随 fork 推送，
> 别人 clone 下来跑不出同一条命令，所以对外数字一律按 `v-2.1.2` 计（历史重写后上游提交在本仓库里
> 是另一批 sha，拿真上游 sha 去 diff 会把整个仓库算成改动，因此也不能改用上游 sha）。
> 口径二：**终点写 sha，不写 `HEAD`**。锚点 `67c1004` 就是写下这组数字时的仓库 HEAD。写 `HEAD`
> 会让这组数字自指失配——携带这些数字的那次提交本身落在 `HEAD` 里，谁跑都比文档大一点，
> 于是"可复现"变成一句空话（这正是本文件此前 139/+15,468 那组数字对不上的机制之一）。
> 复现：`git diff --shortstat v-2.1.2..67c1004`；提交数按作者隔离（上游那 54 个提交没有我们的邮箱）：
> `git log --author=bianqiang@gmail.com --oneline v-2.1.2..67c1004 | wc -l`。
> 这四处数字与锚点由门禁 `devops/checks/check_doc_commands.py` 第 10 条现场重量一遍，改错或改旧都红灯。

- **提交数**：47（我们自己写的；`v-2.1.2..67c1004` 共 101 个提交，其中 54 个是上游 2.1.2 之后的）
- **修改文件**：135
- **新增代码**：+12,095 行
- **删除代码**：-734 行

复核口径与实测命令：

```bash
bash devops/fork-workflow.sh diff       # 文件级改动 + 提交清单
bash devops/fork-workflow.sh recheck    # 全部门禁复跑
```

---

## [2.1.2] — 原版 (WeiYe-Jing/datax-web)

原始版本功能，详见[原项目 README](https://github.com/WeiYe-Jing/datax-web)。

### 新增
1. 添加项目管理模块
2. RDBMS 数据源批量任务创建
3. ClickHouse 数据源 JSON 构建
4. 执行器监控页面图形化
5. RDBMS 增量抽取主键自增方式
6. MongoDB 数据源连接方式更换
7. 脚本任务增加停止功能
8. RDBMS JSON 构建增加 postSql
9. 数据源加密算法修改及优化
10. 日志页面增加 DataX 执行统计
