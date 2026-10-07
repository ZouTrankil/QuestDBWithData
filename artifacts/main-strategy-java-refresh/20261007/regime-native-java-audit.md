# regime_features_monitor_daily 原生 Java 补齐审计

审计日期：2026-10-07。范围：截至 2026-09-30。本审计子代理仅阅读实现、读取本地 QuestDB、保存审计证据；未调用 provider、写库、启动生产 APP、运行 job/tests 或修改 Java。正式原生 materialize 由 root 执行。

## 最终实际验收：通过 ETF 必需输入，融资扩展仍保留缺口

正式原生 Java run：`regime-monitor-5c1ba217-3a43-40cd-82cb-69a83d69b756`。09/21–09/30 已补 **7 个交易日**，最新 **09/30**，全表 **666 行**；此前659行全部保留。计算来源为09/01–30的21交易日股票面板 **116577 行**，另有独立 daily_basic 估值样本 **116588 行**。

独立读取实际 QuestDB、source/publication文件与SQLite只读账本，结构化回执 [regime-native-java-audit.json](regime-native-java-audit.json) 的 `passed=true`。逐值验证不是采样：

|核验|实际结果|
|---|---|
|新 source → formal|7行 × 21列 = **147字段值**；UTC Instant（含微秒）、Double原始IEEE-754位、字符串和NULL逐值一致；差异0|
|历史 outside → 发布前快照|659行 × 21列 = **13839字段值**；差异0|
|保留 backup → 发布前快照|659行 × 21列 = **13839字段值**；差异0|
|全表 canonical SHA256|`c21beb9d2deca48ea85ce24a4a62d6d8ccd0f4912bebfb7b4304f521f4bf50e3`；与outside+source合并、publication.json和journal.afterFingerprint一致|
|source.json文件SHA256|`222829222f212d6ffc78a12b55232eae3b5435c38dc93fddb0564206c1227ba6`；与publication intent的不可变scope一致|
|业务日期与必需输入|全表日期唯一，输出SSE交易日格09/21,22,23,24,28,29,30完整；必需9字段完整、6数值有限、month=202609、flag=proxy_style|
|schema / 物理对象|21列type/order/designated/upsertKey严格匹配；新正式id3017、旧backup id1810；MONTH/WAL/DEDUP=false；backup目录与journal原对象一致|
|WAL / 账本|正式writerTxn=sequencerTxn=9、buffered=0、未suspend；正式与backup前后WAL均稳定；run和publication journal均VERIFIED|
|融资缺口|09/30 `margin_balance_change_mtd=NULL`；sourceAvailability admitted=false、SH2001/SZ0/BJ0，明确保留缺SZ/BJ的事实|
|北向与ERP|09/30 northbound_net_buy_mtd=53851.6635；erp_latest=0.8300156485912749；source分别21/22观测日（gov额外09/20在exact valuation格外，已记录忽略）|

SHA采用正式typed codec的逐行canonical bytes，保留原字段顺序与日期表示。run汇总fingerprint和单slice/sourceFingerprint语义不同，未要求它们互相相等。

只读工具源 [RegimeReadOnlyAcceptance.java](RegimeReadOnlyAcceptance.java)、实际SQL回执 [regime-actual-readonly-queries.json](regime-actual-readonly-queries.json)、发布前快照 [regime-outside-before.json](regime-outside-before.json)；另复用 [regime-monitor-ledger.json](regime-monitor-ledger.json) 的账本/事件/耗时证据（created→VERIFIED约6.04秒）。未运行实现测试。

本表可以为当前 ETF 主策略提供截至09/30的 **必需市场上下文**。该结论不代表09/30全市场融资数据已补全，也不代表另外的ETF持仓时效问题或整个策略的综合验收已通过。

## 首次审计发现（实施前）

