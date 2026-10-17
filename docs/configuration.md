# 配置和启动

运行配置统一在 [config/application.yml](../config/application.yml)，日志格式在 [config/logback-spring.xml](../config/logback-spring.xml)。各程序会自己选对应配置段，启动时不用到处再找一份配置。

## 怎么启动

在项目根目录运行，需要 JDK 24：

```sh
# 数据 API，默认 8080
./gradlew webRun

# 列出同步任务，执行后退出
./gradlew run --args='show-sync-job-definitions'

# 主策略管理 API，默认 8081
./gradlew mainStrategyRun

# Batch 管理 API，默认本机 9085
./gradlew batchRun

# 只看实时 L2 计划，不连接 MQTT
./gradlew aliyunL2Live --args='plan --symbols 563360'
```

这几个是独立进程。只启动数据 API，不会顺带启动主策略或 Batch。

## 常改的值在哪里

| 要改什么 | 配置位置 |
| --- | --- |
| QuestDB 主机、端口、账号、密码 | `app.questdb` |
| Tushare 地址和 token | `app.tushare` |
| 研究项目目录 | `app.paths.research-root`，默认 `../back-monitor` |
| 数据 API 和主策略 API 的 token | `app.web.api-token` |
| Batch 管理 API 的 token | `jdb.api-token`，在 `batch` 配置段 |
| 主策略方案、文件路径和日批 | `app.sync.batch` |
| Batch 定时任务 | `jdb.schedules`，在 `batch` 配置段 |
| 实时 L2 账号和本地目录 | `l2.username/password`、`l2.root` |
| 日志目录和级别 | `logging.file.path`、`logging.level.root` |

配置已有本项目使用的实际默认值，可以直接改。QuestDB 当前主机是 `100.64.153.106`，HTTP `9000`、PGWire `8812`、数据库 `qdb`；URL 引用这组值，改主机或端口会一起变化。

`app.questdb.direct-connection: true` 让指定数据库绕过本机 JDK 的 HTTP/SOCKS 全局代理，其他站点继续用原代理。HTTP 是否需要 Authorization 也看配置，当前为空。

管理 API 使用 `Authorization: Bearer <配置值>`。Batch 控制工具自己读取同一配置，不用把 token 填进每条命令。文档不复制真实凭据。

实时 L2 的私有订阅账号还需要填写；`plan`、`replay` 无需账号。主策略方案、批准摘要和计划文件也必须真实存在，有默认路径不等于输入已经准备好。

## 换环境或临时覆盖

可以直接改 YAML，也可以用标准环境变量覆盖，例如 `APP_QUESTDB_HOST`、`APP_TUSHARE_TOKEN`、`APP_WEB_API_TOKEN`、`JDB_API_TOKEN`。项目不会自动读取根目录 `.env`。

如果经常在两个环境切换，可以在 YAML 最后加自己的配置段：

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

启动前设置 `SPRING_PROFILES_ACTIVE=office`，只覆盖这一环境不同的值。JVM 参数和命令行参数也按 Spring 规则覆盖。

## 日志和运行文件

日志默认分别在 `logs/questdb-data/`、`logs/main-strategy/`、`logs/questdb-batch/`、`logs/questdb-l2/`。可改 `logging.file.path` 和 `logging.level.root`，或对应环境变量 `LOGGING_FILE_PATH`、`LOGGING_LEVEL_ROOT`。

`var/` 保存 SQLite 运行记录、恢复进度和提交索引。实时 L2 的解析结果和累计检查点在 `l2.root/parquet/batches/`，SQLite 保存原始接收日志、状态和文件引用；备份、恢复时两边一起保留。

Batch 已保存到 SQLite 的定时任务设置，不会自动被 YAML 重置。修改已有任务要走管理操作或迁移。更多操作见[批处理说明](batch.md)和[数据库维护](questdb-maintenance.md)。
