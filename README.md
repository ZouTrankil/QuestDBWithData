# QuestDBWithData

基于 Java 的数据采集与计算项目，提供 WebFlux API、数据命令行工具、独立 Batch 应用、原生 Level2 处理和持久化主策略运行时。业务数据存储在 QuestDB 中，本地 SQLite 文件保存执行状态和恢复记录。

## 项目结构

```text
data-core/              共享契约与基础类型
data-app/               数据 owner、CLI、WebFlux API 与 QuestDB 访问
batch-app/              独立 Batch 应用与原生 L2 计算
main-strategy-runtime/  组合 data-app 与 batch-app 的主策略运行时
config/                 共享应用配置
deploy/                 部署脚本与配置示例
docs/                   架构、数据约定与操作指南
tools/                  可复用的生成、L2 处理与测试工具
```

根 Gradle 项目聚合这四个模块，并提供统一的启动命令。模块依赖方向、包职责与历史文件清理记录见[代码结构说明](docs/code-structure.md)。

默认构建产物统一放在顶层 `build/` 下，各模块使用 `build/modules/<module>/` 子目录。根项目的 JAR、分发包和聚合报告保留原有位置。`./gradlew :clean` 清理全部构建产物，`./gradlew :data-app:clean` 只清理该模块的产物。

## 环境要求

- JDK 24。
- 拥有 `stock_basic` 访问权限的 Tushare Pro Token，在 `config/application.yml` 中配置。
- 执行 QuestDB 相关命令前，在 `config/application.yml` 中配置 QuestDB 连接信息。

## 运行

在 `config/application.yml` 中设置 `app.tushare.token` 和 QuestDB 连接信息，然后执行：

```bash
./gradlew run --args='sync-stock-basic-questdb'
```

该命令创建或使用隔离测试表 `java_tushare_stock_basic_qwp_test`，写入一份按 UTC 日期划分的快照，并回读该快照的行数。表以 `(snapshot_ts, ts_code)` 为去重键，相同日期和证券代码的数据会更新或插入。行数轮询仅用于有限的可见性检查：如果行数未变，无法据此确认重复同步时的新字段值已经生效。该命令不操作生产表。旧命令 `sync-stock-basic-jdbc` 仍作为别名保留。

QuestDB 表和最新快照视图由 `data-app/src/main/resources/db/migration/questdb/` 下的 Flyway SQL 迁移管理。可以显式执行 `./gradlew run --args='migrate-questdb-schema'`，数据库同步和最新快照查询命令也会在使用前执行迁移。接入已有数据库时，需要先核对 schema 并显式设置 baseline，详见 [Flyway 管理指南](docs/flyway-schema-management.md)。

QuestDB 写入使用原生 QWP WebSocket 客户端，连接 YAML 中配置的 QWP 端口，通常为 `9000`。PostgreSQL JDBC/PGWire 通常使用 `8812` 端口，负责创建表结构，并轮询已提交的快照是否可以查询。QWP 写入是异步的，命令最多等待 10 秒；如果行数仍未达到预期，则报告失败。

所有入口共用 [config/application.yml](config/application.yml)。第一段 YAML 定义共享的 QuestDB、Tushare 和路径配置，`data`、`batch`、`l2` profile 段只保留各自的专有设置。每个入口自动选择对应的 profile，环境变量、JVM 属性和显式 CLI 参数可以覆盖 YAML 配置。根目录的 `.env` 不会自动加载。profile、覆盖优先级和部署方式见[统一配置说明](docs/configuration.md)。

其他参考文档：

- [QuestDB 使用约定](docs/questdb-usage.md)。
- [模型与 schema 约定](docs/model-schema-conventions.md)：时间、主键语义与 Pydantic 到 Java 的转换。
- [代码结构说明](docs/code-structure.md)：模块与包职责。
- [视图与恢复说明](docs/view.md)：视图、WAL 恢复和数据工程记录。

历史迁移任务卡、过期状态快照和一次性验证脚本已在仓库清理中移除。当前行为以模块代码、已注册的数据集和任务定义，以及持续维护的操作指南为准。仅有 schema 映射不代表已经满足生产运行条件。

