# 退役 margin_zrz、moneyflow_dc 和 qmt_1m_bars

日期：2026-10-20。性质：用户明确要求删除三项业务代码及数据；本文件仅登记待执行任务，不代表已经删除。

## 要解决什么问题

用户决定不再保留 `margin_zrz`、`moneyflow_dc`、`qmt_1m_bars`。需要完整移除它们的生产、读取和在线数据，避免留下仍能触发的 Job、缺表查询、自动建表或无效页面入口。

依据：[新鲜度核查](../../audits/2026-10-20-cn-collected-data-freshness.md)、[原保留分析](../../audits/2026-10-20-cn-stale-data-retention.md)。原分析建议保留部分数据，属于删除决定之前的评估；本次明确删除要求优先，不再以“有人读取”作为永久保留理由。已有消费者需要一起停用或移除，不能直接 DROP 后让其继续报错。

| 删除对象 | 核查时最新业务日期 | 已找到的代码与消费者 | 本任务结果 |
| --- | --- | --- | --- |
| `margin_zrz` | 2025-07-25 | Java 历史同步 owner、模型、读写、恢复、CLI；Python 历史只读产品 | 移除专属实现及在线数据，不再提供历史补跑和读取 |
| `moneyflow_dc` | 2026-03-20 | Java 东财资金流同步与隔离验收；Python 查询、排名服务 | 移除该来源专属实现及数据，不替换成其他口径资金流 |
| `qmt_1m_bars` | 2026-07-06 | Java 目录登记；Python 执行行情、动量、T0 状态、canary 查询 | 移除这张分钟表的生产和消费路径及在线数据 |

日期是 2026-10-20 核查快照，执行删除时重新确认实际环境与物理对象，不按历史行数判断是否删干净。

## 要达成的具体效果

- 代码不再采集、写入、读取或自动创建三张表；任务、目录、模型列表和页面不再把它们显示为可用功能。
- 实际 QuestDB 中三张表及确认专属的派生产物被删除，无活动 writer 重建、无消费者继续发缺表 SQL。
- 专属模型、来源 DTO、mapper、port、storage、owner、CLI、配置和测试完成清理；共享基础设施和其他数据产品继续正常运行。
- `moneyflow`、`moneyflow_ths`、`margin_all`、`margin_detail`、`margin_trading`、实时 L2 的 SQLite/Parquet 及自己的订单成交记录不属于本次删除范围。

## 代码要改哪些地方

### margin_zrz

从 [MarginZrzJobService](../../../markets/cn/data/src/main/java/com/zoutrankil/data/margin/application/MarginZrzJobService.java) 与同功能的 `MarginZrzSyncJobOwner`、`MarginZrzSyncAdapter`、恢复/换表及存储实现追踪，清理来源 DTO、模型、键、Dataset、装配、CLI、读取绑定、目录及字段说明维护资料。

生成模型从维护中的资源或生成器入口移除，不手改生成文件后让下一次生成恢复。逐项核对业务和测试依赖，删除仅为该来源服务的代码，共享分页、锁和核验设施继续保留。

### moneyflow_dc

从 [MoneyflowDcJobService](../../../markets/cn/data/src/main/java/com/zoutrankil/data/flow/application/MoneyflowDcJobService.java) 与同功能的 owner、source、adapter、mapper、port、storage 追踪。清理 `FlowCommands` 中该来源命令、装配、读取绑定、模型及来源说明；移除 `TushareProperties` 中该来源专属配额设置。

相邻 `back-monitor` 的 [资金流查询](../../../../back-monitor/src/quant_platform/data/adapters/questdb/moneyflow.py) 和 [资金流应用服务](../../../../back-monitor/src/quant_platform/data/application/moneyflow.py) 已有具体消费者，需移除东财来源专属入口与排名选项。保留其他资金流来源，不将不同来源数据静默冒充东财结果。

### qmt_1m_bars

核对实际写入方、DDL、任务与配置，不能因本 Java 工程未找到 writer 就宣称已经停止生产。清理本工程目录及契约登记，并核查相邻 `back-monitor` 的专属生产和消费路径：

