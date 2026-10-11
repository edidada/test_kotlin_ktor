# test_kotlin_ktor — ECShop REST API (Kotlin + Ktor)

![CI](https://github.com/edidada/test_kotlin_ktor/actions/workflows/ci.yml/badge.svg)

以 [ECShop](third_party) 旧版 PHP 商城的 URL/表结构为参照，用 Kotlin + Ktor 3 + SQLite 重写的前端无关 REST API。覆盖 `docs/02_url_api_list.md` 的 M0–M4 全部端点（M5 促销写操作按计划排除），金额一律十进制字符串/整数分，错误与鉴权契约见 `docs/03`。

## 模块结构（Gradle 多工程）

```
:core   ecshop.db（SQLite 单连接+互斥锁、SQL 助手）、ecshop.http（错误/分页/Money/鉴权/密码）、
        AppConfig、AppServices
:api    ecshop.routes 全部路由模块 + appModule 装配（只依赖 :core）
:app    进程入口 App.kt（embeddedServer Netty）、resources（schema/种子 SQL、logback）、
        application + shadow 打包
buildSrc  共享约定插件 kotlin-jvm（toolchain 参数化：-PecJavaVersion，默认 17）
```

依赖方向单一：`:app → :api → :core`。

## 快速开始

```bash
./gradlew build                                   # 编译 + 测试 + 产出 fat jar
java -jar app/build/libs/app-all.jar              # 默认 0.0.0.0:8080
curl -fsS http://127.0.0.1:8080/healthz           # {"status":"ok"}
```

首次启动自动建表并写入幂等种子数据（示例商品 12/14 等）。环境变量：`EC_PORT`、`EC_HOST`、`EC_DB_PATH`（默认 `data/ecshop.db`）、`EC_BASE_URL`、`EC_PAYMENT_CALLBACK_HMAC_SECRET`。

## 打包产物

| 任务 | 产物 | 说明 |
| --- | --- | --- |
| `:app:shadowJar` | `app/build/libs/app-all.jar` | **单一 fat jar**，`java -jar` 直接运行（已 mergeServiceFiles） |
| `:app:distZip` / `installDist` | `app.zip`/`app.tar` 或 `app/build/install/app` | bin 脚本 + lib 多 jar 的标准布局 |
| `:app:jar` | `app.jar` | 瘦 jar，不含依赖，不能独立运行 |

## 测试与验收

```bash
./gradlew test                                        # 单元测试
bash scripts/curl_cart_example.sh                     # 购物车乐观锁 409 端到端
bash scripts/curl_full_acceptance.sh                  # docs/06 全流程 60+ 断言
```

curl 清单与实现差异记录见 `docs/06_curl_testing.md` 附录；本机无 jq 时脚本自动退回 python。

## 文档索引（docs/）

- `01_libraries` / `04_architecture` / `05_detailed_design`：选型与架构
- `02_url_api_list`：URL 清单（实现依据）；`09_url_completion_matrix`：完成度矩阵
- `03_api_contract`：API 契约与错误模型；`10_api_error_factories`
- `06_curl_testing`：curl 验收 + 本地验收笔记（含实现差异附录）
- `07_deployment`：**Linux（systemd）与 Docker（根目录 `Dockerfile`）部署方案**；`07_platform_compatibility`；`docs/deploy/`（compose 参考）
- `08_first_url_tutorial`：第一个 URL 的落地教程；`09_http_server_adapters`：服务器适配器
- `docs/sql/`：ECShop 原始表结构参考（MySQL）

## CI

`.github/workflows/ci.yml`：ubuntu / windows / macos × JDK 17 / 21，矩阵构建 `./gradlew build -PecJavaVersion=<jdk>`。

## Gradle 约定

版本目录在 `gradle/libs.versions.toml`；共享构建逻辑在 `buildSrc` 约定插件；启用构建缓存与配置缓存（见 `gradle.properties`）。日常命令：`./gradlew build | check | clean`，始终使用 Gradle Wrapper。
