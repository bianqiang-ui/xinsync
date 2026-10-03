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
- 44 个测试类；管理端回归 101 条用例，core/executor/tdsql/临时文件/遮蔽/RPC 令牌各条链路都有行为用例
- 门禁入口随仓库交付：`bash devops/fork-workflow.sh recheck` 一条命令复跑全部 15 道门禁
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
  （口径当场量：`find <各模块>/src/test/java -name '*Test.java' | wc -l` = 44，admin 34 / core 6 / executor 2 / rpc 2；
  不计 1 个 `*Tests.java` 与 4 个放在测试目录里的工具类）。
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
- **能力档位（对外措辞按这版）**：`TdsqlDdlRewriter` 目前**没有生产调用方**，只有单测在跑。
  所以"③对原程序代码自动改造"仍是**规则就绪、接线未做、无 TDSQL 实例验收**，不得称已完成。

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
