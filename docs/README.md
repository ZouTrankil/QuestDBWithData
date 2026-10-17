# 文档从这里看

想了解项目，先看前面三篇。要改代码，再看开发规范和对应功能的说明。

| 想了解什么 | 看哪篇 |
| --- | --- |
| 各模块做什么，代码从哪里看 | [代码结构](code-structure.md) |
| 数据库地址、账号、日志、启动命令在哪里配 | [配置和启动](configuration.md) |
| 数据同步在哪做，怎么判断增量完成 | [同步和因子计算](data-pipeline.md) |
| 三个市场怎样处理日期和时间 | [市场时间](market-time.md)、[数据集归属](dataset-markets.md) |
| 给自己或 AI 扩展项目时要守哪些规则 | [项目规范](project-structure-and-standards.md)、[AI 入口](../AGENTS.md) |
| 怎么分包，怎么检查依赖 | [分包和依赖检查](architecture-rules.md) |
| 新增字段、模型或数据集 | [模型和新增数据集](model-schema-conventions.md) |
| QuestDB 怎么读写，表和视图怎么选 | [QuestDB 使用说明](questdb-usage.md) |
| 程序怎么列出实库数据、说明和标签，如何导出目录 | [数据目录接口](data-catalog.md) |
| 怎么改表结构 | [数据库迁移](flyway-schema-management.md) |
| 怎么维护 MV，旧 meta 表怎么处理 | [QuestDB 维护](questdb-maintenance.md) |
| Batch 怎么采集、盘后运行和恢复 | [批处理说明](batch.md)、[接口定义](batch/openapi.yaml) |
| ETF 行情写到一半怎么恢复 | [ETF 行情恢复](etf-daily-recovery.md) |
| 主策略做了哪些计算，Java 和 Python 怎么配合 | [主策略数据链路](main-strategy-java-data.md) |
| 本地 L2 文件怎么导入 | [L2 文件导入](l2-native-parquet-ingestion.md) |
| 实时 L2 怎么部署 | [实时 L2 部署](../deploy/l2-live/README.md) |

`architecture-exceptions.tsv` 是架构检查使用的清单，保留原有格式。

## 这次清理了什么

- 删除 `工作.md`：旧待办和清表清单，不能再当作执行指令。
- 删除 `view.md`：早期对话和连接排障；表、视图的区别已放进 QuestDB 使用说明。
- 删除 `factor-incremental-pipeline-20261011.md`：一千多行临时进度、运行编号和性能记录；仍有用的流程放进同步说明。
- 删除 `questdb-maintenance-record-20261017.md`：操作流水和过时排障；维护方法、财务增量未接管的限制放进维护说明。
- 将 `java-batch-20260929/` 的三篇说明合并为 `batch.md`；接口定义移到 `batch/openapi.yaml`。
- 将 `model-development-workflow.md` 合并到模型说明，避免同一规则写两份。

已经提交的旧内容可以从 Git 历史查看。运行日志、验收报告留在各自的产物目录，不再不断追加到使用说明里。
