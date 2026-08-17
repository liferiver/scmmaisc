# ===== 构建阶段：Vue 生产构建（含 vue-tsc 类型检查） =====
FROM node:20-alpine AS build
WORKDIR /build

# 利用 package-lock.json 精确安装依赖（npm ci），利用 Docker 层缓存
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ===== 运行阶段：Nginx 静态服务 + /api 反向代理 =====
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
