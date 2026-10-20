# 本地 Debug：从入口看懂系统

先调试“列定义”，再看查询 API、Batch 和主策略，最后看真实采集。每次只启动正在研究的进程。本文按 2026-10-20 的代码核对；接口是 JSON API，当前没有任务管理或模型浏览网页。

## 1. IDEA 的公共设置

用 Gradle 导入根工程，Project SDK 和 Gradle JVM 选 JDK 24。创建 `Application` 运行配置：

| IDEA 配置项 | 填什么 |
| --- | --- |
| Main class | 下表对应的完整类名 |
| Use classpath of module | 对应入口模块的 main classpath；IDEA 显示名称可能带项目名前缀 |
| Working directory | `$PROJECT_DIR$`，即包含 `config/` 的仓库根目录 |
| Program arguments | 各小节给出的命令或 `--key=value` |
| VM options | 开始可留空；排查内存时再设置 `-Xmx` |

点 Debug 启动。Spring 的 `--debug` 只增加诊断日志，不会让 JVM 等待断点。业务参数用 `--symbols 563360` 这类形式；Spring 配置覆盖用 `--server.port=18080`，不能写成 `--server.port 18080`。

配置从根 `config/application.yml` 导入，市场和应用 profile 由入口选择。本机连接、账号和 token 放被 Git 忽略的 `config/application-local.yml`；新环境参考[空白配置](../config/application-local.example.yml)。环境变量和命令行仍可覆盖，根 `.env` 不会自动加载。详见[配置和启动](configuration.md)。

## 2. 入口速查

| 用途 | Main class | Classpath 模块 | 参数和默认端口 |
| --- | --- | --- | --- |
| A 股数据 API / 有限 CLI / 托管 Batch | `com.zoutrankil.data.QuestDataApplication` | `:markets:cn:data-app` | 无业务命令启动 API，8080；有命令执行后退出 |
| 主策略 API / 日批 | `com.zoutrankil.strategy.MainStrategyApplication` | `:markets:cn:main-strategy-runtime` | 无必填参数，8081；调试时关闭日批 |
| 主策略产物 CLI | `com.zoutrankil.data.strategy.cli.MainStrategyDataCli` | `:markets:cn:main-strategy-runtime` | 先用 `status` |
| 实时 L2 | `com.zoutrankil.data.l2live.bootstrap.AliyunL2LiveMain` | `:markets:cn:l2-live-app` | 先用 `plan --symbols 563360`，无 Web 端口 |
| 美股有限采集 CLI | `com.zoutrankil.market.us.app.UsMarketDataMain` | `:markets:us:data-app` | 先用 `definitions`，无 Web 端口 |
| BTC/ETH 有限采集 CLI | `com.zoutrankil.market.btc.app.CryptoDataMain` | `:markets:btc:data-app` | `collect --through YYYY-MM-DD`，会真实采集和写入 |

Batch 没有独立常驻入口。它由 A 股数据进程按 `jdb.enabled` 托管，在同一 JVM 中使用独立 Spring 容器和 SQLite，管理 API 默认监听本机 9085。`webRun` 和 `batchRun` 是同一个 main 的两种启动方式，不要重复启动占用相同端口或账本。

## 3. 第一步：看模型和任务怎样注册

Main class 选 `QuestDataApplication`，配置名可叫“CN-定义”，Program arguments：

```text
show-dataset-definitions
```

看任务时改为：

```text
show-sync-job-definitions
```

等价的终端命令：

```sh
./gradlew run --args='show-dataset-definitions'
./gradlew run --args='show-sync-job-definitions'
```

建议按顺序放断点：

1. [QuestDataApplication.main](../markets/cn/data-app/src/main/java/com/zoutrankil/data/QuestDataApplication.java)：看参数如何决定常驻或有限模式。
2. [StartupArguments.parse](../platform/runtime-support/src/main/java/com/zoutrankil/data/bootstrap/StartupArguments.java)：看业务参数与 Spring 配置如何拆开。
3. [QuestDataStartup.run](../platform/runtime-support/src/main/java/com/zoutrankil/data/bootstrap/QuestDataStartup.java) 和同目录 `RuntimeModeListener.onApplicationEvent`：看 `app.runtime.persistent` 与 Web 类型如何确定。
4. [CliCommandRegistry.execute](../markets/cn/data/src/main/java/com/zoutrankil/data/cli/CliCommandRegistry.java) → [CatalogCommands.execute](../markets/cn/data/src/main/java/com/zoutrankil/data/cli/CatalogCommands.java)：看命令分发与定义输出。

