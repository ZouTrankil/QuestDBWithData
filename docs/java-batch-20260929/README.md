# Java 数据批处理改造交付记录（2026-09-29）

## 当前结果

**本次交付是经过实库验证的运行底座增量，JDB-01—15 尚未全部完成。不能据此启动生产盘后链。** 元数据存储按用户后续明确要求使用本地 SQLite；需求原文中的独立 PostgreSQL 已被这一最新要求覆盖。部署为单机单进程，需多节点共享元数据时再评估 PostgreSQL 等服务端数据库。

需求来源：back-monitor 的 `docs/01-engineering-governance/architecture/java-data-batch-orca-tasks-20260929.md`。实现位置为现有 `/Users/Apple/zq/fun/QuestDBWithData`，起始 HEAD `e6b7a57`；宿主与参考 checkout 身份见 [inventory.json](inventory.json)。保留原有未提交修改，没有提交或推送。

遵照需求读取了当前 Orca orchestration 指南并只读查询运行状态。没有创建 Run、Task、Dispatch 或 worker，ID 均为“不适用”。返回的三个旧 back-monitor worker 均已失败且释放；未重新启动它们。Windows 实际定时器、环境覆盖和生产 owner 状态仍未知。

## 最新原生源增量

四十一个来源已注册 Java contract→采集冻结→Batch→SQLite 台账→QuestDB typed writer 路径；其中 31 个各有有限真实来源/隔离库验收，单样本验收并不表示全历史或全市场完成；`fina_audit` 对 600000.SH 的单公告日 1 行完成 VERIFIED；`share_float` 对 000998.SZ 的单公告日 45 行完成 VERIFIED；`dividend` 对单证券单公告日一行完成 VERIFIED，同时真实探测确认其他窗口会与旧 QuestDB 去重键冲突，冲突时拒绝快照；`cn_cpi`/`cn_ppi`/`cn_pmi`/`cn_m` 对 2026-07 月份各完成一行来源探测和隔离写入，月份键规范化为月初。四个宏观月频来源均对 2016-01 至 2026-07 的 127 个月窗口完成真实 Tushare/SQLite/隔离 QuestDB 写入、连续覆盖和质量验收，分别见[CPI](evidence/cn-cpi-history-live.json)、[PPI](evidence/cn-ppi-history-live.json)、[PMI](evidence/cn-pmi-history-live.json)和[M2](evidence/cn-m-history-live.json)。`cn_gdp` 对 2026Q2 完成季度末日期映射并在隔离表 VERIFIED；`shibor` 对 2026-09-28 一行及八个期限值、`shibor_lpr` 对 2026-08-20 一行及两个期限值、`hibor` 对 2026-09-28 一行及八个期限值，均完成真实来源/隔离库 VERIFIED。两张指数日表对 2026-09-28 完成同范围采集、写入和回读：行情源覆盖旧配置五个核心指数、`000985.CSI` 与 `801080.SI`（申万 `sw_daily` 路由），基本面源覆盖五个核心指数。混合来源实测及证书见[指数源实测](evidence/index-daily-live.json)。交易日历 SSE/SZSE 单日两行验收见[日历源实测](evidence/exchange-calendar-live.json)。`fina_mainbz` 对 `000001.SZ` 的 2026-06-30 产品/地区两类真实来源共 32 行、隔离库回读 VERIFIED，见[主营业务构成验收](evidence/fina-mainbz-live.json)；SHIBOR/LPR/HIBOR 实测见[利率源](evidence/shibor-live.json)、[LPR 源](evidence/shibor-lpr-live.json)和[HIBOR 源](evidence/hibor-live.json)。此前 13 源矩阵见[多源实测](evidence/native-producers-20260929-matrix.json)；`cyq_perf` 单证券日样本和国债曲线 23 行实测分别见[筹码表](evidence/cyq-perf-live.json)与[国债曲线](evidence/chinabond-yield-live.json)。这些有限样本不代表扩展指数目录、全部 31 个申万代码、全市场覆盖、全部历史或旧 Python 输出对照已完成。`stk_st_daily` 已扫描 17 个真实公告年度窗口并完成指定证券的逻辑日空结果验收，但仍缺真实非空样本及全市场覆盖。`cn_gdp` 与各宏观源的其他频率证据见[GDP](evidence/cn-gdp-live.json)、[CPI 单月](evidence/cn-cpi-live.json)、[PPI 单月](evidence/cn-ppi-live.json)、[PMI 单月](evidence/cn-pmi-live.json)与[M2 单月](evidence/cn-m-live.json)。完整本地测试与打包结果见[构建证据](evidence/full-build-20260930-final.json)。JDB-01—15 尚未全部完成。

