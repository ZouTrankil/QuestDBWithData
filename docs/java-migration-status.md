# Java 迁移功能盘点

盘点日期：2026-09-29。范围是当前 Java 仓库、迁移台账与可读取的 Python 参考仓库；没有执行新的同步、写入、回测或线上验收。本页描述代码和已有验收证据，不把 schema record、DDL 快照或 mock 测试算作业务迁移完成。

## 当前判断

**公共数据运行框架已有可用的纵向能力；业务数据集和 Python 数据/研究业务逻辑尚未批量迁移。** 当前已有真实来源到 QuestDB 写入并逐字段回读的验收主要围绕 `stock_basic` 隔离样例。不能据此认为生产数据集已切换。

迁移台账有 17 项公共功能、184 项主线数据对象和 14 项条件数据对象。截至本次盘点：F001–F016 为 `verified`（人工复核仍是 `pending_review`）；F017 仍在实施；D001–D184 全部为 `planned`；Q001–Q003 为活跃 `conditional`；Q004–Q014 已从落库候选移除（`retired_with_evidence`），详见 [Q 对象处置记录](migration-tasks-20260929/q-object-disposition.md)。F017 的执行日志记有已做的 CLI 参数边界工作，但 completion register 与 manifest 仍显示 planned，且没有 `results/F017.json`，因此本页按“进行中、尚未验收”处理。

## 已支持的公共能力

| 能力 | 当前实现 | 适用边界 |
|---|---|---|
| 来源 API | Spring WebClient、Tushare 响应校验、共享请求预算/限流和有界重试 | 通过已接入的 stock_basic 入口验证；不是全部 Python connector 的替代实现 |
| 分片与分页 | 按日期/代码等生成有限请求片，检查页结束、重复页、游标停滞及取消 | 每个具体 API 仍需自己的参数、分页语义和截断规则 |
| QuestDB 读取 | PGWire/JDBC typed、有界列投影与过滤、分页/键游标；read group 可组合读取 | Spring 当前注册的读取定义只有 `stock_basic` latest 数据集 |
| QuestDB 写入 | QWP Sender、字段/类型/完整键预检、批次与字节预算、ACK 与 verified 分离、WAL 可见性及写后回读 | 实际 writer 和 dataset binding 目前仍是 stock_basic owner；write group 不能据此写任意业务表 |
| 运行治理 | SQLite 账本、attempt/slice 状态、互斥、取消、checkpoint 恢复、未知写入保护、组合任务与人工 tick 调度 | 通用 service 已实现并有验收；job owner 与可运行任务定义仍非常少。调度不等于后台定时器已启用 |
| Schema 迁移 | Flyway QuestDB 插件和隔离 stock_basic 表/view 迁移 | 当前应用 migration 只有 V1、V2；不是现场所有 schema 的初始化脚本 |
| 模型 | `StockBasic` 等少量业务模型；另外从现场 schema 生成 172 个 table、10 个 view、2 个 materialized-view projection | 大批 record 仅描述物理列和 Java 类型，没有业务 DTO、mapper、owner、读写定义或同步 job |

关键代码入口：`cli/CommandLineRunner`、`service/SyncJobRunner`、`service/ReadGroupReader`、`service/PersistentWriteGroupRunner`、`repository/QuestDbBoundedReader`、`repository/SyncRunLedger`。实际数据集注册可看 `config/ReadGroupConfiguration` 和 `service/StockBasicSyncAdapter`：目前仅有 stock_basic 读取定义/同步 owner；组合能力复用这些已注册 owner，不会自动推导出其他 184 个 owner。

有一个运行路径需要特别区分：旧命令 `sync-stock-basic-questdb` 仍直接调用 `StockBasicSyncService.syncToQuestDb()`，没有经过 `SyncJobRunner`。它适合现存的隔离样例验证，不能假定具备统一 runner 的账本、区间锁、取消和 checkpoint 保护。新治理链路通过 `run-stock-basic-job`、组合 runner 和 `schedule-tick` 调用；调度目前由显式 tick 驱动，没有启用后台定时执行。

另外，恢复实现主要复用已终态、证据完整的 verified slices；旧 run 若仍是 active/in-doubt 会被拒绝自动恢复。区间锁有“先确认 writer 已停止且已精确回读后释放”的保护方法，但 CLI 还没有独立 reconcile 命令/入口。崩溃遗留的未知提交需要人工/运维对账，不能仅用 `--resume-from` 或超时删锁处理。