观察 `datasetId`、`objectName`、`columns`、`businessKey`、`capabilities`；字段中的业务日期和真实时间点要分清。输出后进程关闭是正常行为。有限 CLI 不启动 Batch，也不因列出定义就调用同步任务；Spring 初始化仍可能受本机配置或外部连接影响。

## 4. 第二步：看模型、字段和真实样本查询

Main class 仍选 `QuestDataApplication`，配置名“CN-查询API”，Program arguments：

```text
--jdb.enabled=false
--app.sync.financial.enabled=false
--app.sync.batch.enabled=false
--app.materialized-views.etf-overview.enabled=false
```

最后一项关闭默认启用的 ETF MV 后台检查。参数也能用于 `./gradlew webRun --args='...'`。端口冲突时追加 `--server.port=18080`，后续请求同步改端口。

在 IDEA HTTP Client 或 Postman 中执行以下请求。`{{apiToken}}` 填入本机私有 HTTP 环境，值与 `app.web.api-token` 一致，不把实际 token 提交到仓库。

```http
### 健康检查，无需 token；UP 不证明数据库可用
GET http://127.0.0.1:8080/api/v1/health

### 本地注册的数据集定义
GET http://127.0.0.1:8080/api/v1/datasets
Authorization: Bearer {{apiToken}}

### daily 对象的实库字段及来源说明，需要 QuestDB
GET http://127.0.0.1:8080/api/v1/data-catalog/objects/daily
Authorization: Bearer {{apiToken}}

### 最多取 20 行真实样本，需要允许读取的数据集和 QuestDB
GET http://127.0.0.1:8080/api/v1/datasets/daily/sample?limit=20
Authorization: Bearer {{apiToken}}
```

两条调用链分别看：

- 字段说明：[DataCatalogController](../platform/app-support/src/main/java/com/zoutrankil/data/catalog/web/DataCatalogController.java) 的对象详情方法，再逐步进入目录服务。观察本地 `meaning`、`temporal` 与 `sourceDocumentation` 的区别。
- 真实样本：[ExternalApiController.datasetSample](../markets/cn/data/src/main/java/com/zoutrankil/data/web/ExternalApiController.java) → [DatasetReadService.read](../markets/cn/data/src/main/java/com/zoutrankil/data/service/DatasetReadService.java) → [QuestDbBoundedReader.read](../markets/cn/data/src/main/java/com/zoutrankil/data/repository/QuestDbBoundedReader.java)。观察 `definition`、`query`、字段映射、SQL 参数和返回行。

样本 API 当前只接受 `limit`（1–100），没有接证券、日期筛选或 cursor 继续翻页入口，不保证是最新数据。需要有界日期的读取器可能拒绝这个空范围查询；不是所有模型都能直接 sample。

WebFlux 的 JDBC 调用放在 `boundedElastic`，断点会从请求线程切到工作线程。先用行断点，必要时将 Suspend 改为 Thread，避免一个请求暂停整个 JVM。HTTP 超时后不要把空白响应当成空表。

## 5. 第三步：看 Batch 装配、账本与任务

Main class 选 `QuestDataApplication`，配置名“CN-Batch”，Program arguments：

```text
--jdb.enabled=true
--jdb.scheduling-enabled=false
--jdb.metadata-url=jdbc:sqlite:./var/debug/jdb-metadata.sqlite
--jdb.archive-root=./var/debug/jdb-isolated
--app.sync.financial.enabled=false
--app.sync.batch.enabled=false
--app.materialized-views.etf-overview.enabled=false
```

启动前创建 `var/debug/`。本机配置需提供至少 24 位的 `jdb.api-token`。这套参数使用独立调试账本和归档目录，关闭 Quartz；它没有替换 QuestDB 连接，手动提交写入仍会访问配置中的数据库。

