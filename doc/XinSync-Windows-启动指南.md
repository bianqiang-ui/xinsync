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

**本机需要满足：**
- JDK 8（`java -version` 期望 `1.8.0_xxx`；本仓库只在 JDK 8 下构建与验证过）
- Maven 3.x
- MySQL 运行在 127.0.0.1:3306，库名 `datax_web`，用一个你有权限的账号

---

## 二、数据库准备（3 分钟）

### 2.1 如果数据库已经建好

跳过本节，直接到 **第三步**。

### 2.2 如果需要新建

用任意 MySQL 客户端（Navicat / DBeaver / mysql 命令行）：

```sql
-- 1. 建库
CREATE DATABASE IF NOT EXISTS datax_web DEFAULT CHARACTER SET utf8mb4;

-- 2. 导入表结构和初始数据（路径换成你自己的仓库根目录）
USE datax_web;
SOURCE <仓库根目录>/bin/db/datax_web.sql;

-- 3. 验证
SHOW TABLES;
-- 应该有 13 张表（等于 bin/db/datax_web.sql 里 CREATE TABLE 的条数）
```

初始管理员账号：**admin / 123456**

---

## 三、Maven 构建（5-10 分钟）

```powershell
# 仓库根目录：换成你 clone/解压 的位置，后面所有命令都用它
$REPO = "<仓库根目录>"      # 例：$REPO = "C:\src\xinsync"
cd $REPO

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

### 启动前：先准备三个密钥（一次性，且不要抄任何示例值）

`application.yml` 对 JWT 密钥与通信令牌**故意不给可用默认值**（空值 → 每次启动随机签名密钥 / 回调被拒），
数据源口令的出厂 AES 密钥 `AD42F6697B035B75` 会被启动日志判定为不安全。
本文档也不再给可以直接抄的常量：**公开在仓库里的密钥等于没有密钥**，任何照做部署的人都会共用同一把后门。

```powershell
# 只生成一次，然后写进你的密码管理器 / 部署编排，长期固定使用
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

$b = New-Object byte[] 48; $rng.GetBytes($b)     # JWT 签名密钥（≥32 字符）
$env:DATAX_JWT_SECRET   = [Convert]::ToBase64String($b)

$b = New-Object byte[] 16; $rng.GetBytes($b)     # 数据源口令加密密钥（32 位十六进制）
$env:DATAX_AES_KEY      = [BitConverter]::ToString($b).Replace("-", "")

$b = New-Object byte[] 24; $rng.GetBytes($b)     # admin <-> executor 通信令牌
$env:DATAX_ACCESS_TOKEN = [Convert]::ToBase64String($b)
```

> ⚠️ 三个值都不能随手换：
> - 换 `DATAX_AES_KEY` → 旧密钥入库的数据源口令全部解不开，必须重新录入数据源；
> - 换 `DATAX_JWT_SECRET` → 所有已登录会话失效；多实例部署时各实例必须用同一个值，否则 A 机签发的 token 在 B 机不被认；
> - 换 `DATAX_ACCESS_TOKEN` → admin 与 executor 两侧要同时改，否则回调被拒、任务统一报认证失败。

下面的每个启动方式里，把这 3 个变量设成你上面生成（并自行保管）的值。

### 方式 A：`spring-boot:run`（**当前不可用**）

```powershell
mvn -pl datax-admin -am spring-boot:run
```

本仓库的 pom 里**没有** `spring-boot-maven-plugin`（只有 `maven-jar-plugin` / `exec-maven-plugin` / `maven-assembly-plugin`），
这条命令会以 "No plugin found for prefix 'spring-boot'" 失败。请用方式 B。

### 方式 B：用 Maven exec 插件启动（推荐，最简单）

```powershell
cd $REPO                       # 见第三步定义的仓库根目录

# 数据库连接
$env:DB_HOST = "127.0.0.1"
$env:DB_PORT = "3306"
$env:DB_DATABASE = "datax_web"      # 默认值是 dataxweb（无下划线），必须显式设
$env:DB_USERNAME = "<你的数据库账号>"
$env:DB_PASSWORD = "<你的数据库密码>"

# 安全密钥：用"启动前"那一节生成的随机值，不要写常量
$env:DATAX_JWT_SECRET = "<生成的随机串>"
$env:DATAX_AES_KEY = "<生成的随机串>"
$env:DATAX_ACCESS_TOKEN = "<生成的随机串>"

# 启动
mvn -pl datax-admin exec:java -Dexec.mainClass="com.wugui.datax.admin.DataXAdminApplication"
```

### 方式 C：解压部署包启动（需要 Git Bash）

```powershell
# 解压
cd $REPO
mkdir deploy -ErrorAction SilentlyContinue
tar -xzf packages/datax-admin_2.1.2_1.tar.gz -C deploy/
```

```bash
# 打开 Git Bash（不是 PowerShell），把路径换成你的仓库根目录
cd <仓库根目录>/deploy/datax-admin
export DB_PASSWORD="<你的数据库密码>"
export DB_USERNAME="<你的数据库账号>"
export DB_DATABASE="datax_web"
export DATAX_JWT_SECRET="<生成的随机串>"
export DATAX_AES_KEY="<生成的随机串>"
export DATAX_ACCESS_TOKEN="<生成的随机串>"
bin/datax-admin.sh start
```

### 方式 D：IDEA 里直接运行（最适合开发调试）

1. 用 IDEA 打开你的仓库根目录
2. 找到 `datax-admin/src/main/java/com/wugui/datax/admin/DataXAdminApplication.java`
3. 右键 → `Run 'DataXAdminApplication'`
4. 在 Run Configuration 的 **Environment variables** 里添加（值全部用上面生成的随机串）：
   ```
   DB_HOST=127.0.0.1;DB_PORT=3306;DB_DATABASE=datax_web;DB_USERNAME=<你的数据库账号>;DB_PASSWORD=<你的数据库密码>;DATAX_JWT_SECRET=<生成的随机串>;DATAX_AES_KEY=<生成的随机串>;DATAX_ACCESS_TOKEN=<生成的随机串>
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
| `DB_USERNAME` | **是** | 数据库用户 | `<你的数据库账号>` |
| `DB_PASSWORD` | **是** | 数据库密码 | `<你的数据库密码>` |
| `DATAX_JWT_SECRET` | **生产必填** | JWT 签名密钥（≥32 字符随机串）；留空则每次启动随机生成，重启后所有 token 失效；`datax_admin`/`datax-web`/`secret`/`123456` 这几个已知默认值会被直接忽略 | 见"启动前：先准备三个密钥" |
| `DATAX_AES_KEY` | **生产必填** | 数据源口令落库加密密钥；出厂值 `AD42F6697B035B75` 会被启动日志判为不安全；换 key 后旧口令解不开 | 同上 |
| `DATAX_ACCESS_TOKEN` | **生产必填** | Admin↔Executor 通信令牌；留空时回调接口一律拒绝（除非显式 `DATAX_ALLOW_EMPTY_ACCESS_TOKEN=true`），admin 与 executor 两侧必须一致 | 同上 |

> ⚠️ `DB_DATABASE` 默认值是 `dataxweb`（无下划线），而你的实际库名是 `datax_web`（有下划线），**必须显式设置**！
