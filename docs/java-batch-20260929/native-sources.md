# JDB-05 原生源生产增量

状态：`daily`、`daily_basic`、`etf_daily`、`stk_limit`、`etf_adj`、`moneyflow`、`etf_factor`、`margin_detail`、`moneyflow_hsgt`、`stk_suspend`、`etf_portfolio`、`stk_factor`、`stk_st_daily`、`cn_bond_yield_curve`、`cyq_perf` 已有 Java 原生生产路径并通过有限真实来源/数据库验收；`fina_mainbz` 也已注册原生 Batch source job，对 000001.SZ、2026-06-30 报告期真实采集 P/D 两类 32 行并完成隔离 QuestDB VERIFIED；仍只覆盖单证券单报告期，VIP 全市场采集和 checkpoint 未完成，见 [主营业务构成验收](evidence/fina-mainbz-live.json)。P/D 数据按遗留 `(ts_code,end_date,bz_item)` 键检测碰撞，碰撞时拒绝快照。`exchange_calendar` 也已注册原生 Batch source job，并于 2026-09-29 对 SSE/SZSE 的 2026-09-28 各一行完成真实来源与隔离 QuestDB typed 写入/回读，证据见 [日历源实测](evidence/exchange-calendar-live.json)；目前仍未发布全历史版本日历或接入运行时 calendar 水位。`index_daily_market` 与 `index_daily_basic` 已注册 Java contract/producer；2026-09-29 完成同一逻辑日的真实 Tushare→隔离 QuestDB typed 写入/回读，行情覆盖核心五指数、`000985.CSI` 和通过 `sw_daily` 获取的 `801080.SI`，基本面覆盖核心五指数，两产品单次发送后均精确读回并 VERIFIED，见 [指数源实测](evidence/index-daily-live.json)；**不代表 14 张核心表全部完成，不代表全市场 DataReady，也不关闭 JDB-05/JDB-06**。`cn_bond_yield_curve` 仅完成 2026-09-28 单日、23 行来源和隔离表写入验收；未完成旧 Python 冻结输出对照。该来源由公开 ChinaBond 页面提供，不调用 Python 入口。JDB-06 新增 `cyq_perf` 单证券日样本验收，分页上限/offset/终止短页和固定 universe 覆盖有测试；指数源已通过五个核心指数单日 multi-code 真实来源与 QuestDB 实测；`index_daily_market` 代码契约接受标准中证 `.CSI` 后缀，并用 `000985.CSI` 完成额外真实单日来源/隔离写入，仍未完成扩展目录/全市场覆盖、旧 Python 输出对照和历史区间验收。申万 `.SI` 行情现由 Java 按代码后缀独立路由 `sw_daily`；字段 `pct_change` 映射为目标 `pct_chg`，来源不含的 `pre_close` 保留为 null，未做量额单位转换以保持旧 connector 行为。已验收一个 `.SI` 代码与普通指数同批混合写入；普通 `index_daily` 接口不含申万，完整 31 代码 universe 和旧 Python 输出对照仍未完成。没有完成整日全市场及从 2018 起的回补。后续任务继续按原范围推进。

`fut_daily`、`fut_settle` 与 `fut_mapping` 已新增 Java contract、Batch job 和有限 scope。前两者按月合约/单交易日采集；`fut_mapping` 按连续合约/单交易日采集并校验返回的月合约代码。三表沿用旧模型业务字段与业务键，`update_time` 在写入时生成。2026-09-28 对 `IF2610.CFX` 的日线和结算源、对 `IF.CFX` 的映射源真实 Tushare 采集各返回 1 行，在隔离 QuestDB 完成 typed 写入与全字段回读，均为 `VERIFIED`、一次发送；证据见 [期货日线](evidence/fut-daily-live.json)、[期货结算参数](evidence/fut-settle-live.json) 和 [主力合约映射](evidence/fut-mapping-live.json)。fixture 另覆盖 scope、非法月份合约、字段映射和技术更新时间。仍未验证全市场/历史覆盖、修订传播或旧 Python 冻结结果对照。

`ft_limit` 按旧字段、键 `(trade_date,ts_code)` 和生成的 `update_time` 新增 Java job；首期限定单个合约和交易日，校验涨跌停非负且顺序有效。Tushare `IF2610.CFX` / `2026-09-28` 返回 1 行并在隔离 QuestDB 完成 typed 写入及全字段回读 `VERIFIED`，单次发送，见 [期货限价验收](evidence/ft-limit-live.json)。全市场覆盖、历史修订和 Python 冻结基线对照仍未验收。`fut_holding` 已新增原生 Java 合约和 Batch job，按一个产品品种、单个交易日、CFFEX 查询，使用旧业务键 `(trade_date,symbol,broker)`；Tushare 文档将 `exchange` 标为可选，Java 保留其 nullable 语义，`update_time` 写入时生成。四次真实 Tushare 探测覆盖 2019-01-02、2024-01-02、2026-09-01、2026-09-28，均完成请求但返回 0 行，因此没有写入 QuestDB；不能验收为来源成功或 verified-empty，见 [空响应探测记录](evidence/fut-holding-empty-probes.json)。实现和全量回归/打包证据见 [实现验收记录](evidence/fut-holding-implementation.json)，其中 QuestDB 目标未创建、物理 schema 仍未实测；该源的隔离数据库全字段回读仍未验收。

