# A 股市场模块

这里负责 A 股及国内相关数据的采集、同步、清洗、因子、主策略输入和 L2。主要来源是 Tushare，也有 ChinaBond、本地文件和实时行情。公共执行、数据库访问和启动支持复用根目录的 `platform/`。

`cn` 本身是共享模型库，同时包含下面的子工程。数据服务、主策略和实时 L2 是三个独立启动入口；Batch 由数据服务按配置托管。

## 目录怎么分

| 目录 | 负责什么 | Gradle 路径 |
| --- | --- | --- |
| [src/](src/) | 市场模型、业务键、Dataset 契约、字段说明、目录登记和市场时间 | `:markets:cn` |
| [source/](source/) | A 股及国内来源契约、来源策略注册 | `:markets:cn:source` |
| [data/](data/) | 股票、基金、指数、资金流、财务等同步与查询；清洗、因子、衍生计算和离线 L2 | `:markets:cn:data` |
| [data-app/](data-app/) | 数据 API / CLI 启动，装配市场业务，常驻时可托管 Batch | `:markets:cn:data-app` |
| [batch/](batch/) | 批任务、Quartz 调度、盘后编排、管理接口和离线 L2 批处理 | `:markets:cn:batch` |
| [main-strategy-runtime/](main-strategy-runtime/) | 独立主策略进程，准备来源、评分、市场输入包和正式计划 | `:markets:cn:main-strategy-runtime` |
| [l2-live-app/](l2-live-app/) | 独立实时 L2 进程，接收、归一化、累计、落盘和恢复 | `:markets:cn:l2-live-app` |

`data-app` 是薄入口，具体业务在 `data`；`source` 定义来源规则，通用采集器在 `platform/engine/source`。主策略复用业务库，不依赖数据应用入口。实时 L2 只依赖公共 core 和启动支持，不加载整套数据 API。

## 模型从哪里找

模型在 [domain](src/main/java/com/zoutrankil/data/domain/)，参考 Tushare 的类别组织：

```text
domain/
├── stock/       股票：基础、行情、财务、参考、特色、两融、资金流、概念
├── fund/        ETF 和公募基金
├── index/       指数目录、行情、成分、权重
├── futures/     期货
├── spot/        黄金现货
├── bond/        债券收益率
├── forex/       人民币指数历史模型
├── macro/       国内宏观和利率
├── derived/     本地衍生计算、市场状态和回测投影
├── l2/          L2 特征、标签和归档契约
└── shared/      跨类别的任务身份与隔离目标规则
```

每类中，普通模型保存业务值，`*Key` 表示业务键，`*Dataset` 定义执行和存储契约；`table/`、`view/`、`materializedview/` 保存对应数据库对象的字段投影。它们不是重复建表。例如 `stock/market/DailyDataset` 是契约，`stock/market/table/DailyRow` 是日线存储行。

来源字段说明统一维护在 [collected-fields.json](src/main/resources/model-docs/collected-fields.json)。当前覆盖 70 个采集模型、1360 个字段，包含中文含义、来源字段、单位、格式、官网链接和核验状态。生成注释、目录接口和导出共用这份资料；待核实字段不能当成已确认语义。

在**项目根目录**执行，导出目录须尚不存在：

```sh
python3 tools/generate_domain_models.py --market cn --annotate-existing
python3 tools/generate_domain_models.py --market cn --export-documentation outputs/models/cn-fields
```

完整规则见[模型目录与契约](../../docs/model-schema-conventions.md)和[字段注释与导出](../../docs/collected-field-documentation.md)。

## 一条数据怎样走

正式同步通常经过：来源客户端 → mapper 转换 → application/owner 编排 → 写入端口 → QuestDB → 完整键值与 WAL 核验 → SQLite 运行记录和检查点。

在 `data/src/main/java/com/zoutrankil/data/` 下按 `stock`、`etf`、`index`、`flow`、`margin`、`financial`、`factor`、`derived`、`l2` 找业务。功能包内，`domain` 保存规则，`application` 编排，`port` 定义依赖，`mapper` 转换，`storage/client` 访问外部系统。

批处理有独立来源协议和运行账本。当前 Batch 的 `source_*` 固定写 `jdb_test_*` 隔离目标，不能作为正式 `data.*` 同步的完成证明。正式来源接管以[接管清单](../../docs/formal-sync-takeover.md)为准。