后续新增的 `fut_basic` 是第 31 个真实来源样本：CFFEX 标准合约快照 692 行，隔离 QuestDB 全字段核验 `VERIFIED`，详见 [fut_basic 实测](evidence/fut-basic-live.json)。`etf_basic`、`disclosure_date`、`ths_index`、`etf_share` 与 `us_tbr` 已有来源契约、Batch Job 和 fixture 测试，但没有真实来源或 QuestDB 验收。最新完整构建为 403 项测试、0 失败、66 项环境条件跳过；Batch ZIP 使用 SQLite JDBC 且不含 PostgreSQL JDBC。Gradle `check` 会重建并核验该驱动边界。JDB-11 已增加默认暂停的盘后有界恢复 Quartz trigger、21 次 SQLite 尝试账本，并通过新触发 ID 保持同一业务身份重探来源；完整细节见[恢复验收](evidence/post-close-recovery-20260930.json)、[打包依赖门禁](evidence/sqlite-batch-packaging-gate-20260930.json)及[完整构建证据](evidence/full-build-20260930-final.json)。

## 已落地代码

代码集中在 `src/main/java/com/zoutrankil/batch/`，原 CLI 和原 SQLite 治理链未接管、未改接。

- `BatchApplication` / `RuntimeConfiguration`：同仓独立启动入口，不扫描旧应用的 producer/config。独立持久化 SQLite 文件；Flyway 在单一文件内管理业务台账、Spring Batch 与 Quartz 表。SQLite 单机单运行实例，Quartz 使用 JDBC 持久化并禁用集群。PostgreSQL PGWire/8812 不可配置为元数据。生产入口无 fixture 开关。
- `RunRequest` / `CompletionEvidence`：日期、范围、修订、输入身份、实体范围、日历版本、时区、触发时刻和证书校验。来源业务实例哈希包含冻结 universe 版本与期望实体集合，不包含执行时间或 request ID；服务从冻结来源推导并校验 scopeIdentity。已写入或已完成的实例变更输入需显式 revision；未发送源实例的同范围晚到输入有受控重绑，旧成功证据不可覆盖。业务状态独立于 Batch 状态。
- `SqliteLedger` / `LaunchService`：手动与 Quartz 共用启动入口；请求幂等、本机文件锁防重、执行/阶段关联、审计记录。未决运行不自动重放。首次阻塞原因保留在阶段记录中。
- `DurableWriter`：保存源文件指纹和意图，提交 UNKNOWN 后才发送；ACK 与 VERIFIED 分开。持久目标占用无超时抢占；未知批次必须核验，不能整批重发。核验要求精确内容、本批物理可见性、明确停止的未知 sender；不依赖全表 WAL 永久追平。发送前指纹校验采用流式读取。
- `PostCloseGraph` / `BatchJobs`：八个业务阶段及一个内部总体验证 Step。关键链失败阻断；可选分支失败允许主链完成，但总体为 PARTIAL。恢复跳过已有同身份证书的阶段，避免重算/重发。等待一次探测后结束执行，不长期占有业务事务。`LaunchService` 在生产配置下先读取并校验当天冻结的 15 源计划，逐一通过持久幂等 probe 和注册的 source Batch job；来源未完整、定义/范围错误或执行状态未 VERIFIED 时，`post_close` 在启动 Batch 前进入 BLOCKED/IN_DOUBT。`DataReadiness` 随后要求 CORE_TABLES 与 SSE/SZSE 日历源在同 logical_date、calendarVersion、时区下均有最新 VERIFIED/合法 VERIFIED_EMPTY 证书，检查来源 definitionVersion、scope identity、归档内物理证据文件、冻结源文件和本实例隔离表名/行数，再原子发布聚合证书；较新失败 revision 优先于旧成功证书。自动 fan-out 不等同完整数据产品水位或真实盘后验收。
- `ExternalComputation` / `StageExecutor` / `ExternalExecutionStore`：外部结果身份校验和未接通状态。SQLite V7 持久化 child ID、输入身份、PID/进程启动时刻、心跳、结果和日志引用；先预约后允许外部 runner 启动，重启后核对 PID 与进程创建时间，未知状态保持 IN_DOUBT，不自动启动第二个 child。已接入可选固定 argv/no-shell 受限启动器、清理继承环境、结构化进度日志、子目录证据校验及超时诊断；默认命令目录为空并明确 BLOCKED。Java 子进程 fixture 已覆盖 VERIFIED、PARTIAL、错误日期、定义版本漂移、陈旧证书、进度日志及超时后不杀活进程/不重复启动；尚缺完整进程树/崩溃窗口故障矩阵和真实研究 runner 联调。
- `TradingCalendar` / `RecoveryPolicy`：显式日历覆盖、午夜业务日期、月末交易日、恢复截止/次数和有限 backfill 规划、连续证书水位算法。这里只实现策略函数，尚未全部接入调度工作流。
- `ScheduledLaunch` / `ScheduleCatalog`：持久化 `post_close` 18:30 与 `source_cyq_perf` 21:10（工作日、Asia/Shanghai）cron，初始暂停，默认 scheduler 不启动；重启保留暂停状态；过期触发跳过。实际触发必须有版本化日历和 `$JDB_ARCHIVE_ROOT/requests/<job>/<date>.json` 输入请求文件，不编造输入指纹。独立运行时默认开启 `JDB_CORE_SOURCE_FANOUT_ENABLED`；盘后源计划以 SHA-256 内容寻址保存在 `$JDB_ARCHIVE_ROOT/post-close-plans/`，格式、revision 与验收边界见 [核心来源冻结计划](core-source-fanout.md)。`cyq_perf` 调度已注册但未启用。
- `ManagementServer` / `BatchControl`：loopback Bearer 鉴权接口与 CLI，健康检查、业务/探测/写入状态计数、任务/运行/阶段/证据/触发/审计历史查询、同身份运行/恢复、白名单调度单任务暂停/恢复。输入限制 64 KiB；拒绝任意 job、shell 或模块名。暂停不杀当前执行。`/v1/metrics/prometheus` 导出持久化状态和运行姿态；`/v1/reconciliation` 为 UNKNOWN/ACKNOWLEDGED/BLOCKED 写入与未终结外部 child 提供只读人工核对队列，包含孤儿 Batch 执行、未决写入与未终结外部 child，不修改状态、不触发重发；`/v1/backfills/plan` 基于版本日历输出有界源任务分区计划，明确 `dryRun=true/enqueued=false`；通知去重和真正的回补执行器仍缺。`RunRequest` 与 source job 白名单详见 [openapi.yaml](openapi.yaml)，`post_close` 源计划文件由本地冻结工作流提供。
- 月度来源 VERIFIED 后，将范围内的逐月观察与业务证书在同一个 SQLite 事务内持久化；`/v1/coverage/monthly` 返回连续区间。GDP 使用季度 `start_q`/`end_q`，`/v1/coverage/quarterly` 以相同事务语义记录逐季证书。月频和季度来源级水位都不等同于 DataReady 或全资产连续水位。

