# syntax=docker/dockerfile:1
# 多阶段构建：build 阶段产出单一 fat jar（:app:shadowJar），runtime 阶段只带 JRE。
# 用法见 docs/07_deployment.md。

########## build ##########
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# 先拷构建脚本与源码树（wrapper 依赖 settings/buildSrc，整体 COPY 保证可复现）
COPY . .

# 产出 app/build/libs/app-all.jar（含全部依赖，可 java -jar 直接运行）
RUN ./gradlew --no-daemon :app:shadowJar

########## runtime ##########
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home /app --shell /usr/sbin/nologin ecshop

WORKDIR /app
COPY --from=build /src/app/build/libs/app-all.jar /app/app.jar

# SQLite 数据目录挂 volume；生产必须持久化，否则容器删除即丢库
RUN mkdir -p /app/data && chown -R ecshop:ecshop /app
VOLUME /app/data

ENV EC_HOST=0.0.0.0 \
    EC_PORT=8080 \
    EC_DB_PATH=/app/data/ecshop.sqlite3 \
    EC_BASE_URL=http://localhost:8080 \
    EC_PAYMENT_CALLBACK_HMAC_SECRET=""

USER ecshop
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${EC_PORT}/healthz" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
