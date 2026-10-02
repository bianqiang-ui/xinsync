# XinSync 信数通 — Windows 本地启动指南（录制演示视频用）

> 本文档手把手教你在 Windows 上把 XinSync 跑起来，适合录制产品演示视频。
> 所有命令在 **PowerShell** 中执行。

---

## 一、环境检查（1 分钟）

打开 PowerShell，逐条确认：

```powershell
# 1. Java 版本（必须是 JDK 8）
java -version
# 期望输出: java version "1.8.0_xxx"

# 2. Maven
mvn -version
# 期望输出: Apache Maven 3.x.x

# 3. MySQL 是否运行中
# 方法一：检查端口
Get-NetTCPConnection -LocalPort 3306 -ErrorAction SilentlyContinue
# 方法二：用客户端连接测试
```

**你当前的环境：**
- ✅ Java 1.8.0_152
- ✅ Maven 3.9.9（MAVEN_HOME: D:\maven）
- ✅ MySQL 运行在 127.0.0.1:3306（库名 `datax_web`，用户 `devuser`）

---

## 二、数据库准备（3 分钟）

### 2.1 如果数据库已存在（你的情况）

你之前的 qwen 已经建好了 `datax_web` 库（12 张表），直接跳到 **第三步**。

### 2.2 如果需要新建

用任意 MySQL 客户端（Navicat / DBeaver / mysql 命令行）：

```sql
-- 1. 建库
CREATE DATABASE IF NOT EXISTS datax_web DEFAULT CHARACTER SET utf8mb4;

-- 2. 导入表结构和初始数据
USE datax_web;
SOURCE D:/code/githubCode/datax-web-work/datax-web/bin/db/datax_web.sql;

-- 3. 验证
SHOW TABLES;
-- 应该有 12 张表
```

初始管理员账号：**admin / 123456**

---

## 三、Maven 构建（5-10 分钟）

```powershell
cd D:\code\githubCode\datax-web-work\datax-web

# 全量构建（跳过测试）
mvn clean install -DskipTests
```

**验证构建成功：**
```powershell
# 检查部署包是否生成
Get-ChildItem packages\*.tar.gz | Select-Object Name, @{N='Size(MB)';E={[math]::Round($_.Length/1MB,1)}}
```

应该看到：
```
Name                              Size(MB)
----                              --------
datax-admin_2.1.2_1.tar.gz        约 80-100
datax-executor_2.1.2_1.tar.gz     约 30-50
```

> ⚠️ 必须用 `install` 不是 `package`！`package` 只产出瘦 jar（无法直接运行）

---

## 四、启动 datax-admin（管理端）

### 方式 A：直接用 Java 命令启动（推荐，最简单）

由于 XinSync fork 已经为 `application.yml` 补上了所有默认值，可以直接运行主类：

```powershell
cd D:\code\githubCode\datax-web-work\datax-web

# 设置数据库连接（根据你的实际情况修改）
$env:DB_HOST = "127.0.0.1"
$env:DB_PORT = "3306"
$env:DB_DATABASE = "datax_web"
$env:DB_USERNAME = "devuser"
$env:DB_PASSWORD = "你的MySQL密码"

# 设置安全密钥（演示环境可用简单值，生产环境必须用强随机串）
$env:DATAX_JWT_SECRET = "XinSync2026DemoSecretKeyAtLeast32Chars"
$env:DATAX_AES_KEY = "XinSyncDemo2026AES"
$env:DATAX_ACCESS_TOKEN = "xinsync-demo-token-2026"

# 启动！
mvn -pl datax-admin -am spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=9527"
```

> 注意：上面的命令需要 pom 里有 spring-boot-maven-plugin。如果报错，用下面的方式 B。

### 方式 B：用 classpath 方式启动（最可靠）

```powershell
cd D:\code\githubCode\datax-web-work\datax-web

# 设置环境变量（同方式A）
$env:DB_HOST = "127.0.0.1"
$env:DB_PORT = "3306"
$env:DB_DATABASE = "datax_web"
$env:DB_USERNAME = "devuser"
$env:DB_PASSWORD = "你的MySQL密码"
$env:DATAX_JWT_SECRET = "XinSync2026DemoSecretKeyAtLeast32Chars"
$env:DATAX_AES_KEY = "XinSyncDemo2026AES"
$env:DATAX_ACCESS_TOKEN = "xinsync-demo-token-2026"

# 用 Maven exec 插件启动（自动处理 classpath）
mvn -pl datax-admin exec:java -Dexec.mainClass="com.wugui.datax.admin.DataXAdminApplication"
```

### 方式 C：解压部署包启动（需要 Git Bash）

```powershell
# 解压
cd D:\code\githubCode\datax-web-work\datax-web
mkdir deploy -ErrorAction SilentlyContinue
tar -xzf packages/datax-admin_2.1.2_1.tar.gz -C deploy/

# 用 Git Bash 执行 Linux 启动脚本
# 打开 Git Bash（不是 PowerShell），然后：
cd /d/code/githubCode/datax-web-work/datax-web/deploy/datax-admin
export DB_PASSWORD="你的MySQL密码"
export DB_USERNAME="devuser"
export DB_DATABASE="datax_web"
export DATAX_JWT_SECRET="XinSync2026DemoSecretKeyAtLeast32Chars"
export DATAX_AES_KEY="XinSyncDemo2026AES"
export DATAX_ACCESS_TOKEN="xinsync-demo-token-2026"
bin/datax-admin.sh start
```

### 方式 D：IDEA 里直接运行（最适合开发调试）

