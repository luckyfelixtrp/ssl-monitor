# ---- 前端 Nginx Dockerfile ----
# 多阶段构建：先 build 前端，再用 Nginx 托管 dist
#
# 代理目标说明：
#   - bridge 网络模式：后端服务名 backend，端口 8080 → proxy_pass http://backend:8080
#   - host 网络模式：  后端在本机，端口 8091 → proxy_pass http://127.0.0.1:8091
# 默认 nginx.conf 是 bridge 模式，host 模式通过环境变量 BACKEND_URL 切换

# 阶段1: 构建
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

# 阶段2: 运行
FROM nginx:1.25-alpine
# 删除默认配置
RUN rm /etc/nginx/conf.d/default.conf
# 复制自定义 nginx 配置模板
COPY nginx.conf /etc/nginx/templates/ssl-monitor.conf.template
# 从构建阶段复制 dist
COPY --from=builder /app/dist /usr/share/nginx/html
# nginx:alpine 支持 envsubst 模板：/etc/nginx/templates/*.template
# 会自动替换 $BACKEND_URL 等变量后输出到 /etc/nginx/conf.d/
EXPOSE 8090
CMD ["nginx", "-g", "daemon off;"]