`DurableWriter.Port` 的 `QuestDbTablePort` 现支持四十一个注册源；其中三十一个已有有限真实来源/数据库验收，指数源范围为单日五个核心指数、一个 CSI 扩展样本和一个申万 `.SI` 样本；行情多代码验收共 7 行、基本面 5 行。交易日历另完成 SSE/SZSE 同日两行来源与实库验收，但尚未发布全历史版本日历；`fina_mainbz` 对单证券单报告期的 P/D 类别采集已验收，完整 VIP 全市场生产仍未完成；`fina_audit` 和 `share_float` 各有单证券单公告日的真实来源/隔离库样本验收，完整历史、全市场和 PIT/修订传播未完成；`dividend` 已实现按单证券、单公告日采集与旧表空报告期哨兵/重复业务键保护，并完成真实来源与隔离数据库有限验收。新增的真实行情样本验收与原一行协议测试分别记账，后者不能计为行情 producer 验收。孤儿 Batch 执行、BLOCKED 批次和未知子进程的人工对账/恢复管理接口仍需实现，不得直接修改状态后重跑。

## JDB 逐项状态

所有依赖任务均按原需求保留，不以拆分掩盖未验收项。

| 编号 | 本次结果 | 尚缺的关闭条件 |
|---|---|---|
| JDB-01 | 静态盘点；77 源资产、198 既有对象、59 入口/契约文件；D002 `stock_detail_info` 既有 Java producer 的隔离验收已 verified；其与独立 Batch runtime 的复用/接线仍待核实 | 全量语义 owner 审核、部署状态、Windows 定时器、代表负载/SLA 基线。自动分类不是最终确认 |
| JDB-02 | 运行身份、日期、状态、证书和外部结果 Java 契约与边界测试 | 各表精度/单位/PIT 的冻结契约、五类来源全套正常/空/部分/未知样例 |
| JDB-03 | Boot + SQLite + Quartz + Batch 底座已实现并通过真实 SQLite 文件集成测试 | 所有正式任务注册、完整权限语义、健康指标和调度配置版本治理 |
| JDB-04 | 持久写入协议、互斥及 UNKNOWN/ACK/VERIFIED 已实现；SQLite 元数据 + QDB 一行协议验收通过；新增只读 `/v1/reconciliation` 未决写入/外部 child 队列 | 各业务 writer 绑定、真实崩溃注入/WAL suspended/持续并发写全部矩阵、带物理证据的人工处置和关闭接口 |
| JDB-05 | 十四个源有限真实来源/实库验证；stk_st_daily 历史公告逐年完整扫描、单证券逻辑日空结果实库验收；cn_bond_yield_curve 单日公开来源/23 行实库验收 | stk_st_daily 非空旧库冻结对照、全市场/参考数据覆盖、完整频率/迟到/修订语义与逐表全矩阵；daily_basic 指定日旧库对照缺失 |
| JDB-06 | `cyq_perf`、两张指数表、`exchange_calendar`、`fina_mainbz`、`dividend`、`shibor`、`shibor_lpr`、`hibor`、`fina_audit`、`share_float`、`cn_cpi`、`cn_ppi`、`cn_pmi`、`cn_m`、`cn_gdp` 有独立 Batch source job；指数行情/基本面同日分别验收 7/5 行，含 `000985.CSI`、`801080.SI`；交易日历 SSE/SZSE 2 行 VERIFIED；主营构成对单证券单季 P/D 两类 32 行 VERIFIED，并保留旧键碰撞拒写规则；`fina_audit` 单证券公告日 1 行真实 VERIFIED；`dividend` 单证券单日 1 行真实 VERIFIED、冲突日拒绝快照；`share_float` 对 000998.SZ 的 2018-12-20 公告窗口真实采集 45 行并隔离写入 VERIFIED；`shibor`/`hibor` 各单日 1 行/八期限值，`shibor_lpr` 单公告月 1 行/两期限值，四个宏观月频源均对 2016-01 至 2026-07 的 127 个月范围真实 VERIFIED 并登记连续 SQLite 水位，`cn_gdp` 对 1992Q1–2026Q2 的 138 个季度完成真实来源、SQLite 连续水位、隔离 QuestDB 回读和单次发送 VERIFIED；`fut_basic` 有 CFFEX 692 行实测；`etf_basic`、`disclosure_date`、`ths_index`、`etf_share` 与 `us_tbr` 已实现但只有 fixture 验证；`us_tbr` 有精确日期、13 字段契约和默认暂停的 21:35 调度，真实来源与实库未验收 | 仅有限代码/报告期验收；完整扩展指数与 31 个申万代码 universe、日历全历史及版本化发布、宏观来源 2015 年前完整历史与修订回看、share_float/fina_audit/利率完整历史与 PIT 修订回看、主营构成全市场 VIP 与 checkpoint、历史回补、旧 Python 输出对照、PIT/修订策略及其余财务/宏观/筹码资产逐表实现与验收 |
| JDB-07 | Spring Batch `l2_archive_integrity` 先验收并冻结归档，再流式映射三类 CSV 到内容寻址目录的 deals/orders/quotes 压缩 JSON Lines；`L2ArchiveQuestDbIngestor` 按最多 500 行一批使用 SQLite 意图台账、持久 ILP outbox、UNKNOWN 核验协议写入归档 SHA 命名的三张隔离表，归档内稳定行号作为去重键以保留重复时刻事件；所有批次物理核验后原子发布版本化 L2 覆盖证书；Batch API 返回 `writeStatus`，空 QDB 配置明确为 `NOT_CONFIGURED`。新增默认关闭的 `BaiduShareClient`/`BaiduL2Subscription` 和 SQLite 转存意图台账；`BaiduPcsGoDownloader` 校验 UID、容量上限、磁盘空间后下载到唯一 `.incoming` 目录，原子记录来源身份和文件指纹以支持重启复用/未知状态阻断；管理 API 提供显式 dry-run/transfer/download，下载结果保持 `DOWNLOADED_UNVALIDATED`，必须经后续稳定期及归档检验后才可入库 | 已通过 QuestDB 10.0.1 三类 typed schema/精确回读测试，合成 JSONL 贯通 SQLite ledger→outbox→三张隔离表与覆盖证书实测；百度分享和 BaiduPCS-Go 均使用 loopback/命令替身及临时 SQLite 测试，未触碰真实账号或归档。仍缺真实 DFCF 日归档逐字段对照、特征和 Ready 证书、真实百度下载和账号绑定核验、最终本地发布/水位管理、实时常驻采集及网络断连/重复消息/断档矩阵；证据见 [L2 写入实测](evidence/l2-ingest-live.json)、[示例覆盖证书](evidence/l2-coverage-certificate.json)、[物化验收](evidence/l2-materialization.json)及[百度替身测试](evidence/baidu-share-adapter.json) |
| JDB-08 | 完成证书和连续水位算法；CPI/PPI/PMI/M2 月频与 GDP 季频 VERIFIED 观测均在 SQLite 原子记录并提供缺口分段 API；DataReadiness 校验同逻辑日、同日历、最新修订及隔离写入证书；核心来源计划已接入 fan-out | 全部基础派生、MV 健康、全资产覆盖/唯一键/PIT 的真实产品核验；完整数据产品连续水位与真实盘后周期验收 |
| JDB-09 | 外部执行 SQLite 登记、稳定 child identity、心跳/日志引用、父重启关联及可选固定 argv/no-shell executor；结构化进度、child 范围证书校验、退出码不能单独 Ready、超时不杀活进程/不重复启动 | 错误日期、定义版本漂移、陈旧文件、部分结果、超时存活/不重复启动已有 Java 子进程 fixture 覆盖；完整进程树/崩溃窗口矩阵及真实研究 runner 联调未启用；证据见 [子执行登记](evidence/external-execution.json) |
| JDB-10 | 八阶段 Batch 主链与可选分支替身测试；冻结计划驱动核心来源 fan-out；DataReady 接入 SQLite 来源台账证书聚合门禁，缺源、错范围、日历版本不符、新失败修订或隔离证书无效都会阻断；fan-out fixture 见[验证记录](evidence/core-source-fanout-20260930.json) | 真实隔离环境下完整 fan-out→DataReady 验收、真实基础派生步骤、月频/读模型原子发布、标签成熟、下游输入变化的 revision/invalidation 和业务 fixture 口径 |
| JDB-11 | 日期/窗口/回补策略函数、盘后持久 cron、有界 dry-run 分区 API；日任务按交易日历、月频按月初和 GDP 按季度末连续分区，周期范围上限为 `min(2000, source maxRows)`，月/季分区版本分别为 `month-start-v1`/`quarter-end-v1`；同一业务实例的新触发 ID 可重探失败的只读来源请求；新增 19:00–23:45 每 15 分钟和 23:55 的持久恢复触发及 21 次 SQLite 尝试台账；新恢复 requestId 保留为本次 trigger 身份而不覆盖 ledger 的 canonical 业务输入 | 真实回补执行队列、完整 N1/N2/主策略缺口执行器、历史分区配额调度；N2 目前明确 blocked |
| JDB-12 | 管理 API/CLI、健康检查、状态指标、审计查询、基本鉴权/幂等与逐任务暂停；新增 Prometheus 0.0.4 指标导出、月/季验证覆盖查询、未决状态只读核对队列及 backfill dry-run 契约 | 细粒度权限、完整日志接口、通知去重、带安全物理核验证据的未决处置与真正回补执行 API |
| JDB-13 | SQLite、真实 QDB 协议和 Java fixture 三类证据分开 | 逐产品对照、完整故障矩阵、容量/SLA 验收；没有编造性能结论 |
| JDB-14 | 独立安装包、示例配置、重启持久性测试、生产模式拒绝 smoke | 真实隔离常驻部署、自启动、定时触发、升级/版本回滚/备份恢复演练 |
| JDB-15 | 本交付记录及接线清单 | 前置任务全部通过后才可关闭 |

