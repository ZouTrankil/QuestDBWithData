# 统一配置

运行配置统一维护在根目录 `config/`：[`application.yml`](../config/application.yml) 保存参数，[`logback-spring.xml`](../config/logback-spring.xml) 保存共享日志输出结构。YAML 用 `---` 分段：第一段为公共配置，其余段通过 `spring.config.activate.on-profile` 选择。公共值由各 profile 引用，不在各入口重复填写数据库账号、Token 和共享路径。

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

## 日志与任务调度

主服务、独立 Batch、L2 采集器的默认日志分别位于 `logs/questdb-data/`、`logs/questdb-batch/`、`logs/questdb-l2/`。主策略数据 CLI 使用 `data` 配置。独立 CLI 在入口初始化同一日志系统，无需为了加载日志启动 Spring 容器。控制台日志写入 stderr，stdout 用于命令结果。

各进程可分别设置 `LOG_DIR`、`LOG_FILE`、`LOG_LEVEL`、`LOG_MAX_FILE_SIZE`、`LOG_MAX_HISTORY`、`LOG_TOTAL_SIZE_CAP`、`LOG_ARCHIVE_PATTERN`。默认单文件 100MB、保留 30 天、归档总量 1GB。`logging.level.<包名>`、日志格式等也可在 YAML 中覆盖。自定义 XML 用 `LOGGING_CONFIG=file:/absolute/path/logback-spring.xml` 指定；仅替换外部 YAML 时，仍可沿用打包的共享 XML。

主策略每日任务和恢复 cron 位于 `data` 段的 `app.sync.batch`。独立 Batch 的五个初始 cron 位于 `batch` 段的 `jdb.schedules`，时区为 `Asia/Shanghai`；`JDB_POST_CLOSE_CRON`、`JDB_SOURCE_CYQ_PERF_CRON`、`JDB_SOURCE_US_TBR_CRON`、`JDB_POST_CLOSE_RECOVERY_CRON`、`JDB_POST_CLOSE_RECOVERY_FINAL_CRON` 可单独覆盖。缺少、拼错任务名或非法 cron 会使配置校验失败。

独立 Batch 首次安装任务时保持暂停；调度总开关仍为 `jdb.scheduling-enabled`。已存在的 Quartz 任务以数据库中的 cron 和暂停状态为准，重启不会用 YAML 覆盖。调整既有 cron 需要显式迁移或通过 Quartz 管理操作更新；不要通过删除运行台账应用配置。台账、检查点、任务状态与算法规则属于运行数据或业务代码，不是部署配置的副本。

有限批处理命令的输入日期、来源、目标路径及单次运行参数继续通过 CLI 指定；数据质量和计算规则保存在对应业务模块。

ETF 原生 MV 的双来源检查由主 Web 服务运行：`app.materialized-views.etf-overview.enabled` 默认开启，`check-interval` 默认 `1m`，`refresh-timeout` 默认 `2m`；对应环境变量为 `ETF_OVERVIEW_REFRESH_ENABLED`、`ETF_OVERVIEW_REFRESH_CHECK_INTERVAL`、`ETF_OVERVIEW_REFRESH_TIMEOUT`。有限 CLI 不启动后台检查。检查点随 `app.sync.ledger-path` 指定的 SQLite 台账部署；旧同步辅助记录的归档目录由 `app.maintenance.legacy-metadata-archive` / `LEGACY_METADATA_ARCHIVE` 设置，默认 `var/retired-sync-metadata`。

## 原生 MV 与旧增量辅助表

维护入口由 Java 执行，不启动全量应用或其他同步任务：

```sh
./gradlew questdbMaintenance --args='status'
./gradlew questdbMaintenance --args='refresh-etf-overview'
./gradlew questdbMaintenance --args='retire-sync-metadata'
```

`mv_etf_market_overview_daily_v1`、`mv_market_breadth_daily_v1`、`mv_retail_sentiment_daily_v1` 是 QuestDB 原生 `MATERIALIZED VIEW`。它们在 `tables()` 中也会出现，因为物化结果需要物理存储；类型应通过 `materialized_views()` 或 `information_schema.tables.table_type` 判断。公开的 `v_*` 是读取这些 MV 的普通视图，不接受应用直接写入。