- [执行行情适配器](../../../../back-monitor/src/quant_platform/best_trader/adapters/execution_market_data.py)：分钟价及 VWAP/TWAP 读取。
- [行情监控](../../../../back-monitor/src/quant_platform/best_trader/application/market_monitor.py)：依赖该表的动量读取。
- [T0 状态服务](../../../../back-monitor/src/quant_platform/best_trader/application/t0_action_value_service.py)：该表提供的分钟状态。
- 模型、建表、只读产品配置及 canary 命令中针对该表的分支。

功能完全依赖此表时退役对应功能；共享功能只去掉该数据源分支，并明确表达不可用。不能用空数据或其他行情静默冒充原能力，也不能扩大为删除全部 QMT、交易、T0 或实时 L2 模块。

## 数据怎么删除

1. 在实际目标环境固定三张表的数据库身份、DDL、范围和真实依赖清单；停止对应写入与调度，部署好消费者退役代码，防止删除后重建或继续读取。
2. 核对原正式账本、未决写入、所属锁、stage/backup 和视图/MV 依赖。未决状态按原协议处理，不自动重发、不强行解锁；历史核查未发现依赖不能代替本次实查。
3. 执行前保存删除清单和必要恢复材料，按项目实际备份策略处理。临时恢复材料不再作为可查询业务产品；不能把“归档后继续在线读取”当作完成用户删除要求。
4. 删除三张正式表；stage、backup、隔离验收表、本地专属数据及派生产物按实际归属逐项列名清理，不用模糊前缀批量 DROP 或删除共享目录。
5. 复查表/视图/MV 元数据、有效运行配置和消费者请求，确认对象不存在且不会自动重建。共享 SQLite 不能整库删除；原运行证据与删除记录保留审计用途，不再提供已退役业务的执行入口。

已执行的 Flyway 脚本不改，新清理使用独立维护/迁移操作并明确环境和范围。当前缺数据库访问或原恢复证据时，交付可执行清理材料并如实标为“数据未删除”，不能把代码清理计作全任务完成。

## 文档、生成和验证

- 更新维护中的模型资料、生成器输入、任务目录、配置和使用说明，标明退役。旧审计快照和旧迁移中的名称作为历史证据保留，不机械追求全仓零字符串匹配。
- 清理三项专属测试及过期断言，保留有意义的退役回归：已移除命令/来源被明确拒绝，公共装配可启动，剩余数据产品可注册和读取，无该表 SQL 或自动建表调用。
- 执行受影响 Java/Python 模块测试。涉及模块/依赖与发行内容时检查 `architectureTest`、`verifyModuleBoundaries`、依赖兼容及相关发行包；实际数据清理由目标环境元数据与请求证据单独验收。
- 明确交付代码退役范围、消费者影响、删除对象清单、清理前后证据和仍未完成项。本任务不新增替代来源或重建同用途结果表。

## 实现 prompt

```text
先读 AGENTS.md、项目规范及本文件，按用户明确决定彻底退役 margin_zrz、moneyflow_dc、qmt_1m_bars 的专属代码与在线数据。核对本工程及 back-monitor 的 writer、消费者、模型、来源、CLI、调度、装配、目录、生成资料、配置与测试，移除专属能力并处理共享入口，不能只 DROP 后留缺表查询，也不能用其他来源静默替代。实际环境先固定对象与依赖、停写并处理原未决/恢复归属，再按逐对象清单删除正式表及确认专属的关联数据，验证不再读取或重建；不改已执行迁移，不删共享账本、其他两融/资金流或实时 L2 产物。旧审计保留历史语义，完成相关测试、边界和发行包检查，交付代码及真实删除证据；缺访问或恢复材料如实说明数据未删。原保留建议不覆盖本次明确删除决定，保留工作区其他修改。
```

## 本轮执行记录

代码退役和消费者适配已实现，实际范围及清理材料见[退役记录](../../operations/cn-retirement/README.md)。实库三表仍存在；尚缺正式调度、原账本/锁与部署版本证据，数据删除未执行。本项保持未完成，不将本地代码提交当作完整退役。
