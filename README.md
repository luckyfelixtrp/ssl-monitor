# SSL 证书监控系统

自动检测域名的 HTTPS 证书到期时间、TLS 安全配置与可用性，给出 0–100 安全评分。

![Vue 3](https://img.shields.io/badge/Vue-3-42b883) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-6db33f) ![MySQL](https://img.shields.io/badge/MySQL-8.0-4479a1) ![Docker](https://img.shields.io/badge/Docker-Ready-2496ed) ![Windows](https://img.shields.io/badge/Windows-EXE-0078d4)

**1. 功能**

- 证书完整信息：Subject / Issuer / SAN / 指纹 / 公钥 / 序列号
- 到期天数：红（≤7 天）/ 橙（≤30 天）/ 绿三级提醒
- TLS 检测：协议版本、加密套件、密钥位数
- 安全评级：A+ ~ F，附评分与改进建议
- HTTPS 可用性：状态码、响应时间
- 历史记录与趋势追踪

---

**2. 两种运行模式**

| | **Web 版** | **桌面版** |
|:--|:--|:--|
| 后端 | Java Spring Boot 3 | Python FastAPI |
| 数据库 | MySQL 8.0 | SQLite（本地文件） |
| 前端 | Vue 3 + Nginx | Vue 3 + pywebview 原生窗口 |
| 部署 | Docker Compose | 双击 exe |
| 适用 | 服务器 / 团队共享 | 个人 / Windows 单机 |

---

**3. Web 版部署**

**3.1 全新服务器（自带 MySQL 容器）**

```bash
git clone <repo-url> && cd ssl-monitor
cp .env.example .env          # 编辑 .env，设置 DB_PASS
docker compose -f docker-compose.bridge.yml up -d --build
```

访问 **http://服务器IP:8090**

**3.2 复用宿主机 MySQL（host 网络）**

```bash
# 1. 在现有 MySQL 中建库建用户
mysql -u root -p < deploy/mysql-init.sql

# 2. 配置环境变量
cp .env.example .env          # 编辑 DB_HOST / DB_PASS

# 3. 构建并启动（--network host 解决 iptables:false 下依赖下载）
docker build --network host -t ssl-monitor-frontend:latest ./frontend/
docker build --network host -t ssl-monitor-backend:latest ./backend-java/
docker compose up -d
```

访问 **http://服务器IP:8090**

> Docker daemon 如设了 `iptables: false`，bridge 网络不可用，必须用 host 模式。

**3.3 两种方式对比**

|  | **bridge 版** | **host 版** |
|:--|:--|:--|
| 配置文件 | `docker-compose.bridge.yml` | `docker-compose.yml` |
| MySQL | 容器内自带 | 复用宿主机 |
| 占用端口 | 8090 / 8080 / 3307 | 8090 / 8091 / 8195 |
| 适用 | 干净服务器 | 已有 MySQL / iptables 受限 |

---

**4. 环境变量**

复制 `.env.example` 为 `.env` 并按需修改：

| 变量 | 说明 | 默认值 |
|:-----|:-----|:-------|
| `DB_PASS` | 数据库密码 | **必填** |
| `DB_HOST` | MySQL 主机 | `127.0.0.1` |
| `DB_PORT` | MySQL 端口 | `3306` |
| `DB_NAME` | 数据库名 | `ssl_monitor` |
| `DB_USER` | 数据库用户 | `ssl_monitor` |
| `MYSQL_ROOT_PASSWORD` | root 密码（仅 bridge 版） | `root_pwd` |
| `TZ` | 时区 | `Asia/Dubai` |

> `BACKEND_URL` 由 docker-compose 自动设置，一般无需手动修改。

---

**5. 桌面版（Windows exe）**

无需服务器，双击运行，数据本地 SQLite 存储。

**5.1 打包 exe**

前提：Windows 10/11 + Python 3.10+ + Node.js 18+

```bat
:: 双击运行，或命令行执行
build-exe.bat
```

脚本会自动完成四步：

1. 创建 `.venv` 虚拟环境
2. `pip install` 安装 Python 依赖（含 PyInstaller）
3. `npm install && npm run build` 构建前端
4. `pyinstaller` 打包为单文件 exe

完成后 exe 在 `dist\SSL证书监控.exe`（约 80–100 MB）。

使用：将 exe 放到空目录，双击运行，数据自动存储在同目录的 `data\` 下。

**5.2 开发调试**

```bat
run-dev.bat
```

或手动：

```bat
python -m venv .venv
.venv\Scripts\activate
pip install -r backend\requirements.txt
python backend/launcher.py
```

> 系统要求：Windows 10 1809+ 或 Windows 11（自带 Edge WebView2 Runtime）。

---

**6. 项目结构**

```
ssl-monitor/
├── frontend/                  前端（Vue 3，Web 版与桌面版共用）
│   ├── src/                   组件与 API 封装
│   ├── nginx.conf             Nginx 配置模板（Web 版）
│   └── Dockerfile
├── backend-java/              后端 - Web 版（Spring Boot 3）
│   ├── src/                   controller / service / repository / entity
│   └── Dockerfile
├── backend/                   后端 - 桌面版（Python FastAPI + SQLite）
│   ├── app.py                 API 路由
│   ├── checker.py             SSL/TLS 检测核心
│   ├── database.py            SQLite 访问层
│   ├── launcher.py            pywebview 桌面启动器
│   └── utils.py               路径工具（兼容 PyInstaller）
├── deploy/
│   ├── mysql-init.sql         建库建用户脚本
│   └── nginx.conf             裸机部署 Nginx 配置
├── ssl-monitor.spec           PyInstaller 打包配置
├── build-exe.bat              Windows 一键打包
├── run-dev.bat                Windows 开发模式启动
├── docker-compose.yml         host 网络模式
├── docker-compose.bridge.yml  bridge 网络模式（自带 MySQL）
└── .env.example               环境变量模板
```

---

**7. API**

| 方法 | 路径 | 说明 |
|:-----|:-----|:-----|
| GET | `/api/domains` | 域名列表 |
| POST | `/api/domains` | 新增域名 |
| PUT | `/api/domains/{id}` | 更新域名 |
| DELETE | `/api/domains/{id}` | 删除域名 |
| POST | `/api/domains/{id}/check` | 立即检测 |
| GET | `/api/domains/{id}/detail` | 最近检测结果 |
| GET | `/api/domains/{id}/history` | 历史记录 |
| POST | `/api/check-all` | 批量检测 |
| GET | `/api/health` | 健康检查 |

---

**8. 常见问题**

<details>
<summary>Docker 构建时 Maven/npm 下载依赖失败？</summary>

服务器 Docker 如配置了 `iptables: false`，bridge 网络容器无法联网。构建时加 `--network host`：

```bash
docker build --network host -t ssl-monitor-backend:latest ./backend-java/
docker build --network host -t ssl-monitor-frontend:latest ./frontend/
```
</details>

<details>
<summary>容器启动后立即退出？</summary>

```bash
# 查看日志
docker logs ssl-monitor-backend

# 检查端口占用
ss -tlnp | grep -E '8090|8091|8195'
```

端口冲突时修改 `docker-compose.yml` 中的端口。
</details>

<details>
<summary>如何只重启某一个服务？</summary>

```bash
docker compose restart backend
docker compose restart frontend
```
</details>

---

**9. 注意事项**

- 证书检测需连接目标域名 443 端口，确保服务器能出网
- 内网域名也支持，只要服务器能解析并访问
- `.env` 含密码，已在 `.gitignore` 中排除，切勿提交到 Git
- 生产环境建议将 JPA `ddl-auto` 从 `update` 改为 `validate`