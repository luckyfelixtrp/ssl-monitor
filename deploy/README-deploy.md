# SSL Monitor 部署指南

本文档描述如何部署 SSL 证书监控系统，提供两种方式：Docker Compose 一键部署、或裸机手动部署。

## 架构

```
浏览器  ──>  Nginx (80)  ──┬── /          静态前端 (Vue3 build → dist)
                           └── /api/      反向代理到 Tomcat (8080) 的 /ssl-monitor/api/
                                                  │
                                          Tomcat 10.1 + JDK 17
                                          部署 ssl-monitor.war
                                                  │
                                              MySQL 8
```

---

## 方式一：Docker Compose 一键部署

### 0. 环境要求

- Docker 20.10+
- Docker Compose V2

### 1. 一键启动

```bash
docker compose up -d --build
```

启动后访问 `http://服务器IP:8090/` 即可。

### 2. 服务说明

| 容器 | 端口映射 | 说明 |
| --- | --- | --- |
| ssl-monitor-frontend | 8090 → 80 | Nginx 托管前端 dist + 反向代理 /api |
| ssl-monitor-backend | 无外部端口 | Tomcat 10.1 + JDK 17，运行 ssl-monitor.war |
| ssl-monitor-mysql | 3307 → 3306 | MySQL 8.0，数据持久化到 docker volume |

### 3. 常用操作

```bash
# 查看日志
docker compose logs -f backend
docker compose logs -f frontend

# 重启单个服务
docker compose restart backend

# 重新构建并启动
docker compose up -d --build

# 停止所有服务
docker compose down

# 停止并清除数据卷（会删除数据库数据）
docker compose down -v
```

### 4. 环境变量

在 `docker-compose.yml` 中修改以下环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| DB_HOST | mysql | MySQL 主机（Docker 内部用服务名） |
| DB_PORT | 3306 | MySQL 端口 |
| DB_NAME | ssl_monitor | 数据库名 |
| DB_USER | ssl_monitor | 数据库用户名 |
| DB_PASS | ssl_monitor_pwd | 数据库密码 |
| TZ | Asia/Dubai | 时区 |

---

## 方式二：裸机手动部署

### 0. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | Spring Boot 3 要求 |
| Maven | 3.8+ | 仅打包时需要 |
| Node.js | 18+ | 仅构建前端时需要 |
| Tomcat | 10.1.x | Spring Boot 3 用 jakarta 命名空间，必须 Tomcat 10.1+ |
| MySQL | 8.0+ | 也兼容 5.7，但建议 8 |
| Nginx | 任意稳定版 | 反向代理 + 静态托管 |

### 1. 准备 MySQL

```bash
mysql -u root -p < deploy/mysql-init.sql
```

该脚本会创建数据库 `ssl_monitor`、账号 `ssl_monitor / ssl_monitor_pwd`，以及参考表结构。
生产环境请修改默认密码。

### 2. 构建前端

```bash
cd frontend
npm install
npm run build
# 产物: frontend/dist/
```

### 3. 打包后端 war

```bash
cd backend-java
mvn clean package -DskipTests
# 产物: backend-java/target/ssl-monitor.war
```

### 4. 部署到 Tomcat

```bash
cp backend-java/target/ssl-monitor.war /opt/tomcat/webapps/

# 通过环境变量传入数据库连接
cat >> /opt/tomcat/bin/setenv.sh <<'EOF'
export DB_HOST=127.0.0.1
export DB_PORT=3306
export DB_NAME=ssl_monitor
export DB_USER=ssl_monitor
export DB_PASS=ssl_monitor_pwd
EOF

/opt/tomcat/bin/startup.sh
```

验证后端：

```bash
curl http://127.0.0.1:8080/ssl-monitor/api/health
# 期望: {"status":"ok"}
```

### 5. 部署前端到 Nginx

```bash
mkdir -p /var/www/ssl-monitor
cp -r frontend/dist/* /var/www/ssl-monitor/

cp deploy/nginx.conf /etc/nginx/conf.d/ssl-monitor.conf
# 按需修改 server_name

nginx -t && systemctl reload nginx
```

### 6. 访问

浏览器打开 `http://服务器IP/` 即可。

---

## 常见问题

**Q: 页面能开，但操作报"无法连接到后端服务"**
检查 nginx 的 `/api/` 代理是否指向正确的 Tomcat context：
```bash
curl http://127.0.0.1:8080/ssl-monitor/api/health
```
若不通，确认 war 名是否为 `ssl-monitor.war`；若改名为 `ROOT.war`（context 变成 `/`），
需要把 `deploy/nginx.conf` 里的 `proxy_pass` 改成 `http://127.0.0.1:8080/api/`。

**Q: Tomcat 启动报 jakarta / ClassNotFound**
说明 Tomcat 版本过低。Spring Boot 3 必须用 Tomcat 10.1+，不能用 Tomcat 9。

**Q: 数据库连接失败**
确认环境变量已生效（重启 Tomcat），MySQL 账号有远程/本地访问权限。

**Q: 时区/到期天数不对**
检查服务器时区，定时扫描使用服务器本地时区的每天 03:00。

## 升级

**Docker 部署：** `docker compose up -d --build` 即可重新构建部署。

**裸机部署：**
- 后端：重新 `mvn clean package`，覆盖 `webapps/ssl-monitor.war`，删除 `webapps/ssl-monitor/` 解压目录后重启 Tomcat。
- 前端：重新 `npm run build`，把 `frontend/dist/` 内容替换到 `/var/www/ssl-monitor/`。