先只查看管理接口，`{{batchToken}}` 对应 `jdb.api-token`，与数据 API token 分开：

```http
### Batch 健康及调度器状态
GET http://127.0.0.1:9085/v1/health
Authorization: Bearer {{batchToken}}

### 可管理任务
GET http://127.0.0.1:9085/v1/tasks
Authorization: Bearer {{batchToken}}

### 调度定义
GET http://127.0.0.1:9085/v1/schedules
Authorization: Bearer {{batchToken}}

### 运行记录；新调试账本可能没有记录
GET http://127.0.0.1:9085/v1/runs
Authorization: Bearer {{batchToken}}
```

断点顺序：

1. [CnBatchConfiguration.ManagedBatchRuntime.start](../markets/cn/data-app/src/main/java/com/zoutrankil/market/cn/app/CnBatchConfiguration.java)：观察隔离环境、无 parent 的容器及生命周期。
2. [RuntimeConfiguration](../markets/cn/batch/src/main/java/com/zoutrankil/batch/config/RuntimeConfiguration.java)：看 SQLite、JobRepository、Quartz 和管理服务如何装配。
3. [ManagementServer.handle](../markets/cn/batch/src/main/java/com/zoutrankil/batch/api/ManagementServer.java)：用上面的 GET 请求跟踪鉴权、路由和响应。
4. 理解执行时再看 [LaunchService.launch](../markets/cn/batch/src/main/java/com/zoutrankil/batch/job/LaunchService.java) → [BatchJobs](../markets/cn/batch/src/main/java/com/zoutrankil/batch/job/BatchJobs.java)。只发 GET 不会进入 launch。

真实执行必须先冻结来源范围和原文，再绑定 fingerprint 提交运行，见[Batch 操作](batch.md)与[接口定义](batch/openapi.yaml)。`source_*` 仍写 `jdb_test_*` 隔离表，不能把它当成正式 `data.*` 接管。Spring Batch 执行状态和本项目数据核验状态要分别看；`IN_DOUBT` 不能直接重启重发。

当前 9085 提供 JSON 管理 API，没有 Dashboard。打断点不需要 Data Flow，也不要直接用通用 Job 重启绕过现有恢复入口。

## 6. 第四步：看主策略编排和评分

Main class 选 `MainStrategyApplication`，配置名“CN-主策略API”，Program arguments：

```text
--app.sync.batch.enabled=false
--app.sync.batch.startup-catchup=false
--app.sync.financial.enabled=false
--app.materialized-views.etf-overview.enabled=false
--jdb.enabled=false
```

主策略 profile 默认启用日批，这里明确关闭。启动后的管理 API 默认 8081；主策略入口本身不托管 Batch 容器。

```http
### 查看正式数据产物状态
GET http://127.0.0.1:8081/api/v1/main-strategy/data
Authorization: Bearer {{apiToken}}
```

从 [MainStrategyDataController.status](../markets/cn/main-strategy-runtime/src/main/java/com/zoutrankil/data/web/MainStrategyDataController.java) 跟进 `MainStrategyDataService`。只查状态不会执行整条日批。

理解整条编排时，阅读 [MainStrategyDailyPipeline.execute](../markets/cn/main-strategy-runtime/src/main/java/com/zoutrankil/data/service/MainStrategyDailyPipeline.java)，依次看 `Sources`、情绪、状态特征、回测输入、L2 特征、评分、计划和最终验收。观察冻结计划的 `sourceLogicalDate`、日期范围、每阶段凭证与失败原因。整链需要真实方案、批准摘要及来源证据，不能用虚构文件补齐。

仅看产物 CLI，可选 `MainStrategyDataCli`，Program arguments 为 `status`，等价命令：

```sh
./gradlew mainStrategyData --args='status'
```

数据 CLI 自行解析业务命令，不能照搬 Spring 启动参数。`produce-scores`、`import-plan` 会生成或导入正式产物，先读[主策略说明](main-strategy-java-data.md)。

## 7. 第五步：看 L2 与其他市场

实时 L2 选 `AliyunL2LiveMain`，Program arguments：

```text
plan --symbols 563360 --root var/debug/l2-live
```