## 实际验证

- 原始工作区基线：`./gradlew test --console=plain` 通过。
- 新增核心契约与 SQLite 测试：`./gradlew test --tests 'com.zoutrankil.batch.*'`；QDB 测试无显式变量时跳过。
- 实际测试使用临时 SQLite 文件，不是 H2/mock。验证 Flyway 单文件多表迁移、持久化重启、Quartz 暂停状态、重复启动竞争、API 未授权拒绝、可选分支失败恢复、未知投递不重发、WAL 延迟/悬挂协议（后两者为 Port 故障替身）。
- 真实 QDB：`JDB_QUESTDB_TEST_URL=http://100.97.201.8:9000 ./gradlew test --tests 'com.zoutrankil.batch.QuestDbProtocolTest'` 通过。服务版本 10.0.1。写入一个唯一 `jdb_test_protocol_*` 表，一行源数据精确回读，模拟收到 ACK 后客户端丢失 ACK，发送次数为 1。证据：[questdb-protocol.json](evidence/questdb-protocol.json)。这不是业务表迁移验收。
- Java HTTP 客户端最初因 macOS 系统代理返回 502；测试显式直连后通过。失败时未创建表；不将网络失败误判为空结果。
- 国债曲线真实验收：`JDB_SOURCE_LIVE=1 JDB_SOURCE_DATASETS=cn_bond_yield_curve JDB_SOURCE_REPORT=build/reports/jdb-chinabond-live.json JDB_QUESTDB_TEST_URL=http://100.97.201.8:9000 ./gradlew --no-daemon test --tests com.zoutrankil.batch.NativeProducerLiveTest` 通过；日期 2026-09-28，来源返回 23 行，QuestDB typed 写入/读回均 23 行，重复 launch 发送次数仍为 1。原始证书在 `evidence/chinabond-yield-live.json`，含校验指纹与证书引用，不包含认证信息。
- `cyq_perf` 真实验收：从本地 `application.yml` 将 Tushare token 仅传给测试子进程环境（未打印/写入证据），日期 2026-09-28、证券 `000001.SZ`。来源 1 行、QuestDB 写入及全字段核验 1 行、重复 launch 后总发送次数仍为 1。证据见 `evidence/cyq-perf-live.json`；该样本不代表全市场/全历史通过。
- Flyway core 锁定 11.9.0；QuestDB 插件保持已有 10.26.0。PostgreSQL JDBC 驱动仍供旧应用访问 QuestDB PGWire；独立 Batch 运行与发布包已排除该驱动，且 `check` 自动验证 ZIP 必须含 SQLite JDBC、不得含 PostgreSQL JDBC。依赖清单锁定在根目录 `gradle.lockfile`。
- 此前隔离集成/部署验证见 [validation.json](evidence/validation.json)；最新全量测试计数与发行包摘要见 [full-build-20260930-final.json](evidence/full-build-20260930-final.json)。没有执行容量性能测试；SQLite 明确为单机单进程，未声称多实例 HA；没有正式 Python 联调。

