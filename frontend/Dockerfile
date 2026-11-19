# 前端镜像：Node 构建 → Nginx 托管静态文件并反代 /api
#   docker build -t meat-trace/frontend:0.1.0 ./frontend
FROM node:22-alpine AS build
WORKDIR /app

# 镜像内默认走官方源；国内网络可在 deploy/docker-compose.yml 的 build.args 里换成 npmmirror
ARG NPM_REGISTRY=https://registry.npmjs.org
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund --registry=${NPM_REGISTRY}

COPY . .
# build = vue-tsc 类型检查 + 清理 dist + vite build；类型错误会让构建失败，这是有意的
RUN npm run build

FROM nginx:1.27-alpine
LABEL org.opencontainers.image.title="meat-trace-frontend" \
      org.opencontainers.image.version="0.1.0"

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