## Spring WebFlux API

在 `application.yml` 中设置 `app.web.enabled: true` 后，无参数启动 `com.zoutrankil.data.QuestDataApplication` 即可运行 WebFlux API，也适用于 IntelliJ 启动。从 Gradle 启动：

```bash
APP_API_TOKEN='replace-with-at-least-24-characters' ./gradlew webRun
```

服务监听 `APP_PORT`，默认端口为 `8080`。`GET /api/v1/health` 对外开放，供健康探针使用。设置 `APP_API_TOKEN` 后，其余 `/api/v1/**` 路由均要求 `Authorization: Bearer <token>`；Token 留空仅适合本地开发。

指定命令时，CLI 仍通过同一个主类运行，例如：

```bash
./gradlew run --args='show-sync-job-definitions'
```

### 持久化主策略每日批处理

主 Spring Web 应用在收到 `ApplicationReadyEvent` 后自动启动 `main_strategy_daily`，也适用于 IntelliJ 无参数启动。`app.sync.batch.enabled` 和 `startup-catchup` 默认均为 `true`。各阶段依次执行已有 Java 来源 owner、市场情绪计算、市场状态计算、QuestDB 回测物化视图验收和物理数据验收。来源请求复用已有 WebFlux 客户端和共享请求配额。

交易日历以 `Asia/Shanghai` 时区 20:30 的来源截止时间为界，选择最近已完成的上交所交易日。Quartz 在 20:35 触发任务，并在 21:00 至 23:45 之间每 15 分钟重试符合条件的未完成工作，重试次数有明确上限。SQLite 保存业务实例、阶段凭证、Spring Batch 执行记录和 Quartz 状态。复用已完成阶段前会回读验证；写入结果不确定时，必须由原 owner 执行对账恢复。仅凭进程退出码不能判定批处理完成。

回测数据只查询 `mv_backtest_daily`。它是 QuestDB 原生物化视图，直接关联 `stk_factor`、`stk_limit`、`stk_suspend` 和 `stk_st_daily`，以 `REFRESH IMMEDIATE` 自动更新。保留 13 个字段与停牌补行：停牌日使用此前最后价格，成交量和成交额为零。建视图 SQL 位于 [`mv_backtest_daily.sql`](data-app/src/main/resources/sql/mv_backtest_daily.sql)。

`backtest_daily` 结果表和 `v_backtest_daily` 普通视图已退役；应用不再生成回测 stage/backup 表，不运行 Java 回测物化、发布、恢复或刷新命令。每日流水线的 `BacktestDaily` 阶段只等待并验证数据库 MV。

QuestDB 只由指定基表触发刷新。这里使用 `stk_limit`，避免把历史 ASOF 取价范围裁掉；每日采集顺序为先写行情、停牌和 ST，再写涨跌停。主策略与盘后采集均执行该顺序。其他三张表单独变更不会触发 MV；补采或更正时也必须按完整来源批次写入对应日期，不能把基表进度等同于四表变化都会自动联动。读取会检查 MV 定义、13 列类型、WAL 和来源更新时间，拒绝未完成刷新或已知晚到来源。

以下旧数据集已从 Java 目录和读取分组 API 中移除：

- `backtest_daily_cache`
- `backtest_daily_cache_coverage`
- `market_breadth_daily_cache`
- `retail_sentiment_daily_cache`

回测数据通过 `mv_backtest_daily` 读取，市场宽度通过 `v_market_breadth_daily` 读取，散户情绪通过 `v_retail_sentiment_daily` 读取；后两者是原生物化视图的读取别名。ETF 概览缓存及其共享的 `market_barometer_cache_coverage` 凭证仍在使用。

市场情绪和市场状态仍使用计算结果表，由现有 Java `MATERIALIZE` 任务管理，两者都已注册为可每日执行的任务。

启动应用前，在 [`config/application.yml`](config/application.yml) 设置 QuestDB 地址和 `app.sync.ledger-path`。

重启时保留该来源账本，其中保存发布依据和恢复证据。调度器使用独立的元数据库，默认路径为 `var/main-strategy-batch.sqlite`。Web 模式将主策略 owner 绑定到正式表名；有限生命周期的 CLI 命令仍保留隔离表默认值。