## 启动与恢复

版本矩阵：JDK 24、Gradle 8.14.3、Boot BOM 4.1.1、Batch 6.0.5、Quartz 2.5.2。依据实际依赖和启动测试冻结。Batch 6 使用 JDBC JobRepository 注解；Quartz 原始建表脚本的 DROP 已移除，由 Flyway 只执行一次，避免重启清空记录。

```sh
./gradlew test installBatchDist batchDistZip
# 配置持久化 SQLite 元数据文件，复制并填写 deploy/batch/environment.example。
# JDB_API_TOKEN 至少 24 字符，服务固定绑定 loopback。
build/install/QuestDBWithData-batch/bin/questdb-batch
build/install/QuestDBWithData-batch/bin/questdb-batch-control tasks
build/install/QuestDBWithData-batch/bin/questdb-batch-control reconciliation
build/install/QuestDBWithData-batch/bin/questdb-batch-control run /absolute/path/request.json
build/install/QuestDBWithData-batch/bin/questdb-batch-control detail <instance-id>
build/install/QuestDBWithData-batch/bin/questdb-batch-control l2-inspect /absolute/path/under/archive-root/20260928.7z
```

发布包：`build/distributions/QuestDBWithData-batch-0.1.0.zip`。独立 jar 排除了旧 `application.yml`，不把旧配置中的凭证带入该发布包。旧应用发行包不是本交付的运行入口。

