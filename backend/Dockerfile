# 构建上下文必须是**仓库根目录**（pom 里要拷 ../db/migration 与 ../contract/build）
#   docker build -f backend/Dockerfile -t meat-trace/backend:0.1.0 .
# compose 里已按此配置：context: .. / dockerfile: backend/Dockerfile

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 拉依赖：改业务代码不会让依赖层缓存失效
COPY backend/pom.xml backend/pom.xml
# go-offline 偶有插件解析告警，不影响后面的正式构建，失败不阻断
RUN mvn -B -q -f backend/pom.xml dependency:go-offline || true

COPY backend/src backend/src
COPY db/migration db/migration
COPY contract/build contract/build

# 不要用 spring-boot:run：仓库路径含中文时 classpath 解析会失败，且容器里只需一个 jar
RUN mvn -B -f backend/pom.xml package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="meat-trace-backend" \
      org.opencontainers.image.description="高校食堂肉类供应链溯源系统 · 后端" \
      org.opencontainers.image.version="0.1.0"

# curl 用于 HEALTHCHECK；tzdata 保证 Asia/Shanghai 时区可用
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 -m appuser

WORKDIR /app
COPY --from=build /build/backend/target/meat-trace-0.1.0.jar app.jar
RUN mkdir -p /app/logs && chown -R appuser:appuser /app
USER appuser

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

EXPOSE 8080

# 存活探针只打 /api/health（不查库、不查链），避免链抖动导致容器被反复重启
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