ETF MV 使用 `REFRESH IMMEDIATE` 处理 `etf_share` 基表增量。它还有 `etf_daily` JOIN 来源，QuestDB 不会仅因右侧历史修正就自动重算所有旧桶。因此 Java 检查两侧的物理身份、事务和 WAL 状态：右侧修正、输出重建或缺失证书执行 `FULL`，仅左侧修订执行 `INCREMENTAL`。刷新完成后按最多 31 天的窗口比对整个历史的业务日期、计数和聚合值；来源或输出在核验中变化时不推进检查点。未变化时复用原证书。

双来源证书存入已有 `app.sync.ledger-path` SQLite 的 `native_mv_checkpoints`，并绑定 QuestDB `current_data_id()`，避免切换数据库时误用其他实例的检查点。读取路径只读本地证书，缺失或过期会明确拒绝读取，不会创建台账、刷新视图或退回旧 QuestDB 状态表。

`retire-sync-metadata` 只清理六个登记名称：`cashflow_period_meta`、`express_period_meta`、`fina_indicator_period_meta`、`forecast_period_meta`、`income_period_meta` 和 `etf_market_overview_mv_refresh`。它先验证 ETF 新证书，然后保存全部表的原始字段、类型、DDL、数据库及表身份、行数和 SHA256；同一份归档同时写入本地 JSON 文件及 SQLite `retired_questdb_sync_metadata`。整组归档成功后，逐表重新检查内容与身份未变化，才执行 DROP。归档目录或 SQLite 写入失败、意外字段、依赖视图或来源变化均阻止删除；重复执行会跳过已移除的表。

五张财务表是旧同步的抓取记录，当前 Java 生产同步没有读写它们。旧 `last_fetched_at` 和 `row_count` 仅作历史归档，不能证明财报业务键覆盖，也不能直接当成已验证的增量进度。现有同步继续使用冻结请求、运行台账、明确的业务范围及物理读回校验；不要用单个 `MAX(end_date)` 跳过中间缺口。

部署需要一起保留并备份配置指定的 SQLite 台账与归档目录。更新到本版本后再执行退休命令；旧版 Java 的 ETF 读取器依赖已退休的 QuestDB 刷新表。

2026-10-16 已在实际 QuestDB 10.0.1 实例执行：五张财务表各 35 行、ETF 旧刷新表 1 行，共 176 行完成双副本与 SHA256 校验后移除。ETF 历史 2,117 个日期聚合逐窗比对通过；原生 MV 为 `valid`、`immediate`，基表及刷新事务均为 8；公开 `v_etf_market_overview_daily` 保持普通 VIEW。重复退休命令没有新增归档或删除其他对象。归档保存在本项目 `var/retired-sync-metadata/` 与 `var/sync-ledger.sqlite3`。专项测试 76 项中 74 项通过、2 项按条件跳过；四模块生产/测试源码编译、分发包及模块/驱动/依赖检查通过，不代表整仓全量测试已通过。

本机到远端 PGWire 的 JDBC 直连仍在认证阶段出现 EOF，本次通过临时本机 TCP 转发连接同一远端实例完成 Java 维护，仅在执行命令时覆盖 `SPRING_DATASOURCE_URL`，未修改部署数据库地址。实际部署应验证其 PGWire 连接正常；后台双来源检查在更新后的主 Web 服务启动后运行。

## 分发包

Gradle 从 `config/` 生成两个应用的 classpath 配置，源码资源目录不再各自维护 YAML 或日志 XML。根应用、独立 Batch 及子模块分发包同时带有可编辑的 `config/application.yml` 和 `config/logback-spring.xml`。源码运行时，根目录 YAML 作为外部配置优先加载；从其他目录运行分发包时可使用打包配置。

部署时可给不同进程各放一套 `config/`，保留同样的配置结构并填写环境差异。指定部署目录：

```sh
export SPRING_CONFIG_ADDITIONAL_LOCATION=file:/srv/questdb-batch/config/
export LOGGING_CONFIG=file:/srv/questdb-batch/config/logback-spring.xml
export LOG_DIR=/var/log/questdb-batch
```

目录配置位置保留结尾 `/`。YAML 的入口 profile 会自动选择，各进程的目录和环境变量可以独立设置。

其他位置的外部配置可以通过 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/absolute/path/application.yml` 指定；这也适用于 L2 和主策略数据 CLI。配置文件中的密码、Token 应按所在环境的凭据文件管理方式保存。
