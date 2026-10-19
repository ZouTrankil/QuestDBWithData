# 代码从哪看

业务按 A 股、美股、BTC 分目录，通用执行和启动支持单独放。完整规则看[项目规范](project-structure-and-standards.md)。

| 想看什么 | 去哪里 |
| --- | --- |
| 通用字段契约、业务键和时间类型 | `platform/core/src/main/java` |
| 配置、日志和启动支持 | `platform/runtime-support/src/main/java` |
| 通用客户端、配额和分页 | `platform/engine/src/main/java` |
| 来源契约和通用采集 | `platform/engine/source/` |
| 共享写入核验、运行记录和恢复 | `platform/engine/questdb/` |
| Batch 元数据和持久写入协议 | `platform/engine/batch/` |
| 公共鉴权、目录接口和 schema 配置 | `platform/app-support/`，这是库，不单独启动 |
| A 股数据 API / CLI 启动和 Batch 生命周期 | `markets/cn/data-app/` |
| A 股表模型、DTO、L2 共享字段 | `markets/cn/src/main/java` |
| A 股来源字段、请求、转换和质量规则 | `markets/cn/source/` |
| 股票、ETF、指数、资金流、因子和衍生计算 | `markets/cn/data/src/main/java/com/zoutrankil/data/` |
| 盘后任务图、离线 L2 和归档 | `markets/cn/batch/` |
| 主策略数据准备、评分和正式计划 | `markets/cn/main-strategy-runtime/` |
| 实时 MQTT、解析和 Parquet 保存 | `markets/cn/l2-live-app/` |
| 美国表模型与现有美国利率来源 | `markets/us/` |
| BTC 时间和后续扩展入口 | `markets/btc/`，目前没有采集任务 |

`platform/app-support` 只提供跨市场公共 API 支持，数据库执行能力在 `platform/engine`。市场自己的命令、清洗和业务 API 放在市场库，启动入口放在市场目录下。主策略可以复用 A 股库，不加载 `markets/cn/data-app` 的入口 jar。根 `batch-app` 已移除。Batch 的 Job、管理接口和装配归 `markets/cn/batch`，由 `markets/cn/data-app` 的常驻服务按配置托管；有限 CLI 不启动 Batch。

## 跟一条数据流程

先看 `markets/cn/data-app` 的 `QuestDataApplication`，再看 A 股数据模块里的 `StockBasicSyncService`。顺着来源客户端、mapper、写入端口和读回检查看下去。

功能包里面，domain 放数据和纯规则，application 串流程，port 定义接口，storage/client 做实际访问。旧 Java 包名为兼容暂时保留，实际归属看所在模块。

## 运行状态放哪里

通用运行记录和恢复进度主要在本地 SQLite。实时 L2 的事件、分钟数据和累计状态在类型化 Parquet，SQLite 保存原始接收日志、状态和提交引用；备份两边一起保留。

配置和日志仍统一在根 `config/`。怎么启动看[配置说明](configuration.md)，业务时间看[市场时间](market-time.md)。
