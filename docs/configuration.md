# 统一配置

日常只维护根目录的 [`config/application.yml`](../config/application.yml)。文件用 `---` 分段：第一段为公共配置，其余段通过 `spring.config.activate.on-profile` 选择。公共值由各 profile 引用，不在各入口重复填写数据库账号、Token 和共享路径。

| 配置段 | 入口 | 专有设置 |
| --- | --- | --- |
| 公共段 | 所有入口 | `app.questdb`、`app.tushare`、`app.paths`、`app.time`、同步账本路径 |
| `data` | 主服务、数据 CLI、主策略数据 CLI | API、PGWire DataSource、每日调度、研究产物、L2 历史数据路径 |
| `batch` | 独立 Batch、Batch 管理客户端 | `jdb` 元数据、归档、独立调度、百度网盘 |
| `l2` | L2 实时采集器 | 订阅账号、Broker、标的、采集窗口和缓冲参数 |

## 启动

各 Java 入口自动选择自身 profile。IntelliJ 的 working directory 设为项目根目录；无需在每个 Run Configuration 重填公共参数。Gradle 入口也使用项目根目录。

```sh
./gradlew webRun
./gradlew batchRun
./gradlew aliyunL2Live --args='plan --symbols 563360'
```

`data` 是默认 profile。主服务和独立 Batch 仍使用各自的 Java 入口；profile 只决定加载哪些配置，不负责切换 Java 主类。不要同时激活 `data`、`batch`、`l2` 这几个入口 profile。

## 在同一个文件增加环境差异

公共段中直接填写本机的值。需要区分另一套环境时，在文件末尾增加一个只包含差异的段，例如：

```yaml
---
spring:
  config:
    activate:
      on-profile: office
app:
  questdb:
    host: 192.168.1.20
  paths:
    research-root: /srv/back-monitor
```

启动时增加 `SPRING_PROFILES_ACTIVE=office`，或在 IntelliJ VM options 中增加 `-Dspring.profiles.active=office`。入口自身的 `data` / `batch` / `l2` profile 会保留，`office` 段覆盖需要调整的公共值。

共享值的引用会随之变化：主服务 JDBC、Batch HTTP、L2 HTTP 都由 `app.questdb` 推导；研究产物和历史 L2 数据目录由 `app.paths.research-root` 推导。`batch` 的 `jdb.questdb-http-url`、`jdb.tushare-token` 等参数仍可单独覆盖，适合指向隔离实例。

## 覆盖规则与兼容入口

Spring 入口支持环境变量、JVM `-D` 和 `--属性=值` 覆盖。现有 `QUESTDB_HOST`、`APP_PORT`、`APP_SYNC_BATCH_*`、`JDB_*`、`ALIYUN_L2_*` 等环境变量仍可使用。根目录 `.env` 不会自动加载。

L2 和主策略数据 CLI 使用相同的 Spring ConfigData 加载 YAML，但不会为此启动 Spring 容器。它们的业务命令行参数仍优先于配置。L2 的旧 `credentials.env` 兼容文件覆盖 YAML 中的账号、Broker 和 HTTP 设置，进程环境变量再覆盖兼容文件，显式 CLI 参数最终覆盖。新配置可以直接填写在 YAML 的 `l2` 段，不再需要单独的凭据文件。

Batch 管理客户端从同一文件读取 `jdb.api-token` 和 `jdb.api-url`，默认 URL 的端口跟随 `jdb.api-port`。

## 分发包

Gradle 从这一个源文件生成两个应用各自的 classpath `application.yml`，没有需要同步编辑的副本。源码运行时，根目录 `config/application.yml` 作为外部配置优先加载；从其他目录运行分发包时使用打包配置。部署时也可将同一个文件放到进程 working directory 的 `config/application.yml`。

其他位置的外部配置可以通过 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/absolute/path/application.yml` 指定；这也适用于 L2 和主策略数据 CLI。配置文件中的密码、Token 应按所在环境的凭据文件管理方式保存。