1. 用 IDEA 打开 `D:\code\githubCode\datax-web-work\datax-web`
2. 找到 `datax-admin/src/main/java/com/wugui/datax/admin/DataXAdminApplication.java`
3. 右键 → `Run 'DataXAdminApplication'`
4. 在 Run Configuration 的 **Environment variables** 里添加：
   ```
   DB_HOST=127.0.0.1;DB_PORT=3306;DB_DATABASE=datax_web;DB_USERNAME=devuser;DB_PASSWORD=你的密码;DATAX_JWT_SECRET=XinSync2026DemoSecretKeyAtLeast32Chars;DATAX_AES_KEY=XinSyncDemo2026AES;DATAX_ACCESS_TOKEN=xinsync-demo-token-2026
   ```
5. 点击运行

---

## 五、验证启动成功

### 5.1 看控制台日志

成功标志（看到这些说明启动OK）：
```
Tomcat started on port(s): 9527 (http) with context path ''
>>>>>>>>> init datax-web admin scheduler success.
Access URLs:
  Local-API:      http://127.0.0.1:9527
  web-URL:        http://127.0.0.1:9527/index.html
```

### 5.2 打开浏览器

访问：**http://127.0.0.1:9527/index.html**

你应该看到 **XinSync 信数通** 登录页面。

### 5.3 登录

- 用户名：`admin`
- 密码：`123456`

登录后你会看到管理后台首页。

---

## 六、演示视频录制指南

### 推荐录屏顺序（约 2-3 分钟素材）

#### 场景1：登录（20秒）
1. 打开浏览器，输入 `http://127.0.0.1:9527/index.html`
2. 标题栏显示"XinSync 信数通"
3. 输入账号密码，点击登录
4. 看到管理后台首页

#### 场景2：数据源管理（30秒）
1. 左侧菜单 → 数据源管理
2. 点击"添加"，展示支持的数据库类型
3. 填写连接信息，点击"测试连接"→ 成功
4. 保存数据源

#### 场景3：任务管理（40秒）
1. 左侧菜单 → 任务管理
2. 展示任务列表界面
3. 点击"添加"，展示：
   - 选择数据源（读端/写端）
   - 选择表、配置字段映射
   - 配置调度策略（CRON 表达式）
4. 保存任务

#### 场景4：构建 JSON & 执行（30秒）
1. 选中一个任务 → 构建
2. 查看生成的 DataX JSON 配置
3. 手动执行一次
4. 查看执行日志

#### 场景5：执行器管理（15秒）
1. 左侧菜单 → 执行器管理
2. 展示在线的执行器列表

#### 场景6：用户管理 & 安全特性（20秒）
1. 左侧菜单 → 用户管理
2. 展示角色权限（管理员 vs 普通用户）
3. 展示安全加固特性（可以在终端展示启动日志中的安全检测输出）

### 录屏工具推荐

- **OBS Studio**：免费，可录制系统声音+麦克风
- **Windows 自带**：Win+G 打开游戏栏录制
- **Bandicam**：轻量，适合录制指定区域

### 后期用剪映制作

录制完原始素材后，我可以帮你用剪映 MCP 自动：
- 添加片头片尾（XinSync 信数通 品牌动画）
- 添加字幕说明
- 添加背景音乐
- 添加转场特效
- 导出成品

---

## 七、常见问题排查

### Q1: Maven 构建时 JAVA_HOME 指向了非 JDK8

```powershell
# 临时切换 JAVA_HOME（你的 JDK8 路径，按实际修改）
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_152"
mvn clean install -DskipTests
```

### Q2: 启动报 "Could not resolve placeholder"

说明环境变量没设好。逐个检查：
```powershell
echo $env:DB_PASSWORD
echo $env:DB_USERNAME
echo $env:DB_DATABASE
```

### Q3: 数据库连接失败

确认 MySQL 在运行：
```powershell
Test-NetConnection -ComputerName 127.0.0.1 -Port 3306
```

确认用户名密码对：
```powershell
# 用命令行测试（如果装了 mysql 客户端）
mysql -h 127.0.0.1 -u devuser -p -e "SHOW DATABASES"
```

### Q4: 9527 端口被占用

```powershell
Get-NetTCPConnection -LocalPort 9527 -ErrorAction SilentlyContinue | Select-Object OwningProcess
# 找到占用的进程并关闭，或者改端口：
$env:SERVER_PORT = "9528"  # 用别的端口
```

### Q5: 页面打开是空白或 404

- 确认访问的是 `/index.html` 不是 `/`
- 确认前端静态资源在 `datax-admin/src/main/resources/static/` 目录下
- 如果是构建产物方式，确认 `conf/` 和 `lib/` 在同一目录

---

## 八、停止服务

```powershell
# 方式A/B/D：直接在运行的终端按 Ctrl+C
# 方式C（Git Bash）：
bin/datax-admin.sh stop
```

---

## 附录：环境变量速查表

| 变量 | 必填 | 说明 | 示例值 |
|------|------|------|--------|
| `DB_HOST` | 否（默认127.0.0.1） | MySQL地址 | `127.0.0.1` |
| `DB_PORT` | 否（默认3306） | MySQL端口 | `3306` |
| `DB_DATABASE` | 否（默认dataxweb） | 数据库名 | `datax_web` |
| `DB_USERNAME` | **是** | 数据库用户 | `devuser` |
| `DB_PASSWORD` | **是** | 数据库密码 | `你的密码` |
| `DATAX_JWT_SECRET` | 建议设置 | JWT签名密钥（≥32字符） | `XinSync2026...` |
| `DATAX_AES_KEY` | 建议设置 | 数据源口令加密密钥 | `XinSyncDemo2026AES` |
| `DATAX_ACCESS_TOKEN` | 建议设置 | Admin↔Executor通信令牌 | `xinsync-demo-token` |

> ⚠️ `DB_DATABASE` 默认值是 `dataxweb`（无下划线），而你的实际库名是 `datax_web`（有下划线），**必须显式设置**！