- 本表是 ETF 主策略真实输入：`etf_rotation.py` 的 `market_context` → `market.regime.daily_context` → 物理 `regime_features_monitor_daily`。现有主策略日表 validator 未包含本表，不能据其通过宣称所有主策略输入 ready。
- 首次审计时物理表最新 2026-09-18，659 行，21 列；`MONTH / WAL / DEDUP=false / designatedTimestamp=trade_date`，表 id 1810。不是 28 列，也不能凭 Java Row/View projection 当成已实现计算。
- 09 月 21 个交易日的 style/breadth 必需源已有完整日期与 exact join 覆盖，可以原生 Java 计算 ETF 读取的必需字段至 09/30。
- `margin_detail` 09/30 只有上海 2001 行。沿旧代码直接 SUM 会得到错误的月初至今融资变化 **-50.87006127118536%**，必须拒绝当作全市场数值。全 21 字段源完整性仍有融资缺口。
- ETF 使用的 9 字段不含融资、北向、估值；既有 `bounded_regime_completeness` 只校验这 9 字段。若原生 owner 明确允许可空扩展指标，则 09/30 融资置 NULL、记录 raw counts/coverage/SHA 缺口证据，并保留 `proxy_style` 的原模型含义，可以独立认证 ETF 必需字段。不得把 optional NULL 报成全字段完成，也不得借此把 margin 源缺口判为已修复。

## 真实算法和 21 字段

Python 正式 owner：`src/quant_platform/data/derived/regime/features_monitor_daily.py`，由 `data/derived/workflows.py:run_regime_features_monitor_daily` 和 materializer catalog 注册；registry owner/policy 为 `post_close`。注册源依赖 8 表：`stk_factor, daily_basic, stk_limit, stk_suspend, stk_st_daily, margin_detail, moneyflow_hsgt, cn_bond_yield_curve`。

模型 `data/adapters/questdb/models/regime/features_monitor_daily.py` 定义：17 DOUBLE + `trade_date TIMESTAMP`、`month SYMBOL`、`data_quality_flag SYMBOL`、`updated_at TIMESTAMP`。

### 1. 来源窗口与股票面板

- 扩展开始日 = min(请求开始日所在月的 1 日, 请求开始日减 20 个自然日)。请求 09/21 或 09/22 至 09/30 的来源从 09/01 开始；若请求早于 09/21，不能硬编码同一来源起点。
- 股票面板以 `stk_factor` 驱动；`daily_basic/stk_limit` 以 `(ts_code, trade_date)` 左连接，`stk_suspend/stk_st_daily` 以 `(ts_code, timestamp)` 左连接。
- 先按 trade_date、ts_code 排序；无效日期/code 丢弃，数值转换无效为 NaN。稀疏停牌/ST 记录缺省 0，值为 1 的股票排除。
- `daily_ret = close/pre_close - 1`，pre_close 非空且非 0 才计算。上涨/下跌严格比较 close 与 pre_close；平盘不计任一侧。涨跌停判断为有 up/down_limit 且绝对差 ≤ 1e-6。

### 2. Style：6 输出

`hs300_ret_mtd, zz500_ret_mtd, all_a_ret_mtd, cs1000_ret_mtd, small_large_ret_mtd, growth_value_ret_mtd`。

- 每日按 total_mv 三等分为 small/mid/large；按 pb 三等分为 value/neutral/growth。分桶只剔除 daily_ret 或该排序字段缺失；没有额外正值筛选。
- 唯一排序值不足 3 个时该日分桶为空。否则 `rank(method='first')`，相同值按此前 ts_code 顺序破平局，再 `qcut(rank, q=3)`。Java 应复制以 n−1 为跨度的线性分位边界、右闭区间和最低值包含规则，不能随意按整数 n/3 切段。
- 桶内 daily_ret 算术平均，全 A 为所有可交易股票 daily_ret 算术平均。缺失的全 A 或桶收益填 0，仅继承此正式模型规则。
- 每月分别 `(1 + daily_mean_return).cumprod() - 1`。hs300/zz500/cs1000 实际为 large/mid/small 股票分桶代理，不是实际指数行情。
- small_large = small MTD − large MTD；growth_value = growth MTD − value MTD。

