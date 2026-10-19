# QuestDBWithData

文档统一从[文档目录](docs/README.md)看。开发或交给 AI 扩展前，先读 [AI 开发入口](AGENTS.md) 和 [项目结构与开发规范](docs/project-structure-and-standards.md)。业务已按三个市场分目录，规范说明模块职责、市场时间和数据发布规则。

简单说，这是一个用 Java 写的数据采集和计算项目。QuestDB 放业务数据，SQLite 记运行状态和恢复进度。

业务代码收拢为两个父工程：`platform/` 放公共能力，`markets/` 放 A 股、美股和 BTC。根级启动命令保留。

## 这几个目录是干什么的

- `markets/cn`：A 股模型、采集、清洗、特征和批处理；主策略与实时 L2 在下面分别启动。
- `markets/us`：美国利率、日线/分钟行情、挂牌快照和部分 ITCH；`markets/btc`：BTC/ETH 现货及永续行情。各自的 `data-app` 是有限采集入口，真实覆盖与剩余验收见[基础行情](docs/us-market-data.md)。
- `platform/core`：纯 Java 模型、契约和市场时间接口。
- `platform/engine`：通用客户端、来源执行、存储核验和恢复。
- `platform/runtime-support`：配置、日志和启动支持。
- `platform/app-support`：公共鉴权、数据目录和 schema 配置支持库，没有市场启动入口。
- `markets/cn/data-app`：A 股数据 API / CLI 入口，常驻服务按配置托管 Batch；`markets/cn/batch` 保存任务与管理能力。

## 怎么启动

需要 JDK 24。[`config/application.yml`](config/application.yml) 是配置入口，公共值在这里，市场和进程设置分别在 `config/markets/`、`config/apps/`。这些文件已提供本项目的运行默认值，数据库、来源 token 和管理 API token 都在这里查看或修改。项目根目录的 `.env` 不会自动读取。

```sh
# 数据 API，默认 8080
./gradlew webRun

# 看看有哪些同步任务
./gradlew run --args='show-sync-job-definitions'

# 主策略，默认 8081
./gradlew mainStrategyRun

# Batch
./gradlew batchRun

# 只检查实时 L2 计划，不会开始订阅
./gradlew aliyunL2Live --args='plan --symbols 563360'
```

数据 API、主策略和实时 L2 分别启动。Batch 由数据 API 按配置托管；`batchRun` 是启用托管的兼容命令，不要与同一个数据 API 实例重复启动。

## 从哪里开始看代码

先从 `markets/cn/data-app` 的 `QuestDataApplication` 看程序怎么启动。然后挑 `markets/cn/data` 里的一个业务，比如 `stock/`：跟着“命令 → 应用服务 → 数据来源和转换 → QuestDB 写入”往下看就行。

批处理和主策略稍复杂，等熟悉一条普通采集流程后再看。目录说明在[代码结构](docs/code-structure.md)，配置说明在[这里](docs/configuration.md)。

## 其他说明

- `sync-stock-basic-questdb` 是写隔离测试表的示例，需要 Tushare 和 QuestDB；接入已有 QuestDB 前先看 [Flyway 说明](docs/flyway-schema-management.md)。
- [模型和字段约定](docs/model-schema-conventions.md)、[QuestDB 使用说明](docs/questdb-usage.md)、[实时 L2 部署说明](deploy/l2-live/README.md)。
- [同步和因子计算](docs/data-pipeline.md)、[Batch 说明](docs/batch.md)、[QuestDB 维护](docs/questdb-maintenance.md)。

本地验证命令：

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew :test --console=plain
./gradlew check --console=plain
```

有些测试需要外部服务或仓库里没有的原始数据；跑这些命令不等于测试已经通过。
