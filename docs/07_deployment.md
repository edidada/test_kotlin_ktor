# 部署方案：Linux 与 Docker

本文覆盖多模块重构（`:core` / `:api` / `:app`）后的打包产物说明与两种部署路径。所有环境变量与 `docs/06_curl_testing.md` 附录一致。

## 打包产物：jar 到底是不是只有一个？

`./gradlew build` 后各模块的 jar：

| 产物 | 内容 | 能否直接运行 |
| --- | --- | --- |
| `core/build/libs/core.jar`、`api/build/libs/api.jar` | 库 jar，只含本模块 class，不含依赖 | 否（供其它模块引用） |
| `app/build/libs/app.jar` | 瘦 jar（thin jar），只含 `App.kt`/资源，**不含任何依赖** | 否，直接 `java -jar` 会 ClassNotFound |
| `app/build/libs/app-all.jar` | **shadow 插件产出的单一 fat jar**，打包了 Kotlin/kotlinx/Ktor/SQLite JDBC/logback 全部运行时依赖 | 是：`java -jar app-all.jar` |
| `app/build/distributions/app.zip` / `app.tar` | application 插件 dist 包：`bin/app` 启动脚本 + `lib/` 下多 jar + `lib/` 扁平依赖 | 解压后用 `bin/app` 运行 |
| `app/build/distributions/app-shadow.zip` / `.tar` | shadow 的 dist（内含 app-all.jar 单文件） | 是 |

结论：默认 `jar` 任务确实只产出"一个" jar，但它是瘦 jar 不能独立跑；本项目以 `:app:shadowJar` 的 `app-all.jar` 作为单一可执行产物（`build` 任务已挂 `dependsOn(shadowJar)`），dist 包保留给需要标准 Gradle 布局的场景。fat jar 已做 `mergeServiceFiles()`，Ktor/Netty 的 `META-INF/services` 合并正常，sqlite-jdbc 原生库可在 `java -jar` 下自解压加载（已实测启动 + 注册 + 商品接口通过）。

## 运行时配置（env）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `EC_HOST` | `0.0.0.0` | 监听地址 |
| `EC_PORT` | `8080` | HTTP 端口 |
| `EC_DB_PATH` | `data/ecshop.db`（部署时应显式指定，本文示例统一用 `ecshop.sqlite3`） | SQLite 文件路径，进程启动时自动建表并跑幂等种子（重复执行安全） |
| `EC_BASE_URL` | `http://localhost:$EC_PORT` | sitemap/feed 绝对链接前缀，生产设为对外域名 |
| `EC_PAYMENT_CALLBACK_HMAC_SECRET` | dev 值 | 支付回调验签密钥，**生产必须覆盖** |

## 方案 A：裸 Linux（systemd + dist 包）

适用：单机、无容器基础设施，想要标准运维形态。

1. 构建（任何有 JDK 17+ 的机器）：

```bash
./gradlew :app:installDist          # 产出 app/build/install/app/{bin,lib}
tar czf ecshop-app.tgz -C app/build install/app
```

2. 安装到 `/opt/ecshop`，以专用用户运行：

```bash
sudo useradd --system --home /opt/ecshop --shell /usr/sbin/nologin ecshop
sudo mkdir -p /opt/ecshop /var/lib/ecshop
sudo tar xzf ecshop-app.tgz -C /opt/ecshop --strip-components=1
sudo chown -R ecshop:ecshop /opt/ecshop /var/lib/ecshop
```

3. `/etc/ecshop/ecshop.env`（600 权限，别放进 git）：

```ini
EC_PORT=8080
EC_DB_PATH=/var/lib/ecshop/ecshop.sqlite3
EC_BASE_URL=https://shop.example.com
EC_PAYMENT_CALLBACK_HMAC_SECRET=<从密钥管理注入>
```

4. `/etc/systemd/system/ecshop.service`：

```ini
[Unit]
Description=ECSHOP Kotlin Ktor API
After=network.target

[Service]
Type=simple
User=ecshop
Group=ecshop
EnvironmentFile=/etc/ecshop/ecshop.env
ExecStart=/opt/ecshop/bin/app
Restart=on-failure
RestartSec=2
NoNewPrivileges=true
ProtectSystem=full
ProtectHome=true
ReadWritePaths=/var/lib/ecshop
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

5. 启动与验收：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ecshop
curl -fsS http://127.0.0.1:8080/healthz          # {"status":"ok"}
bash scripts/curl_full_acceptance.sh             # 全部 PASS 再放量
```

注意：SQLite 单文件 + 进程内单连接互斥锁，**只允许一个服务实例写库**；要横向扩展请先换 PostgreSQL/MySQL 并改造 `:core` 的 `Db`。反代（nginx/caddy）只做 TLS 与静态资源即可。

## 方案 B：Docker（仓库根 `Dockerfile`）

多阶段：`eclipse-temurin:17-jdk` 跑 `./gradlew :app:shadowJar`，运行层只含 `17-jre` + `app-all.jar`，非 root 用户、内置 `/healthz` 健康检查。

1. 构建与运行：

```bash
docker build -t ecshop-api:latest .
docker run -d --name ecshop-api \
  -p 8080:8080 \
  -e EC_BASE_URL=https://shop.example.com \
  -e EC_PAYMENT_CALLBACK_HMAC_SECRET='<从密钥管理注入>' \
  -v ecshop-data:/app/data \
  --restart unless-stopped \
  ecshop-api:latest
docker exec ecshop-api curl -fsS http://127.0.0.1:8080/healthz
```

数据在命名卷 `ecshop-data`（对应容器内 `/app/data/ecshop.sqlite3`），删容器不删库；`docker rm` 时想连库一起销毁才用 `-v` 不加 `--rm` 的卷管理。

2. compose 形态（`docker-compose.yml`，按需落盘）：

```yaml
services:
  api:
    build: .
    ports: ["8080:8080"]
    environment:
      EC_BASE_URL: ${EC_BASE_URL:-http://localhost:8080}
      EC_PAYMENT_CALLBACK_HMAC_SECRET: ${EC_PAYMENT_CALLBACK_HMAC_SECRET:?required}
    volumes: [ecshop-data:/app/data]
    restart: unless-stopped
volumes:
  ecshop-data:
```

3. 升级/回滚：`docker build -t ecshop-api:<git-sha> .` → `docker compose up -d`（滚动换容器，卷不动）；回滚即把 tag 指回旧 sha。镜像 tag 用 commit sha，别用 `latest` 上线。

4. 国内网络提示：build 阶段 Gradle 依赖走已在用的阿里云镜像配置；基础镜像拉取慢可换 `docker.m.daocloud.io/library/eclipse-temurin:17-jre` 等镜像源，仅改 `FROM` 行。

## 验证清单（两种方案通用）

- `curl -fsS $BASE_URL/healthz` 返回 `{"status":"ok"}`
- `bash scripts/curl_cart_example.sh`（乐观锁 409 路径）
- `bash scripts/curl_full_acceptance.sh`（docs/06 全流程 60+ 断言，需 sqlite3 CLI 提取 outbox token）
- 生产首单后检查 `EC_PAYMENT_CALLBACK_HMAC_SECRET` 能验通 mockpay 回调（错误签名必须 401）