### 3. Breadth：6 输出

`avg_up_down_ratio_5d, avg_limit_up_count_5d, avg_limit_down_count_5d, avg_turnover_rate_5d, avg_pct_positive_ratio_5d, avg_pct_negative_ratio_5d`。

- 每日计算上涨、下跌、涨停、跌停数，turnover_rate 均值和可交易股票行数。
- up_down_ratio=up_count/down_count，分母 0 时 NaN；positive/negative_ratio=up/down_count÷tradable_count。
- 每个每日序列按日期排序做 `rolling(5,min_periods=1).mean()`，忽略 NaN；是最近 5 个已存在交易日，不是自然日，也不在月初重置。

### 4. 北向、融资：2 输出

- `northbound_net_buy_mtd`：moneyflow_hsgt.north_money 转数值、空填 0、除 100，按月累计和。保留现有字段/算法的事实；该命名不构成对当前 provider 字段金融语义的额外认证。
- `margin_balance_change_mtd`：每日 margin_detail.SUM(rzye) / 1e8，除该月第一条融资余额再减 1。首值空/0 时 NaN。原 owner 没有市场覆盖 guard，Java 必须保留当前已证的 partial 事实，不将 SH-only SUM 当 full-market。

### 5. 估值：3 输出

`all_a_pe_ttm_percentile_latest, all_a_pb_percentile_latest, erp_latest`。

- daily_basic 不使用停牌/ST过滤；筛选 pe_ttm>0 或 pb>0 的行。分别对各正值非空样本取每日 PE/PB median。
- 对本次 expanded source window 的每日 median 做 `rank(pct=True)`；并列值平均排名、除非空日数。不是全历史分位、不是逐日 expanding 分位。复制旧规则时必须将窗口写入证据，不能宣称 historical point-in-time 分位。
- 从 cn_bond_yield_curve 的 `curve_code='gov' AND tenor='10Y'` 读 yield_value，按同日左连接每日 valuation，再仅在已连接的每日格内 forward fill。收益率独有非交易日不会单独进入 valuation 或输出。
- ERP = 100/PE median − gov10Y，必要值缺失则 NaN。

### 6. 合并与发布

- 5 个分组结果以 `(trade_date,month)` 外连接；完成计算后裁剪请求窗口。
- `data_quality_flag='proxy_style'`，updated_at 为本次计算观察时间；模型缺列补 NULL。
- 正式 Python 使用 `replace_model_window` staging-window swap；正式 Java 应有界覆盖输出窗口，完整保留 outside rows 与 backup。

## 09/30 源依赖实测

只读 SQL 回执见 [逐日源数据](regime-dependency-audit-queries.json) 和 [完整面板连接、交易日历、最后一行](regime-dependency-audit-extra.json)。取数为当次观察，不等价于新 owner 已冻结稳定 source pin。

|来源|09 月 / 09/30 实测|结论|
|---|---|---|
|SSE exchange_calendar|09/01–30 完整 30 自然日格，21 开市日；09/25 休市|输出必需日期为 21 个交易日|
|stk_factor|21 交易日皆有且每日股票键唯一；09/30 5560|style/breadth 驱动完整|
|daily_basic|21 交易日；面板 exact join 每日 missing_basic=0；09/30 总表 5561|驱动股票均有基本指标|
|stk_limit|面板 exact join 每日 missing_limit=0|涨跌停源 join 完整|
|stk_suspend/stk_st_daily|稀疏事件至09/30，09/30面板停牌1、ST197；join未增加面板行数|按原算法缺事件为0，不要求dense全股票日格|
|核心面板数值|全月 close/pre_close非空且pre_close非0；total_mv/turnover非空；pb每日52–54空（09/30为52）|PB缺失仅排除该排序桶样本，不能全行填0|
|moneyflow_hsgt|21交易日每日日键1行，north_money非空|原北向月累计算法可计算|
|cn_bond_yield_curve gov10Y|21交易日各1行且yield非空，09/30=1.6822；另有09/20非交易日1条|交易日ERP源完整；非交易日额外值不直接输出|
|margin_detail|21日期有行，但09/30仅SH2001；09/29=4454(SH/SZ/BJ)|09/30全市场融资未完成，须NULL或fail-closed明确降级|
|regime输出|09月份仅09/01–18，14行；全表最新09/18|缺09/21,22,23,24,28,29,30共7个交易日|