## 实现与范围

- `SourceContract` + `contracts/source/*.json` 固定来源字段、目标字段、类型、业务键、单次 cap 和分页策略。股票日线保留未复权价格、盘后量额、手/千元单位及 null；ETF 源 `trade_date` 映射到物理 `timestamp TIMESTAMP_NS`。`daily_basic` 为 `trade_date TIMESTAMP`。
- `SourceCollector` 复用现有 Tushare HTTP/限流/有界重试及 PageExecutor。按明确逻辑日采集，检验重复页/键、来源日期、类型和预期证券集合。满 cap 的未分页响应不能证明完整。内容按完整键排序、原子保存并计算 SHA-256；时间不参与内容身份。错误/权限失败不成为空证书。
- `SourceQuality` 按只读旧 `data_quality_rules.yaml` 冻结相关空值阈值与领域规则。除 moneyflow 九个整数量字段沿用旧 fillna(0) 之外，其余合法 null 均保留；没有额外规定 ETF 开盘价必须大于零。质量不通过可保留冻结来源，但不能写出 Ready。
- `QuestDbTablePort` 是真实 typed HTTP/ILP adapter，发送前校验目标完整 schema、dedup keys、日期列精度、WAL 状态及保留的实际发送内容。普通产品每批最多 1000 行，四键持仓产品每批 50 行，均不超过 4 MiB。精确回读所有业务列与 null，浮点容差预先固定为 **0.0**；不等待全表 writerTxn==sequencerTxn。
- `SourceProductRunner` 对固定来源分批写入、逐批核验，再检查整个快照总行数。每个元数据库持久化独立 namespace；目标为 `jdb_test_<namespace>_<dataset>_<instance>`。这是保留旧版本的不可变产品快照，证书记录具体 target，不原地替换任何旧业务表。
- `source_daily`、`source_daily_basic`、`source_etf_daily`、`source_stk_limit`、`source_etf_adj`、`source_moneyflow`、`source_etf_factor`、`source_margin_detail`、`source_moneyflow_hsgt`、`source_stk_suspend`、`source_etf_portfolio` 是真正的 Spring Batch Job，经过与手动/Quartz 相同的 LaunchService、SqliteLedger 和 DurableWriter。已验证实例重复调用不再发送。ACK 待可见时以同一 Batch 实例继续验证。UNKNOWN 保留占用并阻止自动重放。
- 源晚到时，只在同一日期、定义、预期证券集合、universeVersion、日历和时区不变，且**尚无任何写入意图**、业务未开始/未完成的情况下，允许重新绑定来源指纹。绑定在业务互斥内完成并审计；Batch 仅用稳定 instance 参数标识实例，输入指纹是执行参数，不另建 Batch 实例。产生过任何 intent 后仍必须使用显式 revision，不能偷偷换输入。

这里的预期证券集合必须是该请求的真实覆盖范围。单证券请求只能证明该证券的数据，不能代替全市场证券主数据/停牌口径的覆盖认证。当前 DataReady 没有因此自动开放，原生其余源与基础派生仍需完成。

## API/CLI

用独立持久化 SQLite 元数据库文件（只允许一个 Java 服务进程）启动 `questdb-batch`；设置本地 `JDB_TUSHARE_TOKEN`、`JDB_QUESTDB_HTTP_URL` 和可选 `JDB_QUESTDB_HTTP_AUTHORIZATION`。不把 token 作为 API 请求参数或写进源码。服务固定绑定 loopback，并要求管理 Bearer token。

```sh
questdb-batch-control sources
questdb-batch-control collect /absolute/path/source-collect.json collect-daily-20260928-001
```

采集请求例子见 `deploy/batch/source-collect.example.json`。`expectedCodes` 和 `universeVersion` 必填；传 `tsCode` 时必须与单证券预期集合一致。不传 `tsCode` 时读取该交易日完整来源，仍须提供冻结的预期集合。采集接口要求幂等键，同键重试返回持久化探测结果；不会重复扣来源配额。探测失败或仍在执行不会伪装成功。

来源成功采集后，结果里的 `result_json` 包含 `fingerprint`。构造标准 RunRequest：

- `job` 为 `source_<dataset>`；
- `definitionVersion` 分别为 `daily-v1`、`daily_basic-v1` 、`etf_daily-v1`、`stk_limit-v1` 、`etf_adj-v1`、`moneyflow-v1` 、`etf_factor-v1` 、`margin_detail-v1` 、`moneyflow_hsgt-v1` 、`stk_suspend-v1` 或 `etf_portfolio-v1`；
- `logicalDate/rangeStart/rangeEnd` 都是本次单日（多日回补应分区）；
- `inputFingerprint` 为采集返回的指纹；
- 其余请求身份、revision、日历、时区和触发字段仍按标准契约提供。

