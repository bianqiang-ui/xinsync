<p align="center">
  <h1 align="center">XinSync 信数通</h1>
  <p align="center"><strong>信创数据同步，安全可控</strong></p>
  <p align="center">信创生态下的企业级数据集成平台 · 基于 DataX 深度安全加固</p>
</p>

<p align="center">
  <a href="https://github.com/bianqiang-ui/datax-web/blob/master/LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License"></a>
  <a href="https://github.com/bianqiang-ui/datax-web/releases"><img src="https://img.shields.io/badge/Version-2.1.2--xinsync-green.svg" alt="Version"></a>
  <img src="https://img.shields.io/badge/JDK-1.8+-orange.svg" alt="JDK">
  <img src="https://img.shields.io/badge/Spring%20Boot-2.1.x-brightgreen.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Security-13%2F15%20Fixed-blueviolet.svg" alt="Security">
  <img src="https://img.shields.io/badge/信创-适配-red.svg" alt="信创">
</p>

<p align="center">
  <a href="https://github.com/bianqiang-ui/datax-web">📦 GitHub</a> ·
  <a href="https://gitee.com/brian888/xinsync">📦 Gitee（国内镜像）</a>
</p>

---

## 📖 项目简介

**XinSync 信数通** 是基于开源项目 [DataX-Web](https://github.com/WeiYe-Jing/datax-web)（v2.1.2）深度安全加固的信创数据同步平台。针对信创生态下的数据迁移、数据同步、异构数据库集成等场景，提供**安全可控、开箱即用**的 Web 化管理能力。

> ### 🔥 这是一次脱胎换骨的彻底改造
>
> 本项目**不是简单的 Bug 修复**，而是对原版 DataX-Web 进行的一次**全方位、系统性的安全重塑**：
>
> - 📊 **29 次提交**，涉及 **100+ 个文件**，新增 **6,500+ 行代码**
> - 🔒 修复 **15 个安全漏洞**（含 5 个 CRITICAL 级别），封堵了从 RCE 远程代码执行到越权访问的全部高危攻击面
> - 🛡️ 建立 **31 条授权接缝** 全覆盖的权限体系，从"几乎裸奔"到"全面设防"
> - ✅ 解决 **15 个社区长期悬而未决的 Issue**，包括进程残留、DDL 兼容、口令泄漏等顽疾
> - 🏗️ 构建 **9 道自动化安全门禁**，每次改动可一键验证，杜绝安全回退
> - 📝 输出 **1,290+ 行开发日志** + **270 行技术手册**，全程可追溯
>
> **原版是一把好刀，我们给它淬了火、开了刃、配了鞘。**

### 与原版的核心区别

| 维度 | 原版 DataX-Web | XinSync 信数通 |
|------|---------------|---------------|
| **安全加固** | 存在 15 个已知安全漏洞 | 13 个完全修复，2 个部分修复 |
| **授权体系** | 无系统化鉴权 | 31 条授权接缝全覆盖 + IDOR 防护 |
| **口令保护** | API 返回明文密码 | 全链路口令脱敏（API/日志/临时文件） |
| **依赖安全** | Log4j 等存在 CVE | Log4j2 2.17.2 / Logback 1.2.13 / Fastjson 1.2.83 |
| **信创适配** | 无 | TDSQL DDL 改写、国产数据库元数据支持 |
| **质量门禁** | 无 | 9 个自动化安全检查 + 可复跑验证 |

---

## 🏗️ 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    XinSync Admin (Web UI)                │
│    Spring Boot 2.1.x + Spring Security (JWT) + MyBatis  │
│    ┌──────────┐  ┌──────────┐  ┌──────────────────┐    │
│    │ 任务管理  │  │ 数据源管理│  │ JSON 构建 & 调度  │    │
│    └──────────┘  └──────────┘  └──────────────────┘    │
│              AccessControl (31 条授权接缝)               │
└────────────────────────┬────────────────────────────────┘
                         │ Hessian RPC (白名单反序列化)
┌────────────────────────▼────────────────────────────────┐
│              XinSync Executor (执行器集群)               │
│    ┌──────────┐  ┌──────────┐  ┌──────────────────┐    │
│    │ DataX 引擎│  │ 脚本执行  │  │ 进程管理 & 监控   │    │
│    └──────────┘  └──────────┘  └──────────────────┘    │
│         JobParamSafety (命令注入防护双关口)               │
└─────────────────────────────────────────────────────────┘
```

---

## ✨ 功能特性

### 数据同步核心
- ✅ 支持 **MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse、Hive、HBase、MongoDB** 等主流数据源
- ✅ 支持 **TDSQL**（腾讯分布式数据库）DDL 自动改写
- ✅ Web 界面可视化构建 DataX JSON 任务
- ✅ RDBMS 数据源**批量创建**同步任务
- ✅ 支持**增量同步**（时间戳/主键自增）
- ✅ 支持 Hive **分区动态参数**配置

### 任务调度
- ✅ 分布式任务调度（基于 xxl-job 二次开发）
- ✅ 执行器**集群部署**，支持 9 种路由策略
- ✅ 任务超时控制、失败重试、失败告警
- ✅ 任务依赖（父子任务联动）
- ✅ 支持 DataX / Shell / Python / PowerShell 四种任务类型
- ✅ 执行器 CPU / 内存 / 负载实时监控

### 🔒 安全加固（XinSync 独有）
- ✅ **RPC 反序列化防护** — Hessian 白名单序列化工厂
- ✅ **IDOR 越权防护** — 31 条授权接缝全覆盖（AccessControl 集中管控）
- ✅ **命令注入防护** — JobParamSafety 双关口（入库 + 执行）
- ✅ **SQL 注入防护** — SqlSafeIdentifier 白名单校验
- ✅ **GLUE 脚本 RCE 防护** — 脚本型任务限管理员操作
- ✅ **口令全链路脱敏** — API 响应掩码 / 日志脱敏 / 临时文件 0600 权限
- ✅ **JWT 安全** — 密钥环境变量化，拒绝硬编码
- ✅ **Log4Shell 修复** — Log4j2 升级至 2.17.2
- ✅ **依赖安全基线** — Logback 1.2.13 / Fastjson 1.2.83 / Netty 4.1.100

### 🛡️ 质量门禁系统
项目内置 **9 个自动化安全检查**，每次修改都可复跑验证：

```bash
bash tools/fork-workflow.sh recheck
```

| 门禁 | 检查内容 |
|------|---------|
| `check_authz_seams.py` | 31 条授权接缝闭合验证 |
| `check_sql_identifiers.py` | SQL 标识符白名单 |
| `check_datasource_secret_scrub.py` | 数据源口令脱敏 |
| `check_job_param_safety.sh` | 命令注入参数校验 |
| `check_admin_tests.sh` | 管理端单元测试 |
| `check_ports.py` | 端口配置安全 |
| `check_yaml.py` | YAML 配置合规 |
| `check_tdsql.sh` | TDSQL DDL 改写测试 |
| `check_executor_streams.py` | 执行器流处理 |

---

## 🚀 快速开始

### 环境要求

| 组件 | 版本要求 |
|------|---------|
| JDK | 1.8.201+ |
| Maven | 3.6+ |
| MySQL | 5.7+ |
| Python | 2.7 / 3.x |
| DataX | 已安装（[DataX 下载](https://github.com/alibaba/DataX)） |

### 1. 克隆项目

```bash
git clone https://github.com/bianqiang-ui/datax-web.git
cd datax-web
```

### 2. 初始化数据库

```bash
# 导入 SQL 脚本
mysql -u root -p < doc/db/datax_web.sql
```

### 3. 修改配置

编辑 `datax-admin/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/datax_web?useUnicode=true&characterEncoding=UTF-8
    username: your_username
    password: your_password

# JWT 密钥（务必修改，不要使用默认值！）
jwt:
  secret: ${JWT_SECRET:your-random-secret-here}
```

### 4. 编译打包

```bash
mvn clean package -Dmaven.test.skip=true
```

### 5. 启动服务

```bash
# 启动 Admin
cd datax-admin/target
java -jar datax-admin-*.jar

# 启动 Executor
cd datax-executor/target
java -jar datax-executor-*.jar
```

### 6. 访问 Web UI

浏览器打开 `http://localhost:9527`，默认账号：`admin` / `123456`

> ⚠️ **首次登录后请立即修改管理员密码！**

### Docker 部署

```bash
cd build/docker
docker-compose up -d
```

详细部署文档：[部署指南](doc/datax-web/datax-web-deploy-V2.1.2.md)

---

## 📊 信创适配说明

XinSync 信数通特别适配了信创生态中常见的数据库和场景：

| 信创数据库 | 支持状态 | 说明 |
|-----------|---------|------|
| **TDSQL**（腾讯云） | ✅ 完整支持 | DDL 自动改写（shardkey / 主键 / 索引） |
| **MySQL 国产分支** | ✅ 完整支持 | 兼容 MySQL 协议的国产数据库 |
| **PostgreSQL 国产分支** | ✅ 完整支持 | 含元数据查询优化 |
| **Hive on 国产大数据平台** | ✅ 完整支持 | Kerberos 认证 + JDBC 连接 |
| **Oracle → 国产库迁移** | ⚠️ 基本支持 | 元数据查询需真实环境验证 |

---

## 📋 版本变更日志

详见 [CHANGELOG.md](CHANGELOG.md)

### v2.1.2-xinsync 主要变更

**安全修复（15 项，13 项完全修复）：**
- 🔴 CRITICAL × 5：RPC 反序列化 RCE、IDOR 越权、命令注入、Log4Shell、GLUE 脚本 RCE
- 🟠 HIGH × 6：SQL 注入、XSS、口令泄漏（3 处）、JWT 硬编码
- 🟡 MEDIUM × 2：trigger_msg 脱敏、临时文件权限

**社区 Issue 修复（16 项，15 项已修复）：**
- #487 进程树残留、#672 TDSQL DDL、#389 Hive Kerberos、#348 PostgreSQL 元数据 等

---

## 💖 赞助支持

如果 XinSync 信数通 对你有帮助，欢迎赞助支持项目持续发展！

### 微信赞赏

<p align="center">
  <img src="doc/sponsor/wechat-pay.png" alt="微信赞赏码" width="280">
</p>

### 支付宝赞赏

<p align="center">
  <img src="doc/sponsor/ali-pay.jpg" alt="支付宝赞赏码" width="280">
</p>

### 其他支持方式

- ⭐ 给项目点个 Star
- 🐛 提交 Issue 反馈问题
- 🔀 提交 Pull Request 贡献代码
- 📢 推荐给有需要的朋友

---

## 🤝 贡献指南

欢迎参与项目贡献！

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/your-feature`
3. 提交修改：`git commit -m 'feat: add your feature'`
4. 推送分支：`git push origin feature/your-feature`
5. 提交 Pull Request

### 提交规范

```
feat:     新功能
fix:      修复 Bug
security: 安全修复
docs:     文档更新
refactor: 代码重构
test:     测试相关
chore:    构建/工具变更
```

---

## 📜 开源协议

本项目基于 [MIT License](LICENSE) 开源。

- 原始项目：[WeiYe-Jing/datax-web](https://github.com/WeiYe-Jing/datax-web) © 2020 WeiYe
- 安全加固版：[bianqiang-ui/datax-web](https://github.com/bianqiang-ui/datax-web) © 2026 Brian

> 本项目为 DataX-Web 的 Fork 安全增强版，遵循原项目 MIT 协议。感谢原作者及社区贡献者的工作！

---

## 📞 联系我们

- **GitHub Issues**：[提交问题](https://github.com/bianqiang-ui/datax-web/issues)
- **微信**：`13898886628`（添加时请注明 GitHub / Gitee）
- **邮箱**：bianqiang@gmail.com

---

<p align="center">
  <strong>XinSync 信数通</strong> — 信创数据同步，安全可控<br>
  Made with ❤️ by <a href="https://github.com/bianqiang-ui">Brian</a>
</p>