主策略在来源完成后核验情绪、市场状态、回测 MV 和 L2 特征，再生成评分与市场输入包、绑定正式计划。来源覆盖清单支持自动组装，但仍需要独立批准范围、真实上游运行和历史时点材料。

## 怎么启动

使用 JDK 24，在**项目根目录**运行：

```sh
# 列出数据同步任务，执行后退出。
./gradlew run --args='show-sync-job-definitions'

# 数据 API，默认端口 8080。
./gradlew webRun

# 查询主策略数据状态。
./gradlew mainStrategyData --args='status'

# 只查看实时 L2 订阅计划，不连接 MQTT。
./gradlew aliyunL2Live --args='plan --symbols 563360'
```

独立主策略用 `./gradlew mainStrategyRun` 启动，管理 API 默认端口 8081；该 profile 默认启用日批和启动补跑，启动前需准备实际来源绑定和批准材料。实时 L2 的采集、恢复和归档命令见[部署说明](../../deploy/l2-live/README.md)。

Batch 默认不托管；启用 `jdb.enabled` 后随数据服务启动和关闭。兼容命令 `./gradlew batchRun` 会启动同一个数据服务并启用托管，不要与已有数据服务重复启动。Quartz 调度及已保存的暂停状态另行控制。有限 CLI 不启动常驻调度。

数据目录入口是 `GET /api/v1/data-catalog`，可查表、视图、标签和字段详情，并导出目录 CSV。接口使用 Bearer token，示例请求在 [api.http](data-app/api.http)，详见[数据目录接口](../../docs/data-catalog.md)。

## 配置和存储

配置全部维护在根 [config/](../../config/)，不要在子模块另放一套运行 YAML：

- `application.yml`：公共连接、配置导入和默认值。
- `markets/cn.yml`：A 股时间、研究和离线 L2 目录。
- `apps/cn-data.yml`、`cn-main-strategy.yml`、`cn-batch.yml`、`cn-l2-live.yml`：各入口设置。
- `application-local.yml`：本机私密覆盖，Git 忽略；新环境按示例填写。

行情与共享时序产品按契约存 QuestDB；任务进度、锁和恢复记录存 SQLite。实时 L2 的归一化数据、分钟数据和累计状态存类型化 Parquet，SQLite 保存原始接收日志和提交索引，备份时两者一起保留。自己的策略订单、成交等记录使用本地 SQLite。

真实事件时间使用 `Instant` 和 UTC；业务日期使用 `LocalDate`，A 股业务时钟为 `Asia/Shanghai`。历史 UTC 零点日期标记不能整体加 8 小时。

## 当前完成到哪里

采集、分页、写后核验、恢复、查询 API、因子与评分链路、L2 存储和归档已有实现。模型存在、任务注册或单元测试通过，都不代表对应来源已经正式接管。

当前重点是核心来源和五条财务来源的生产范围、正式绑定、连续更新与恢复验收；其次是评分所需的真实批准材料、历史原件，以及 L2 全天实采和成对备份恢复。Batch 生产模式目前不可启用。详细缺口及顺序见[正式同步接管清单](../../docs/formal-sync-takeover.md)。

## 开发和验证

先读[项目规范](../../docs/project-structure-and-standards.md)。新增数据先明确来源、市场、时间、业务键、唯一写入方和消费者，再扩展现有 owner 与端口。迁包不顺带改表名、任务身份或恢复协议。

```sh
# 只测共享模型库；不包含所有 CN 子工程测试。
./gradlew :markets:cn:test

# 验证指定子工程。
./gradlew :markets:cn:data:test --tests '*DailyMappingTest'

# 构建数据应用发行包。
./gradlew :markets:cn:data-app:distZip
```

涉及依赖和分包时还要运行 `architectureTest`、`verifyModuleBoundaries` 及相关发行包检查。当前根架构测试源码缺失会拦截 `architectureTest`，不能视为通过。全部构建产物在根 `build/modules/`，完整离线入口和验证范围见[项目规范](../../docs/project-structure-and-standards.md#怎么验证和交付)。

进一步阅读：[同步与因子](../../docs/data-pipeline.md) · [财务同步](../../docs/financial-sync.md) · [Batch](../../docs/batch.md) · [主策略](../../docs/main-strategy-java-data.md) · [全部文档](../../docs/README.md)。