## 尚未迁移的功能

### 1. 184 个主线数据对象

从交易日历、证券参考、股票/ETF/指数、资金流和财报，到宏观/期货、L2、衍生结果、因子/估值、策略和运行数据，D001–D184 在完成登记中均为 `planned`。当前没有逐数据的 Java source DTO、字段 mapper、业务 key 确认、DatasetDefinition/job 注册，以及 source→write→QuestDB 全字段回读证据。计划目录按类别拆分，见 [`migration-tasks-20260929`](migration-tasks-20260929/README.md) 和 [逐对象登记](migration-tasks-20260929/completion-register.md)。

尤其要区分：现场 schema 的 184 个 Java projection 只是“看得到列”；不能拿它们当成已搬迁、可同步或可安全写入。生成记录还没有从 Python Pydantic 模型生成 DTO，也没有完成逐字段的 nullable、单位、枚举、业务日期及 key 语义审核。

### 2. View、materialized view 与 ASOF

- 当前 Flyway 只建立 stock_basic 隔离快照表和 latest 普通 view。现场导出的 10 个普通 view、2 个 materialized view 是 schema 证据，没有作为 Java Flyway migrations 接管。
- Java 有 view/MV 的生成 projection，但没有已注册的业务读取定义来覆盖这 12 个现场对象；也没有 Java 侧的 MV 状态监测、回补/refresh 操作或结果对账流程。
- Java 源码中未发现 `ASOF JOIN` 查询实现。Python 中有 `pd.merge_asof` 逻辑，且 `data/adapters/questdb/backtest_view.py` 使用 QuestDB ASOF SQL；这些语义尚未迁入 Java。
- 普通 view/MV 由 QuestDB DDL 执行不等于 Python 的物化业务流水线已迁移。需要按具体 view/MV 核对查询语义、基表依赖、新鲜度、late data、回补与下游使用。

可参考现有设计：[View、Materialized View 与 WAL 说明](view.md)。设计文档不等于实现或运行验收。

### 3. 衍生计算及应用层业务

Python 的 `data/adapters/materializers`、`data/application/level2_features`、research/backtest/factor/industry valuation、策略与交易运行逻辑目前没有通用 Java 等价实现。Java 里有这些结果表的 projection 和可组合写入骨架，但没有逐算法移植/对照结果的证据。QMT/L2 实时采集、订阅状态机、特征计算和下游发布也不能从表 record 推断已支持。

此外，Web/API、仪表盘等 Python 应用层不在本轮 17 项公共功能 + 184 个数据对象计划的验收范围内；若要求 Java 全面替换整套 `back-monitor`，还需另列应用功能迁移清单。

## 与切换生产的距离

现阶段可以继续用 Java 验证并运行已接入的 stock_basic 隔离链路，也可以复用框架开发下一个 dataset。对其他对象，建议按 D 卡逐项完成：先冻结 Python 来源/owner/完整字段/业务键和物理 UPSERT KEY，再实现 DTO→domain→persistence 映射、Flyway/schema 接管、读写和同步模式，最后用隔离 QuestDB 验收非空真实来源、写后全字段回读、幂等重跑、增量修订和失败恢复。所有对象通过验收后，仍要单独核对唯一写入 owner、调度、历史覆盖、下游兼容和回退，才能判断是否切换。

## 依据

- [`completion-register.md`](migration-tasks-20260929/completion-register.md)、[`manifest.json`](migration-tasks-20260929/manifest.json)、[`execution-status.md`](migration-tasks-20260929/execution-status.md)
- Java CLI 与注册：[CommandLineRunner](../src/main/java/com/zoutrankil/data/cli/CommandLineRunner.java)、[ReadGroupConfiguration](../src/main/java/com/zoutrankil/data/config/ReadGroupConfiguration.java)、[StockBasicSyncAdapter](../src/main/java/com/zoutrankil/data/service/StockBasicSyncAdapter.java)
- 数据库迁移：[`db/migration/questdb`](../src/main/resources/db/migration/questdb/)
- 现场结构投影：[`schema-export/questdb-qdb-2026-09-28`](../schema-export/questdb-qdb-2026-09-28/README.md)
- Python 参考根目录（只读检查）：`/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/`