首次启动 scheduler 禁用、`post_close` 与 `source_cyq_perf` trigger 均暂停。要进行隔离定时测试，必须先完成对应 producer 绑定与验收，提供覆盖所测日期的日历和冻结的日期请求文件（例如 `$JDB_ARCHIVE_ROOT/requests/source_cyq_perf/YYYY-MM-DD.json`），显式启用测试 scheduler，再使用受鉴权 API 单独 resume 相应任务。日期/输入指纹不匹配会拒绝；默认未接通 producer 仍为 BLOCKED。示例日历仅为有限日期 fixture，不能作为完整交易日历。

百度分享转存和下载默认关闭。启用时需在本机私有文件中维护 Cookie（至少 `BDUSS`/`STOKEN`），POSIX 权限不得向 group/other 开放；Java 不读取浏览器凭据。配置 `JDB_BAIDU_ENABLED=true` 后，可用 `POST /v1/l2/subscription/transfer` 做分享查找与本人网盘转存；启用下载还须配置专用 BaiduPCS-Go 可执行文件、账号配置目录和预期 UID，并设置 `JDB_BAIDU_DOWNLOAD_ENABLED=true`。`POST /v1/l2/subscription/download` 的 dry-run 只规划；显式 `dryRun:false` 会转存（如需要）并下载到归档根目录 `.incoming/baidu/<日期>-<随机目录>/`。SQLite 在云端转存发送前记录意图，未知结果重启后只核验，不自动重发。下载 API 返回 `DOWNLOADED_UNVALIDATED`；待归档稳定后，使用 `/v1/l2/archives/inspect` 执行 ZIP/7z 校验和冻结，再运行 Batch 入库工作流。下载成功本身不表示 L2 数据 Ready，见[百度适配器验收](evidence/baidu-share-adapter.json)。