在 `main`、`execute` 和同目录 `L2LiveConfiguration.parse` 放断点。`plan` 不连接 MQTT；它只说明订阅计划，不验证真实账号或行情。网络接收、解析、累计和发布链分别看 `AliyunL2MqttCollector`、`AliyunL2WireParser`、`L2MinuteAccumulator`、`L2LiveProcessor`。

看解析逻辑优先 Debug 下节测试。真实 `run/session` 会订阅和写文件；`replay` 也会处理并提交本地结果。长时间暂停采集线程可能导致断线或积压，精确算法调试使用保留样本。SQLite 与引用的 Parquet 必须成套保留，恢复流程见[实时 L2](../deploy/l2-live/README.md)。

美股入口先用 `definitions`，观察模型注册；真实采集时再跟进 `UsCoreCollector` 或 `UsBroadDailyCollector`。BTC 入口只有有限采集命令，没有只列定义模式，先看 `CryptoPriceMapperTest`；真实命令、日期上限及写入边界见[跨资产采集](us-market-data.md)。

## 8. 无外部输入时，直接 Debug 样本测试

IDEA 打开以下测试类，在方法左侧选择 Debug，通常比启动整条生产链更容易看清规则。测试使用本地样本或替身，不等于真实来源验收。

| 想理解什么 | 测试类 | 重点观察 |
| --- | --- | --- |
| 财务分页限制、修订、冻结日期和预算 | [FinancialSyncTest](../markets/cn/data/src/test/java/com/zoutrankil/data/financial/FinancialSyncTest.java) | 不完整来源为何阻断写入，本地列怎样保留 |
| 主策略缺日范围及恢复日期 | [MainStrategyFrozenSourceDateTest](../markets/cn/main-strategy-runtime/src/test/java/com/zoutrankil/data/service/MainStrategyFrozenSourceDateTest.java) | 新计划范围与旧计划恢复分别走哪条分支 |
| L2 逐笔、十档及时间解析 | [AliyunL2WireParserTest](../markets/cn/l2-live-app/src/test/java/com/zoutrankil/data/l2live/domain/AliyunL2WireParserTest.java) | 原始字符串、精确数值、上海时间到 UTC 的转换 |
| BTC 行情字段转换 | [CryptoPriceMapperTest](../markets/btc/src/test/java/com/zoutrankil/market/btc/marketdata/CryptoPriceMapperTest.java) | 交易所字段、单位和日期边界 |

正式同步的通用执行核心是 [SyncJobRunner](../platform/engine/questdb/src/main/java/com/zoutrankil/data/service/SyncJobRunner.java)。可在财务样本测试调用到它时观察来源完成证据、发送意图、完整键值回读、WAL 和最终状态，而不是只盯着发送方法返回。

## 9. 常见卡点

| 表现 | 先检查什么 |
| --- | --- |
| 找不到 main 或依赖类 | 重新同步 Gradle，classpath 选入口模块而不是父工程或业务库 |
| 找不到 YAML、方案或 SQLite | Working directory 是否为根目录；相对路径和本机覆盖是否正确 |
| 一运行就退出 | 是否传了有限 CLI 命令；查看最终退出码和失败信息 |
| 启动后业务断点不进 | API 需主动发请求；关闭调度后不会自动执行日批；CLI 与 Web 的 Bean 也不同 |
| 返回 401 | 请求端口、token 对应关系及 `Bearer ` 前缀；Batch 健康接口同样需要 token |
| 端口占用或 SQLite 已被持有 | 是否重复启动数据服务；调试账本是否被另一个实例占用 |
| API 健康但查不到数据 | 单独检查 QuestDB 连接、对象是否存在、READ 能力和读取守卫；健康 UP 不证明数据就绪 |
| 样本为 409 或报范围错误 | 查看读取守卫和数据集范围约束，不要直接放宽保护 |
| 单步后超时、状态未决 | 断点暂停也消耗网络超时、执行预算和锁租期；核对原运行再恢复，不能删账本或锁后重发 |
| 日期看起来差 8 小时 | 先确认是业务日期标记还是真实时刻；日期不转时区，事件时间才按市场时区展示 |

本文只核对入口、参数、请求路径和源码链接，未启动服务或执行真实采集。日志位置、完整配置和已有恢复操作继续以对应专题文档为准。