通过以下接口控制和查看同一个运行中的流水线：

- `GET /api/v1/batch/main-strategy`：查看流水线状态。
- `GET /api/v1/batch/main-strategy/instances/{instanceId}`：查看业务实例。
- `POST /api/v1/batch/main-strategy/catch-up`：补齐未完成任务。
- `POST /api/v1/batch/main-strategy/instances/{instanceId}/reconcile-publication`：对账恢复发布结果。
- `POST /api/v1/batch/main-strategy/pause`：暂停。
- `POST /api/v1/batch/main-strategy/resume`：恢复。

这些接口沿用现有 API 鉴权。暂停状态持久化保存，重启后仍然有效。设置 `APP_SYNC_BATCH_ENABLED=false` 可禁用该运行时；设置 `APP_SYNC_BATCH_STARTUP_CATCHUP=false` 则保留定时调度，但不在启动时补齐任务。

原生发布结果不确定时，需要显式调用对账恢复接口，请求体示例：

```json
{"stage":"MarketSentimentDaily","runId":"original-run-id","writerStopped":true}
```

恢复要求写入方已停止，并核对原始冻结运行、未变化的来源证据和物理发布结果。运行时保留原有不确定状态凭证，追加已验证的恢复关联记录；之后调用 `catch-up` 继续剩余阶段。普通重试不会重新执行结果不确定的发布。

常规每日任务不要求保留已经验收的 Level2 临时 JSONL 目录。保留的导入和归档凭证、未变化的 WAL 进度，以及当前完整的类型化质量指纹，可用于验证已导入日期。重新对比来源或重新导入时，需要重新生成已删除的输出。后续 Level2 日期必须有各自已验收的清洗和导入证据，缺少该依赖时批处理会等待。ETF 披露数据使用目标日期之前实际最新的公告，时效性和完整性限制单独报告。全市场融资数据不完整时仍作为可选来源，不会被当作完整来源使用。

常用冒烟测试接口包括 `GET /api/v1/info`、`GET /api/v1/datasets`、`GET /api/v1/jobs`、`GET /api/v1/stock-basic/latest` 和 `POST /api/v1/stock-basic/sync`。后两个接口访问 QuestDB 或 Tushare，使用 `bounded-elastic` 调度器执行，避免阻塞数据库或客户端调用占用 WebFlux 事件循环。

在 IntelliJ 中打开 [api.http](api.http)，执行其中的请求，可检查服务健康状态、QuestDB 连通性、数据集定义和有数量上限的实际数据样本。样本接口接受 `limit=1..100`，仅读取已注册且具备 `READ` 能力的数据集。股票同步请求会写入数据，因此在文件中默认注释。

Flyway 配置、固定版本与已有数据库的接入方式见 [Flyway schema 管理](docs/flyway-schema-management.md)。定义新模型及其读写映射，参见[模型开发流程](docs/model-development-workflow.md)。运行时插件测试已通过；迁移尚未在真实 QuestDB 实例上验证。

Spring Batch 任务元数据使用本地 SQLite 数据库，不需要 PostgreSQL 服务。PostgreSQL JDBC 驱动仅在主应用中用于 QuestDB PGWire 的 schema 和查询操作，独立 Batch 分发包不包含该驱动。

此前从 QuestDB 10.0.1 导出的 `schema-export/` 历史快照已清理。使用 `tools/generate_domain_models.py` 生成 Java schema 映射时，需要通过 `--catalog` 指定另行准备的数据对象目录 JSON 文件。

当前 `stock_basic` 请求使用 `exchange=''` 和 `list_status='L'`，表示当前上市证券范围，不代表某个历史时点的证券范围。

## 本地验证

```bash
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew :test --console=plain
./gradlew check --console=plain
```

真实环境验收测试需要显式配置环境变量和外部服务。`:data-app:retainedParityTest` 还依赖原始采集样本，当前仓库不提供完整样本，需要另行准备。应用重启时应保留 `var/` 和 SQLite 恢复账本。`artifacts/`、`build/` 和 `logs/` 下的新本地运行产物由 Git 忽略。