恢复同一业务日期可使用新 requestId，保留业务范围、定义、revision、输入、日历和时区；同一 requestId 重试复用原实例。已验证阶段不重算。已产生写入意图的输入变更或已完成实例重算时使用新 revision，带 supersedes 与原因。未发送源实例同范围晚到重绑规则见原生源说明。IN_DOUBT/RUNNING 遗留实例必须先查明子进程和副作用；当前版本不提供强制清锁/强制重放接口。

隔离备份/升级建议（**尚未演练**）：暂停未来调度，等待/核验当前执行；备份 SQLite 数据库文件（连同 WAL/SHM 文件或在停机后备份）、归档和构建包摘要；安装到新版本目录，使用同一元库执行 Flyway 校验；旧包保留。回退前审核元数据兼容性与未决批次，不能仅换包后自动重跑。不要设置 Quartz `initialize-schema=always`，不要删除未知批次的目标占用。`compose.yml` 只是可选隔离环境模板，当前宿主无 Docker，未部署。

## 副作用与后续工作

本次只修改 Java 仓库。没有修改或启动 Python/back-monitor 代码、脚本、配置、API、前端或原定时任务；没有发送通知、改变权限、创建订单或接管生产 owner。真实 QDB 仅新增证据文件所列协议及原生源专用测试表，保留样本以便复查；没有修改现有业务表。临时 SQLite 测试文件及 API 测试端口均随测试结束清理。未安装常驻服务。