```sh
questdb-batch-control run /absolute/path/source-run.json
questdb-batch-control detail <instance-id>
```

接口 `GET /v1/sources` 不发来源请求；`POST /v1/sources/collect` 只采集/冻结，不写 QuestDB；`POST /v1/runs` 是统一受控写入/恢复入口。来源请求体上限 1 MiB，运行请求仍为 64 KiB。完整契约见 `openapi.yaml`。

## 验收证据与限制

`NativeProducerLiveTest` 的历史 13 源实测矩阵使用真实 Tushare、临时 SQLite 文件和真实 QuestDB，日期覆盖 2026-09-28（证券表一个证券、汇总表一个日期记录；持仓公告使用 2026-08-29）。行数和产品清单见 [`evidence/native-producers-20260929-matrix.json`](evidence/native-producers-20260929-matrix.json)。新增 `cyq_perf` 和 `cn_bond_yield_curve` 单独验收，分别见对应证据文件。以上都是有界样本验收，非全市场覆盖。原始 provider 响应、冻结来源、ILP、证书保留在 Java 仓库 `var/jdb-source-acceptance/`；SQLite 临时元数据库文件随测试清理，专用 QDB 测试表保留。

`FrozenSourceParityTest` 使用捕获的 provider 输入和旧 QuestDB 输出快照：daily 与 etf_daily 样本逐字段、日期、null 和数值完全一致。它不是“重新运行 Python 处理同一冻结输入”的证明。daily_basic 的 2026-09-28 旧库无对应行；只读检查发现该证券旧库最近日期为 2026-09-21，故该日对照证据明确缺失，未换一个更容易的日期冒称通过。

单元/实库测试还覆盖类型漂移、错日期、重复键、缺证券、错误空窗口、来源文件篡改、幂等来源探测、旧质量规则、晚到数据的同实例重绑、已有 intent 禁止换输入、并发触发和可选分支恢复。

仍需完成：其余核心 producer、完整参考主数据和停牌覆盖、实际全市场样本、多日/大规模分批与容量验证、正式 Python 冻结基线、各源修订/频率/迟到规则、完整未知 sender 对账操作接口。源任务当前按单日快照交付；不能宣称全周期历史回补已接通。

