# ---- 后端 Tomcat Dockerfile ----
# 多阶段构建：先 Maven 打 war，再 Tomcat 运行
#
# 注意：Tomcat 端口在构建时固定为 8091/8195（host 网络模式避让宿主机端口）
# 如需在 bridge 网络下使用默认 8080/8005，请用 docker-compose.bridge.yml
# （bridge 版的后端 command 会重新 sed 回 8080/8005）

# 阶段1: Maven 构建
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# 阶段2: Tomcat 运行
FROM tomcat:10.1-jdk17
# 删除 Tomcat 默认的 webapps
RUN rm -rf /usr/local/tomcat/webapps/*
# 从构建阶段复制 war 包
COPY --from=builder /app/target/ssl-monitor.war /usr/local/tomcat/webapps/
# 将 Tomcat 端口改为 8091/8195，避免与宿主机现有服务冲突
# bridge 网络模式下通过 docker-compose command 改回 8080/8005
RUN sed -i 's/port="8080"/port="8091"/' /usr/local/tomcat/conf/server.xml && \
    sed -i 's/port="8005"/port="8195"/' /usr/local/tomcat/conf/server.xml
# 数据库连接通过环境变量注入（docker-compose 覆盖）
ENV DB_HOST=127.0.0.1 \
    DB_PORT=3306 \
    DB_NAME=ssl_monitor \
    DB_USER=ssl_monitor \
    DB_PASS=changeme \
    TZ=Asia/Dubai
EXPOSE 8091
CMD ["catalina.sh", "run"]