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

启动前设置 `SPRING_PROFILES_ACTIVE=office` 就会读这段。环境变量、JVM 参数和命令行参数也能覆盖 YAML。项目不会自动读取根目录的 `.env`，密码和 Token 请放在你本机或部署环境的凭据配置里。

## 日志和运行状态

日志默认分别写到 `logs/questdb-data/`、`logs/main-strategy/`、`logs/questdb-batch/` 和 `logs/questdb-l2/`。改日志目录用 `LOG_DIR`，改级别用 `LOG_LEVEL`。

`var/` 下会保存 SQLite 运行状态和提交索引。实时 L2 数据及累计检查点存放在 `l2.root/parquet/batches/`，使用标准 Parquet；SQLite 仅保留原始接收 WAL 与提交引用。重启和备份要同时保留 SQLite 与它引用的 Parquet 目录。Batch 已写进 SQLite 的定时任务设置不会自动跟着 YAML 改；要改已有任务，需要通过管理操作或迁移来做。

主策略调度看 `app.sync.batch`；Batch 的时间表看 `batch` 配置段里的 `jdb.schedules`。QuestDB 的维护操作和历史清理记录在[单独页面](questdb-maintenance-record-20261017.md)。
