# Python 数据同步与数据工程迁移 Review

日期：2026-09-29。

范围：Java HEAD `a90b22c` 加当前未提交的业务时间组件；本机 `../back-monitor`（HEAD `a3af1163`）的数据应用服务、连接器目录、质量与物化入口；迁移任务登记与已提交验收产物。静态审查，不运行测试、不请求来源、不连接或写入数据库。历史验收结果按仓库记录引用，不视为本次重新验证。

## 结论与进度

Java 已具备同步基础设施与 stock_basic 样例闭环，尚不能替代 Python 数据平台。

- 生成的 table/view/materializedview record 共 184 个，是数据库结构投影，不是 184 个可执行同步任务。
- 当前生产源码中 DatasetImplementation 和 SyncJobOwner 的具体接入均集中于 stock_basic。
- completion-register 登记 F001–F012 verified，F013 running，F014–F017 planned；D001–D184 尚待逐数据迁移，Q001–Q003 保持条件准入；Q004–Q014 已退出落库候选，详见 [Q 对象处置记录](migration-tasks-20260929/q-object-disposition.md)。
- F012 历史记录为全套 113 项、104 通过、9 跳过；恢复样例为两个真实 stock_basic 代码。该证据不能证明行情增量、财报修订或所有数据源已完成。
- README/execution-status 顶部仍写 F010，后文已到 F013，应统一当前摘要；不按任务数量推算业务完成百分比。

## 优先问题

### P1：旧入口绕过统一运行控制

依据：`cli/CommandLineRunner.java` 的 sync-stock-basic-questdb/qwp/jdbc 分支仍调用 `StockBasicSyncService.syncToQuestDb()`，直接取数和调用 Repository；没有经过 SyncJobRunner 的运行账本、DatasetIntervalLock、取消与 checkpoint。

影响：即使正式 job 持有锁或处于 IN_DOUBT，旧入口仍可向同一张表写入，破坏单写入者和恢复假设。旧取数入口一次请求 list_status=L，也没有走 PageExecutor 的完整性判定。

建议：所有正式数据库写入汇聚至统一 runner；旧命令做明确兼容映射，完整证券目录的来源契约单独实现，不把最多 100 个显式代码的样例直接当全市场任务。

### P1：崩溃/未知写入的对账恢复入口缺失

依据：`VerifiedSliceRecovery.load()` 拒绝未终态的旧 run；`DatasetIntervalLock` 保留持久锁；`releaseAfterReconciliation()` 在主源码中没有调用方；CLI 没有 reconcile 命令。

影响：正常捕获异常后的 PARTIAL 恢复已有实现，但 kill/断电遗留 RUNNING 或未知提交时，不能仅靠 --resume-from 恢复。锁不会自行消失，当前缺少受控处理路径。

建议：补齐旧 writer 终止证据、源与目标完整键/值/WAL 对账、账本状态收敛、锁释放和审计入口。不能仅按超时删除锁。SQLite 控制库的锁只协调共享该控制库的进程；多机部署需明确控制面边界。

### P1：账本配置没有贯穿状态/取消入口

依据：`StockBasicJobService`、`StockBasicGroupService` 使用 app.sync.ledger-path；CLI show-sync-run 与 cancel-sync-run 默认硬编码 var/sync-ledger.sqlite3。

影响：配置自定义账本后，运行成功写入新路径，但默认查询/取消仍访问旧路径，需要用户手工重复指定 --ledger。

建议：统一 SyncProperties 与账本工厂；命令行显式覆盖优先，其余均使用 YAML，路径也应采用一致的解析基准。

### P1：数据同步业务尚未接入

依据：`StockBasicSyncAdapter.definition()` 只支持 SNAPSHOT，最多 100 个代码、手动运行、dailyEligible=false；preflight 拒绝日期区间。现有生成器读取 domain-catalog.json，DailyRow.tradeDate 仍是物理 TIMESTAMP 对应的 Instant。

缺少：交易日历、完整证券主数据、行情/复权/停牌等逐数据 adapter、来源参数契约、正式读写实现、日期与键映射、任务注册及真实对账。stock_basic 的六字段 L 状态样例不等同于 stock_detail_info。

模型工作应补充 DTO→业务模型→存储映射；不能把物理 record 当业务日期与完整键已经确认。Pydantic→JSON Schema→Java 业务 DTO 的自动生成链路也尚未由现有物理投影生成器完成。

### P1：断点恢复尚不等于历史增量引擎

已有 verified slice 重取/复核/跳过机制，以及分片分页基础组件。未见正式业务 adapter 完成 bootstrap、已验证覆盖/水位、交易日缺口、有限 backfill、修订重叠窗口、旧公告修订和空数据证明的整条链路。

建议：以 daily 数据任务实现第一个端到端增量闭环，再以一张财报表检验公告日/报告期/修订差异，避免只用 MAX(date) 推进。

### P1：数据工程缺少质量门槛与物化生命周期

Python 对照：`application/quality.py`、`adapters/quality/rules/`、`adapters/quality/historical_completeness.py`、`application/materialization.py`、`adapters/materializers/registry.py`。

Java 当前有完整键/值写后回读和 WAL 验证，但未见相应质量规则执行服务、历史覆盖报告、数据 readiness 门槛或物化调度实现。

待补：完整性、唯一性、时效、值域、一致性、PIT；交易日覆盖和缺口定位；上游版本/分区就绪判断；衍生表的重算范围、源修订失效与下游重建；普通 View 与原生 MV 的差异化处理。

写后回读只能证明“本批写入值一致”，不能证明“来源没有漏数、历史完整、策略可以使用”。

### P2：批量组合、调度与正式 schema 接管待完成

- F013 有定义、冻结计划和串行 runner，正式示例只有一个 stock_basic 成员；仓库证据明确尚未完成真实组合链路验收。
- F014/F015 批量读写组合未完成；F016 调度定义、启停、交易日、misfire、重入未完成；F017 只有局部 CLI，通用管理未完成。
- Flyway 目录仅有 stock_basic 示例表和 latest view 两个迁移，尚未接管 184 个已有对象。schema-export 是快照，不是 migration history。
- 正式接管需要每表 owner、现状差异审计、baseline 策略、后续版本迁移、View/MV 依赖顺序和回退方案；不应直接重建线上表。
- 非 Tushare 来源、文件/L2 导入 manifest/hash、外部结果接入也需各自适配，不能由通用 HTTP 客户端代替。

## 建议实施顺序

1. 收口旧写入入口、账本配置和未知写入恢复流程。
2. 按现有计划完成 F013 真实组合验收及 F014–F017；补一张独立的数据质量/就绪门槛任务，避免仅逐表建模遗漏此能力。
3. 依照已有逐数据依赖落地 exchange_calendar、stock_detail_info 和 A 股核心数据，先证明完整端到端路径。
4. 扩展 ETF/指数/财报等；每个任务必须记录非空来源、逐键逐字段回读、幂等、缺口/修订、失败恢复证据。
5. 接入衍生物化、文件来源和下游消费契约；与 Python 按冻结窗口并行对账，确认一致后逐任务切换 owner，防止双写。

本次仅新增 Review 文档；未修改运行逻辑或任务完成状态。