Java 本轮未完成能力见上表，不能归到“后续 Python 接线”而跳过。它们完成后，未来跨仓接线还须另行实施：

1. 与受支持 Python 计算入口对齐 run/date/input/schema，冻结并测试子执行环境；正式算法不移植。
2. 将旧任务中心 adapter 对接本文 API，保留业务状态和技术状态区别。
3. 只读核实旧调度及数据 owner，形成明确停用清单，再按单独授权停用旧时钟。
4. 通过对照及回滚门禁后转移生产写入 owner、启用生产与稳定观察；本包显式不提供生产模式。

框架参考：[Spring Batch JDBC infrastructure](https://docs.spring.io/spring-batch/reference/job/configuring-infrastructure.html)、[Spring Boot Quartz](https://docs.spring.io/spring-boot/4.1/reference/io/quartz.html)。这些框架能力不是本项目全部业务验收的替代证据。


`etf_basic` 的 Java 原生快照 producer 已实现并通过 57 项契约、映射及 SQLite 管理运行测试；契约已进入 Batch ZIP。由于此前本地来源凭证需要轮换，本次没有访问 Tushare 或写 QuestDB；该来源尚未实库验收，见[实现记录](evidence/etf-basic-implementation.json)。

`etf_share` 已新增逐交易日 `fund_share(trade_date)` 全市场 producer：冻结旧表中的 `(ts_code,timestamp)` 去重键与 YEAR 分区，映射 `trade_date` 到物理 `timestamp`，并对旧物理模型仍保留的 `fund_type`/`market` 空值不做臆造。Tushare 官方说明单次上限为 2,000 行；请求不分页且达到上限即判为潜在截断，空响应不签发 ready 证书。合同、mapping 和 SQLite task/source 注册通过 fixture 测试；未做真实来源请求、QuestDB 物理 schema 核验或写入，checkpoint 与日期范围增量执行仍待完善，见[实现记录](evidence/etf-share-implementation.json)。