原 guard 的7次provider查询和实际拒绝已在 [ETF/margin最终审计](etf-margin-audit.md) 中保存；本次未重新调用 provider。

## Java 原可复用路径与此次真实实现

初读时全 src/main/java 搜索只有 `domain/table/RegimeFeaturesMonitorDailyRow.java`、对应 View/Monthly projection；没有本表计算源、writer 或 job owner。Root 正在协调原生实现，因此此结论是审计发现时的状态，不否定随后落盘的工作。

可复用真实组件：

1. `DatasetImplementation` / `SyncJobOwner` / `DatasetRegistry` / `SyncJobRegistry`：正式注册真实 owner；DatasetRegistry 仅接受已注册 dependencies，不能把尚无正式 Java 实现的 cn_bond 表名假称为 owner。
2. `SyncJobRunner`、`VerifiedBatchExecutor`、`DatasetIntervalLock`：有界 MATERIALIZE 请求、日期唯一键、批次 readback、WAL 与账本证据。
3. `ReferencePublicationJournal`、`StaticTargetIdentity`、`QuestDbWriteChecks`：已有 durable stage swap 状态、表 identity lineage 与 settled WAL 核验。
4. 最近的 `MarketSentimentDailyJobService` / `MarketSentimentDailyWritePort` 已实现原生 Java 本地依赖查询、bounded subqueries、source SHA、保留 outside、完整 stage SHA、保留 backup、publication proof。它们的计算和 writer 都严格绑定 53 sentiment 字段/表名，**不能直接用该 writer 写 regime，不能假称它是可直接接参数的通用 materializer**。可在共享 typed staging owner 下抽取通用发布部分，再接本表21字段实现；也可复用 runner/journal工具而保留本表真正的 typed owner。
5. `EquityStyleMonthlyMaterializeAdapter` 是原生月度指数源，不能代替本表股票分桶/rolling每日算法；`EtfMarketOverviewDailyCacheMaterializeAdapter` 部分委托 Python owner，不满足当前原生 Java 目标。

此次已经落盘 `RegimeFeaturesMonitorDailyCalculation` / `RegimeFeaturesMonitorDailyJobService`，复用generated Row、SyncJobRunner、Shared `NativeDailyWindowWritePort` / `NativeDailyWindowPublication`，正式完成上述原生计算与窗口发布。cn_bond_yield_curve被明确记录为必需的、source-pinned的本地只读外部表，没有伪造Java provider owner。当前JobDefinition v2只开放已实现的MATERIALIZE；此前实际run的frozen v1历史原样保留。

独立静态审核确认qcut right-closed边界/tie、缺桶0与cumprod、rolling跨月、全暖窗average rank/N、gov exact merge后ffill和实际9表SQL/日期/键约束符合源规格。发现的重复CREATE_NEW publication回执问题已在正式run前修复；RECONCILE执行写路径问题通过收紧新JobDefinition v2的真实支持模式修复，没有提供假的只读模式。

ETF必需9字段：trade_date, month, hs300_ret_mtd, zz500_ret_mtd, all_a_ret_mtd, small_large_ret_mtd, growth_value_ret_mtd, avg_up_down_ratio_5d, data_quality_flag。既有 bounded_regime_completeness 接受 `ok`/`proxy_style`，要求交易日格完整唯一及6数值有限；updated_at另有可用时间约定，不得虚填过去时间。
