# 配置和启动

配置都放在 [`config/application.yml`](../config/application.yml)，日志格式放在 [`config/logback-spring.xml`](../config/logback-spring.xml)。各程序会自己选好要用的配置段，你不用在每个启动命令里重复指定。

## 启动命令

在项目根目录运行：

```sh
./gradlew webRun
./gradlew run --args='show-sync-job-definitions'
./gradlew mainStrategyRun
./gradlew batchRun
./gradlew aliyunL2Live --args='plan --symbols 563360'
```

数据 API 默认用 `8080` 端口，主策略管理 API 默认用 `8081`。它们是两个独立进程；只运行 `webRun` 不会启动主策略。L2 的 `plan` 只看采集计划，不会连 MQTT。

## 修改默认值

日常直接编辑 `config/application.yml`：`app.questdb` 当前连接 `100.64.153.106`，HTTP 端口 `9000`、PGWire 端口 `8812`，数据库 `qdb`；HTTP 无需额外 Authorization。`app.questdb.direct-connection: true` 会让该数据库绕过本机 JDK 的 HTTP/SOCKS 全局代理，避免私网 PGWire 被代理断开；其他站点仍沿用原代理。HTTP/JDBC URL 引用这组参数，改主机或端口会一起生效。`app.paths.research-root` 默认是相邻的 `../back-monitor`，不再指向 Windows 盘符。

`app.web.api-token` 用于数据和主策略 API，`jdb.api-token` 用于 Batch 管理 API。两个值已分别生成；调用时使用 `Authorization: Bearer <配置值>`，Batch 控制工具自动读取同一配置。实时 L2 的私有订阅账号尚未提供，`plan`、`replay` 无需账号；实际订阅前需要填写 `l2.username/password`。主策略的已验收方案文件也必须实际存在，配置不会伪造这些输入。

## 换一套环境

可以在 `application.yml` 最后加一段，只写跟当前环境不同的值：

```yaml
---
spring:
  config:
    activate:
      on-profile: office
app:
  questdb:
    host: 192.168.1.20
```

启动前设置 `SPRING_PROFILES_ACTIVE=office` 就会读这段。环境变量、JVM 参数和命令行参数也能覆盖 YAML。项目不会自动读取根目录的 `.env`。这份 YAML 已填入本项目使用的数据库地址、已有 Tushare token，以及已生成的两个独立管理 API token；不依赖另外一份凭据文件。可以直接修改对应值。环境变量覆盖使用 Spring 标准名称，例如 `APP_QUESTDB_HOST`、`APP_TUSHARE_TOKEN`、`APP_WEB_API_TOKEN`、`JDB_API_TOKEN`，无需修改 Java 代码。

## 日志和运行状态

日志默认分别写到 `logs/questdb-data/`、`logs/main-strategy/`、`logs/questdb-batch/` 和 `logs/questdb-l2/`。可直接改 `logging.file.path` 和 `logging.level.root`；相应环境变量为 `LOGGING_FILE_PATH` 和 `LOGGING_LEVEL_ROOT`。

`var/` 下会保存 SQLite 运行状态和提交索引。实时 L2 数据及累计检查点存放在 `l2.root/parquet/batches/`，使用标准 Parquet；SQLite 仅保留原始接收 WAL 与提交引用。重启和备份要同时保留 SQLite 与它引用的 Parquet 目录。Batch 已写进 SQLite 的定时任务设置不会自动跟着 YAML 改；要改已有任务，需要通过管理操作或迁移来做。

主策略调度看 `app.sync.batch`；Batch 的时间表看 `batch` 配置段里的 `jdb.schedules`。QuestDB 的维护操作和历史清理记录在[单独页面](questdb-maintenance-record-20261017.md)。
