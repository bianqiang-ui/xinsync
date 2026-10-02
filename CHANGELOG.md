# Changelog — XinSync 信数通

所有重要变更记录在此文件中。格式基于 [Keep a Changelog](https://keepachangelog.com/)。

---

## [2.1.2-xinsync] — 2026-10-02

基于 DataX-Web v2.1.2 的深度安全加固版本。

### 🔒 安全修复

#### CRITICAL（5 项 — 全部修复）

- **S1 RPC 反序列化 RCE** — 新增 `WhitelistSerializerFactory`，Hessian 反序列化仅允许白名单类
- **S2 IDOR 越权** — `AccessControl.java` 集中授权，31 条授权接缝全覆盖
  - 所有 Controller 的 CRUD 方法均增加 `denyUnlessCanOperate()` 或 `requireAdmin()` 守卫
  - 分页查询根据用户角色自动过滤（非 admin 仅见自己的数据）
- **S3 命令注入** — `JobParamSafety.java` 双关口防护
  - 入库关口：任务参数保存前校验危险字符（`"`, `` ` ``, `$`, `\`）
  - 执行关口：执行器拼命令前二次校验
- **S5 Log4Shell** — Log4j2 升级至 2.17.2，根 POM `dependencyManagement` 全局锁定
- **S15 GLUE 脚本 RCE** — `denyGlueScriptIfNotAdmin()` 限制脚本型任务仅管理员可创建/修改

#### HIGH（6 项 — 全部修复）

- **S7 SQL 注入** — `SqlSafeIdentifier` 锚定正则白名单 `^[A-Za-z_][A-Za-z0-9_]{0,63}$`
- **S8 XSS / JSON 构建** — `DataxJsonController.buildJobJson` 增加 `AccessControl.adminDeny()` 守卫
- **S9 口令泄漏 - API 响应** — `JobDatasourceController` 所有读接口返回 `PASSWORD_MASK = "******"`
- **S10 口令泄漏 - 日志** — `JobTrigger.sanitizeTriggerMsg()` 正则脱敏 password/accessToken
- **S11 口令泄漏 - 临时文件** — `ExecutorJobHandler` 临时 JSON 文件设置 0600 权限 + `cleanStaleTmpFiles()`
- **S12 JWT 硬编码密钥** — JWT secret 改为环境变量 `${JWT_SECRET}` 注入

#### MEDIUM（2 项 — 全部修复）

- **S13 执行器日志组件** — Logback 版本统一至 1.2.13
- **S14 依赖版本全局统一** — 根 POM 新增 `<dependencyManagement>` 锁定 6 个安全敏感依赖

#### 部分修复（2 项）

- **S4 Spring Boot EOL** — 已识别，需大版本升级（2.1 → 2.7+），影响面大暂缓
- **S6 AES 默认密钥** — 已支持环境变量覆盖，但缺少存量密文迁移脚本

### 🐛 社区 Issue 修复（15/16）

| Issue | 问题 | 修复方案 |
|-------|------|---------|
| #487 | 执行器进程树残留 | `ProcessUtil` Linux 进程树递归 kill |
| #672 | TDSQL DDL 不兼容 | `TdsqlDdlRewriter` 自动改写 shardkey/主键/索引 |
| #389 | Hive Kerberos 连接失败 | JDBC URL Kerberos 参数适配 |
| #348 | PostgreSQL 元数据查询异常 | Schema 查询 SQL 修正 |
| #265 | 数据源密码明文显示 | API 响应密码掩码化 |
| #296 | 定时任务 CRON 校验缺失 | CRON 表达式预校验 |
| #698 | Netty 版本安全漏洞 | 升级至 4.1.100.Final |
| #652 | Fastjson 安全版本 | 升级至 1.2.83 |
| #587 | MySQL Connector 版本 | 升级至 8.0.33 |
| #444 | Docker 构建失败 | Dockerfile 修复 + 基础镜像更新 |
| #336 | 分页查询越权 | 非 admin 自动过滤 userId |
| #512 | 日志脱敏不完整 | trigger_msg 增加口令正则替换 |
| #632 | 执行器注册鉴权 | RPC 通道 token 校验 |
| #478 | 任务参数注入 | JobParamSafety 双关口 |
| #492 | 前端 XSS | JSON 构建接口增加管理员鉴权 |
| #250 | Oracle 元数据查询 | ⏳ 需真实 Oracle 实例验证 |

### 🏗️ 基础设施

- 新增 9 个自动化安全门禁检查（`devops/checks/`）
- 新增 `tools/fork-workflow.sh` 一键复跑所有检查
- Shell 脚本统一 LF 换行符
- 新增 67+ 管理端单元测试
- 新增 `docs/devlog.md` 1290+ 行开发日志
- 新增 `docs/technical-manual.md` 270 行技术手册
- Docker 构建镜像更新为 `maven:3.8-openjdk-8`
- Assembly 打包输出 tar.gz 部署包

### 📊 统计

- **提交数**：29
- **修改文件**：100+
- **新增代码**：+6,593 行
- **删除代码**：-426 行
- **测试类**：12 个
- **门禁检查**：9 个

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