技术/字段依据：
- [Tushare daily](https://tushare.pro/document/1?doc_id=27)
- [Tushare daily_basic](https://tushare.pro/document/2?doc_id=32)
- [Tushare fund_daily](https://tushare.pro/document/2?doc_id=127)
- back-monitor 对应 connector、model、`data_quality_rules.yaml` 的只读内容；物理类型以仓库 schema 快照及真实新建/回读验证为准。

新增 `stk_limit` 保持四个物理字段，未请求的 pre_close/asset_type 不写入；文档未承诺 offset 分页，达到 5800 条 cap 直接判为不完整，不假装全市场完成。`etf_adj` 使用 fund_adj 的 limit/offset，单页 2000；trade_date 映射到物理 timestamp TIMESTAMP，复权因子原值保留，不计算或重写复权行情。两个来源目前只固定键完整性、日期、类型、重复和覆盖检查，未在旧规则之外新增价格/因子范围门禁；这两个来源的 null 不填零。测试验证 ETF 2001 行跨页及涨跌停满 cap 拒绝。

- [Tushare index_daily](https://tushare.pro/document/1?doc_id=95)（CSI 后缀依指数发布方；接口不含申万行业行情）
- [Tushare index_dailybasic](https://tushare.pro/document/2?doc_id=128)
- [Tushare stk_limit](https://tushare.pro/document/2?doc_id=183)
- [Tushare fund_adj](https://tushare.pro/document/2?doc_id=199)

本次完整验证：`./gradlew test installBatchDist batchDistZip`，显式开启真实来源和隔离 QuestDB 测试，200 项测试中 180 通过、20 按环境条件跳过、0 失败。新增来源旧库对照见 `evidence/additional-source-reference.json`：etf_adj 三列映射后完全一致，stk_limit 指定日无旧库行。最初新增分页测试以 Integer 比较 Long 失败；改为 Long 后完整重跑通过，未放宽生产校验。

## 资金流与 ETF 技术因子增量

`moneyflow` 使用官方 moneyflow 接口，保留九个量字段 LONG（手）、九个金额字段 DOUBLE（万元）、证券与交易日期。量字段源 null → 0 与旧 connector 的明确转换一致；金额 null 保留。净流入保持来源值，不能用其他买卖字段求和替代，允许负值。非整数成交量拒绝写入，不能静默截断。未声明 offset 分页，满 6000 行拒绝完整性认证。

`etf_factor` 使用 fund_factor_pro，89 个物理字段逐列投影；不计算研究因子，不改变 _bfq 指标定义或单位。排除来源技术辅助列 trade_date_doris，保留必需的原始 trade_date；日期缺失不会拿请求日期补造。指标 warm-up 空值与负指标原样保留。满 8000 行拒绝完整性认证。真实接口和隔离目标已完成全字段核验，测试仍只覆盖一个明确证券与一个日期。

只读旧库检查显示 moneyflow、etf_factor、stk_factor 在指定证券/2026-09-28 都无对应行，因此不能宣称新增两表已取得旧库同日对照，见 `evidence/factor-moneyflow-reference.json`。

stk_factor 已注册并通过冻结输出对照。只读检查发现旧物理表的 35 列与旧 connector 所调用的 stk_factor_pro 专业版字段确有差异；因此 Java 没有猜测映射。官方历史 stk_factor 接口返回与旧表相同的 35 个字段，指定证券 2026-09-24 的实际来源与只读旧表逐列零容差一致。新 producer 接入该历史接口并保留旧物理 schema；这不等于修复旧 Python connector，也不宣称 2026-09-28 同日旧表对照。实际旧快照、专业版诊断与字段差异分别记录在 `evidence/stk-factor-reference.json`、`evidence/stk-factor-legacy-provider-diagnostic.json`、`evidence/stk-factor-provider-diagnostic.json`。

依据：[moneyflow](https://tushare.pro/document/2?doc_id=170)、[fund_factor_pro](https://tushare.pro/document/2?doc_id=359)、[stk_factor_pro](https://tushare.pro/document/2?doc_id=328)，以及只读旧 connector/model/物理 schema 快照。

最新七来源完整验证：202 项测试，182 通过、20 按环境条件跳过、0 失败；`installBatchDist`、`batchDistZip` 成功。七个真实源产品全字段回读及重复请求不重发通过。此前五来源运行记录保留于 evidence/history；最新证据见 evidence/validation.json。

## 两融明细与原始页归档

margin_detail 的 11 列固定类型/顺序来自物理 schema。八个数值字段均为 DOUBLE，保持元/股/份/手的来源单位；证券名称保留 STRING 和合法 null。不把融资、融券数量换算成金额。只忽略 trade_date 等于请求日期、ts_code 精确等于 `日期：YYYY-MM-DD.BJ` 且八个数值字段均为空的来源尾行；不是这一特例的异常代码仍失败。只有尾行的响应为 WAITING_SOURCE，不得成为 VERIFIED_EMPTY。

原始页仍参与源 cap、页指纹和重复键检查，因此过滤不会掩盖满 6000 行的未分页截断。来源探测收据记录 sourceRows、filteredFooterRows、有效 rows 和 rawArtifacts。所有已注册来源现在都会把不含凭据的原始 Page JSON 按 SHA-256 保留在 `sources/raw/`，在清洗前落盘，独立限制原始行数和总字节数；规范化内容身份仍不含探测时间。出错的原始页可以保留用于核验，但不产生完成证书。

发布规则依据：[Tushare margin_detail](https://tushare.pro/document/2?doc_id=59)：上一交易日数据在后续发布窗口到达，深/北交易所周五数据有周一上午发布差异。当前 producer 接收显式业务日期；未自动用机器今天替代，未接通 08:50 发布后回看定时。JDB-05/06 的完整发布/回看验收继续保持未完成。

只读旧库指定证券/日期无记录，见 `evidence/margin-reference.json`；没有以换日期或忽略空值获得虚假的对照成功。

最新八来源完整验证：204 项测试，184 通过、20 按环境条件跳过、0 失败，安装目录与 ZIP 构建成功。后续完整真实验收结束后可执行 `python3 tools/record_java_batch_validation.py --log /absolute/path/gradle.log`，工具要求测试结果无失败、真实来源/协议测试确实执行，并保留历史证据和累计测试表记录；它不会把总体需求标为完成，也不代替逐项业务验收。

## 沪深港通日期汇总

moneyflow_hsgt 保留日期和六个 DOUBLE 金额字段（百万元），不计算或重命名资金口径。请求必须 `expectedCodes: []` 且 tsCode 缺省/null，universeVersion 标识汇总范围版本；来源按 start_date=end_date=logicalDate 读取，只有一个正确日期的记录能证明覆盖。空响应仍为 WAITING_SOURCE。不得构造 MARKET 等虚假证券代码；证券产品依旧禁止空预期集合。示例见 `deploy/batch/source-collect-aggregate.example.json`，OpenAPI 具有条件范围约束。

真实接口六个金额字段返回十进制字符串，旧 connector 显式执行 to_numeric。Java 仅对该来源允许有界、有限十进制字符串（含科学计数法）转换为 DOUBLE；null 原样保留。不像旧 errors=coerce 那样把不可解析文本静默转 null；异常文本、NaN、Infinity 失败，其他来源仍严格拒绝字符串数值。首轮真实验收因字符串类型被严格校验挡下，保留原始页后核实旧转换并修复，不是绕开校验。

QuestDbTablePort 以完整业务键索引实际/预期行，汇总表按日期查询，不依赖 ts_code。逐列比对依旧零浮点容差，日期或任一金额不一致都不通过。旧物理表没有启用去重键；新隔离快照显式用 trade_date 做 WAL DEDUP 键，属于本次 Java 隔离存储策略，不宣称物理配置与旧表完全相同，也未修改旧表。

官方旧 doc_id=47 当前返回 404；契约依据是只读旧 connector、model、schema 快照和本次真实接口响应，而非引用第三方说明作为官方承诺。旧库指定日无记录，`evidence/hsgt-reference.json` 明确保留对照缺失。此处实现没有补齐完整历史频率、最新来源披露口径审核及全产品验收。

最新九来源完整验证：206 项测试，186 通过、20 按环境条件跳过、0 失败。Gradle 测试、安装目录和 ZIP 构建通过；最新实际报告与历史报告分别归档。真实验收固定 2026-09-28，八个证券来源各一个证券，汇总来源一个日期记录，不表示完整九表全市场验收。

## 停牌稀疏快照与 verified-empty

stk_suspend 原生调用 suspend_d(trade_date, suspend_type=S, ts_code 可选)。来源原始字段是 ts_code/trade_date/suspend_type/suspend_timing，原始页先归档再转换为目标 ts_code/is_suspended/timestamp。保留旧规则：筛选 S、代码 trim、同证券多段停牌合并为一个正标志 1；不把原始日内事件直接当成重复日键报错。未知类型、错日期、非法/范围外代码失败；未分页原始响应达到 5000 行时失败，不因过滤或合并变短而错误认定完整。

该稀疏产品的 expectedCodes 是要探测的证券范围，不是强制每个证券都有停牌行。来源完整返回后，观测证券可为其子集，包括零行。来源错误或 null 响应仍失败；成功空来源先为 VERIFYING，不能在采集阶段成为 Ready。

V5 数据库迁移允许零行意图，V1 历史迁移保持原样。零行快照使用相同目标预约、源摘要/空 ILP 留存、持久状态和物理核验；QuestDbTablePort 只对 emptyAllowed 产品允许空批次，预检建立并验证 typed WAL 表，零行 send 不发数据 POST，核验必须确认整张独立快照表为零行且 WAL 未 suspended。已有任何行都会阻止空证书。该路径不会清空、删除或覆盖旧表；修订产生新快照，消费者仍需绑定证书版本。

真实 Tushare/临时 SQLite 文件/QuestDB 验收中，000001.SZ 在 2026-09-28 返回零个停牌事件，得到 VERIFIED_EMPTY 并验证重复入口复用。意图 attempt=1 表示一次协议处理，不是一次数据 POST；报告 dataPostCount=0 根据空协议路径计算，另有 HTTP 端口测试断言实际 POST 次数为零。非空、多段停牌合并和复牌过滤由固定输入测试覆盖，尚缺真实非空停牌样本、全市场覆盖和历史修订的完整矩阵，不关闭 JDB-05。

依据：[Tushare suspend_d](https://tushare.pro/document/2?doc_id=214) 与旧 stk_suspend_sync.py 只读转换规则。

最新十来源完整验证：208 项测试，188 通过、20 按环境条件跳过、0 失败。测试包含真实 V1→V5 元数据库迁移、真实停牌空快照、既有九来源回归、空协议无 POST 和已有行拒绝空证明；安装目录与 ZIP 构建成功。

## ST 历史有效区间快照

`stk_st_daily` 使用 `namechange` 而不是 `stock_st` 当日快照。Python 旧 producer 的语义是：对 `name` 中不区分大小写含 `ST` 的记录，按 `start_date`/`end_date` 有效区间投影到逻辑日，并仅输出 `is_st=1` 的正标记。公告日期与生效日期不同，因此 Java 将公告查询按 2010 年至逻辑日所在年分年请求，并在每年按 offset 分页；所有原始公告先归档，完整扫描结束后再转成按代码去重的日快照。空结果仅在全部公告窗口有终止证据后合法。

`stock_st(trade_date)` 官方快照在 2026-09-28 返回 203 行，而现有 `stk_st_daily` 同日返回 280 行，官方快照结果是旧表子集，不能用于替代有效区间算法。未来 start_date、已结束 end_date、非 ST 名称不纳入逻辑日结果；异常公告字段、游标不前进、页重复、来源失败均阻断证书。合同、转换测试和真实历史年度扫描已加入；非空证券旧库冻结同输入对照、全市场历史扫描与日快照对照仍待执行，因此不关闭 JDB-05。

依据：[Tushare namechange](https://tushare.pro/document/2?doc_id=100)、[Tushare stock_st](https://tushare.pro/document/2?doc_id=397)及只读旧 connector 与同日查询。名称历史日快照有意按原 connector 字符串规则匹配 `ST`，不擅自缩窄为仅特殊处理证券。

2026-09-29 实机验收使用 `000001.SZ`、逻辑日 2026-09-28、真实 Tushare、临时 SQLite、隔离 QuestDB；17 个年度页（空页也逐页归档）完整终止，结果为 0 行 `VERIFIED_EMPTY`，重复同一 Batch 请求的发送尝试为 1。原始页和探测回执位于本机归档 `var/jdb-source-acceptance/d4faee35-ec24-452a-9fc0-429f30fc6370/`；脱敏汇总见 `evidence/stk-st-producer-live.json`。本样本没有 ST 证券，真实非空 ST 区间与全市场历史扫描仍缺。

## ETF 持仓公告、多日期与四键写入

etf_portfolio 调用 fund_portfolio，按公告日 ann_date 读取，limit/offset 每页 8000，原始/规范化字节上限各 32 MiB、行上限 100000。业务时间为公告日，报告期 end_date 保留为物理指定时间列，不强制改成公告日；拒绝未来报告期和缺失公告日，不用报告期替补公告日从而伪造可用时间。四个键为 ts_code/ann_date/end_date/symbol；同持仓证券跨报告期不合并。基金代码支持 .OF，普通股票/ETF 行情请求的代码规则不变。公告是稀疏窗口，来源探测完整后的无公告可走已实现空快照协议。

八个来源业务字段冻结并参与源身份；第九列 update_time 是技术处理元数据，在准备写入时取 SQLite 已持久化的业务实例 created_at（截到微秒），不会每次恢复重取机器时间，也不会把处理时间混入来源指纹。写入后的物理核验包含这列。新 revision 生成独立实例，允许新处理时间；旧证书仍不覆盖。

非指定 TIMESTAMP 列使用 ILP 微秒 t，指定时间使用纳秒行尾；按完整四键组成有界回读谓词，50 行一批，不用基金代码去错误合并多只持仓。中间批次可独立确认，总快照必须行数一致。相关边界测试包括同一证券的两个报告期、源顺序不影响指纹、未来报告期拒绝、技术时间微秒精度和重复物理键拒绝。

真实验收固定样本：510300.SH，公告日 2026-08-29、报告期 2026-06-30；来源 324 行，7 个写入批次，全部 9 列回读通过，重复执行未增加发送次数。这个公告日是自然日，不能套用只在交易日运行的日行情规则。它是额外的历史公告样本，不冒称其余来源 2026-09-28 日行情样本。

旧库同范围 82 行，82 个共同键的八个业务字段完全一致；新来源另外 242 行，没有旧库对应键。两次获取不是同一冻结输入执行，不能认定全量 Python 对照通过，见 evidence/portfolio-reference.json。技术 update_time 因处理实例不同不参与旧业务值对照，但参与本次物理写入核验。

依据：[Tushare fund_portfolio](https://tushare.pro/document/2?doc_id=121)、[QuestDB ILP 字段时间精度](https://questdb.com/docs/connect/compatibility/ilp/columnset-types/)，以及旧 connector/model/物理 schema 的只读内容。完整全市场公告覆盖、历史修订传播和周期编排仍待验收。

SQLite 切换后的历史完整回归记录为 317 项测试、0 失败、62 项按环境条件跳过；后续完整构建结果以 [full-build-20260930.json](evidence/full-build-20260930.json) 为准。另有 `cn_bond_yield_curve` 独立来源验收。`cyq_perf` 单证券真实采集 1 行，临时 SQLite 台账和 QuestDB 隔离目标通过幂等、全字段核验。QuestDB 协议测试一行写入后模拟 ACK 丢失，最终核验 VERIFIED 且发送次数仍为 1。安装目录和 ZIP 已重新构建，独立 Batch ZIP 含 SQLite JDBC、无 PostgreSQL JDBC。该结果仍是有限证券/日期验收，不能代表全市场覆盖。


`dividend` 已补齐独立 Java Batch source job 和 v1 字段契约：当前每次请求限制为一个证券、一个公告日，沿用旧 `end_date` 空值归一规则，并对遗留去重键冲突拒绝快照。单元测试覆盖字段类型、scope、sentinel 与冲突；已于 2026-09-30 对 `000001.SZ` 的 2026-05-23 一行执行真实 Tushare→隔离 QuestDB typed 写入、回读及重复 launch 验收（VERIFIED、发送 1 次）。同证券 2026-08-15 真实响应有 3 行但仅 1 个旧去重键，Java 明确拒绝而不静默丢失；完整日期/市场覆盖、生产物理 schema 和 Python 冻结结果对照仍未完成，证据见 [分红源实测](evidence/dividend-live.json)。


`shibor` 已接入单日期间的 Java Batch 来源任务，字段保留 Tushare 与 QuestDB 的原名（`date`→指定时间列 `timestamp`，以及 `on`、`1w`、`2w`、`1m`、`3m`、`6m`、`9m`、`1y`），为 SQL DDL 标识符统一加引号；来源请求限定同一 `start_date`/`end_date`，空日结果仅在探测完整时允许。真实 Tushare 2026-09-28 返回 1 行，隔离 QuestDB 九列精确 typed 写入/回读 VERIFIED，重复启动未重发，证据见 [SHIBOR 实测](evidence/shibor-live.json)。还未接入默认增量 checkpoint、修订回看、完整历史/日历水位和 Python 冻结结果对照。


`shibor_lpr` 复用有界来源与报价合同，对 `2026-08-20` 同日范围进行 Tushare 采集；真实来源返回 1 行，原名 `1y`/`5y` 字段在隔离 QuestDB 精确回读并 VERIFIED，重复启动没有重发，见 [LPR 实测](evidence/shibor-lpr-live.json)。接口对非发布日会合法返回空结果，因此只允许来源探测完成且完整日期范围的 verified-empty。月度完整性、增量 checkpoint、正式表 schema 和 Python 冻结结果对照仍未完成。


`hibor` 已加入同一日期限定的原生 Batch 采集，按 Tushare 日期字段映射 `date`→QuestDB `timestamp`，保留 8 个隔夜/期限原名。真实 Tushare 于 2026-09-28 返回 1 行，隔离 QuestDB 全字段 typed 写入与回读 VERIFIED，重复启动未重发，证据见 [HIBOR 实测](evidence/hibor-live.json)。数字开头的期限字段由受限的 ASCII 字段语法接受，DDL 使用引用标识符；尚无完整日历史、checkpoint 或 Python 基线对照。

`cn_cpi` 接入月频 Java Batch producer，单月沿用 `m=YYYYMM`；月度区间使用 `start_m`/`end_m`，并要求冻结结果逐月连续无缺口后才允许进入 writer。月份时间戳仍映射为月初并保留全部 13 个字段。固定输入测试验证连续范围、缺少中间月份时标记 PARTIAL、完整空单月保持原语义。真实来源 `2026-07` 单月及 2016-01 至 2026-07 的 127 月历史窗口均在隔离 QuestDB 完成 typed 回读/质量核验，并把连续范围记入临时 SQLite，见[CPI 单月](evidence/cn-cpi-live.json)与[CPI 历史窗口](evidence/cn-cpi-history-live.json)。`cn_m` 的真实三个月和 127 个月验收见[M2 区间](evidence/cn-m-range-live.json)、[M2 历史窗口](evidence/cn-m-history-live.json)。更早历史、发布滞后回看、旧 Python 输出对照和正式物理 schema 仍未验收。


`cn_ppi` 与 `cn_pmi` 已接入月频 Java Batch producer，分别保留遗留物理模型全部 31/60 列（含月份键）。单月沿用 `m=YYYYMM`，区间请求使用 `start_m`/`end_m` 并执行逐月连续性校验；月份时间戳归一化为月初。真实 Tushare 2026-07 单月验收见[PPI](evidence/cn-ppi-live.json)与[PMI](evidence/cn-pmi-live.json)；两源另对 2016-01 至 2026-07 的 127 个月窗口完成真实来源、SQLite 连续覆盖登记和隔离 QuestDB typed 回读，均为 `VERIFIED`、一次发送，见[PPI 历史](evidence/cn-ppi-history-live.json)与[PMI 历史](evidence/cn-pmi-history-live.json)。更早历史、发布滞后回看、旧输出对照和正式物理 schema 仍未验收。


`cn_m` 已接入月频 Java Batch producer，保留 `month` 加 9 个货币供应量指标，单月 `m=YYYYMM` 请求，区间请求使用 `start_m`/`end_m` 并要求月份连续；旧格式 timestamp 定位到月初。2026-07 单月、2026-05 至 2026-07 三个月区间，以及 2016-01 至 2026-07 的 127 个月历史窗口均完成真实来源、隔离 QuestDB typed 回读、SQLite 连续水位和质量核验，`VERIFIED`、一次发送。证据见[单月](evidence/cn-m-live.json)、[三月区间](evidence/cn-m-range-live.json)及[历史窗口](evidence/cn-m-history-live.json)。质量门禁覆盖下游读取的 `m2_yoy`。1990 年代历史、全部完整历史、发布滞后回看、修订重放、生产 schema 与 Python 结果对照仍未验收。

`cn_gdp` 已接入季度 Java Batch producer，按季度请求 Tushare 并将 `quarter` 映射为旧表季度末 `report_date`（例如 `2026Q2`→`2026-06-30`），保留旧模型 10 列。除单季 `q` 外，历史范围使用官方支持的 `start_q`/`end_q`，逐季校验连续覆盖后才写入；SQLite 在 VERIFIED 同一事务内记录每季观测，`/v1/coverage/quarterly` 保留缺口分段。隔离测试覆盖跨年连续范围、漏季 PARTIAL、回补计划和元数据水位。真实 Tushare/SQLite/隔离 QuestDB 对 1992Q1–2026Q2 的 138 季历史范围回读 VERIFIED、一次发送，连续水位为 138 季，见 [GDP 历史验收](evidence/cn-gdp-history-live.json)。另探测到 1992 年前来源只返回部分年度 Q4 记录，要求 1953Q1–2026Q2 的全区间采集被连续性门禁拒绝，未写入 QuestDB。PIT、正式表 schema 和旧 Python 对照未验收。


`share_float` 已补 Java 原生来源契约和 Batch source job：按 Tushare `ann_date` 公告可用日探测，保留未来 `float_date` 作为 QuestDB 事件时间，业务键与旧 schema 一致（`ts_code,ann_date,float_date,holder_name`），字段类型按旧 QuestDB model 保留。fixture 覆盖事件样例、合法空公告日、错误公告锚点以及 snapshot runner schema/身份校验。另于 2026-09-29 对 `000998.SZ`、公告日 `2018-12-20` 完成真实 Tushare 45 行采集、隔离 QuestDB typed 写入/全字段回读，`VERIFIED` 且发送一次，见[share_float 实测](evidence/share-float-live.json)。完整历史/全市场、PIT 修订和旧 Python 结果对照未完成。来源文档：[Tushare share_float](https://tushare.pro/document/2?doc_id=160)。


`fina_audit` 已接入单证券、单公告日的 Java Batch 采集。保留旧物理列类型和修订键 `(ts_code,ann_date,end_date)`，对报告期晚于公告日或不匹配证券的返回 fail closed；空公告日需要完整来源探测。真实 Tushare `600000.SH` / `2018-04-28` 返回 1 行，在隔离 QuestDB typed 写入、全字段回读为 `VERIFIED`，重复启动发送一次，见[fina_audit 实测](evidence/fina-audit-live.json)。全市场历史、PIT 修订传播、正式生产表 schema 和 Python 冻结结果对照仍未验收。来源文档：[Tushare fina_audit](https://tushare.pro/document/2?doc_id=80)。

`fut_basic` 已接入原生 Java Batch 来源，按交易所和 `fut_type=1` 获取标准合约快照；保留遗留 1970-01-01 时间戳 sentinel、`(ts_code,timestamp)` 去重键、YEAR 分区及生成的 `update_time`。接口没有分页且上限为 10,000 行，满上限无终止证据时按截断拒绝。真实 Tushare CFFEX 快照（逻辑采集日 2026-09-28）返回 692 行，在隔离 QuestDB 完成全列 typed 写入/可见性与质量核验，状态 `VERIFIED`，发送 1 次；SQLite Batch 台账与恢复使用临时文件。运行器按静态快照语义保留采集日与物理 epoch 时间戳的区别。证据见 [fut_basic 实测](evidence/fut-basic-live.json)。该样本不代表其他交易所、完整合约历史、修订回放或旧 Python 冻结结果对照，JDB-06 仍未完成。来源文档：[Tushare fut_basic](https://tushare.pro/document/2?doc_id=135)。

`etf_basic` 已实现 `fund_basic(market=E)` 原生 Batch 契约和运行注册，映射遗留 ETF 基础资料 27 列，使用 `(ts_code,timestamp)` 去重键、YEAR 分区、1970-01-01 技术时间戳与发布时生成的 `update_time`。官方接口最大 15,000 行且无分页；达到上限而无完整终止证明时拒绝写入。静态快照按 `market='E'` 校验回读范围；逻辑日期仅记录采集日。契约、全字段类型、15,000 行截断、质量规则与 SQLite 管理 API 测试共 57 项通过，见 [etf_basic 实现记录](evidence/etf-basic-implementation.json)。本轮未使用真实来源凭证、未写 QuestDB，故尚未通过真实源与数据库验收、也未核验线上物理 schema。来源文档：[Tushare fund_basic](https://tushare.pro/document/1?doc_id=19)。


`disclosure_date` 已接入原生 Java 契约和 Batch Job，按一个精确季度末 `end_date` 请求，非分页响应最多 6,000 行；物理 `ann_date` 是公告时间戳，旧业务键为 `(ts_code,ann_date,end_date)`，空季度允许完成。校验会拒绝错误季度、日期格式错误和跨季度返回。定向契约测试通过；本轮没有使用真实凭证或写 QuestDB，故真实来源/隔离表回读、历史覆盖、旧 Python 对照和线上物理 schema 核验仍待完成，见[实现记录](evidence/disclosure-date-implementation.json)。来源文档：[Tushare disclosure_date](https://tushare.pro/document/2?doc_id=162)。

`ths_index` 已接入原生 Batch 静态目录快照。按旧 Java/Python 连接器行为使用 Tushare `ths_index()` 无参数全量调用；单次上限 5,000，达到上限时拒绝将响应标成完整。业务列映射为 `ts_code,name,count,exchange,list_date,type`，发布时生成 `update_time`，物理时间/键沿用 `(ts_code,update_time)` 与 MONTH 分区。定向测试覆盖参数为空、字段与类型、重复代码和触顶拒绝、生成时间戳及 Batch 管理注册；未调用真实 Tushare 或 QuestDB，物理 schema 与全量目录完整性仍待真实验收，见[实现记录](evidence/ths-index-implementation.json)。Tushare 来源：[ths_index](https://tushare.pro/document/2?doc_id=259)。旧连接器实现取自只读 back-monitor `HEAD e7aa8729` 的 `ths_index_sync.py` 与 `models/index/ths.py`。

`etf_share` 已接入 `source_etf_share`，按逻辑交易日调用 Tushare `fund_share(trade_date)` 并映射至旧 `timestamp` 键；保留物理模型的 `fund_type`、`market` 可空列，但不会从来源缺失值推造数据。2,000 行非分页上限触顶、空响应、错日和重复键均不能生成可发布的完整来源证据。fixture 验证了请求、字段和键、YEAR DDL、精确 `fd_share` 映射、发布时 `update_time` 及 Batch/API 注册。无真实来源访问、无 QuestDB 写入；checkpoint、完整 ETF universe 数量/来源覆盖和物理表 schema 仍待实测，详见 [实现记录](evidence/etf-share-implementation.json)。
