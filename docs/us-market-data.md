# 美股与跨资产基础行情

目标是保存可复核的基础数据：美股约 20 年日线、核心标的当年分钟线、六只参照 ETF、BTC/ETH 现货和合约日线及小时线，以及一段 Nasdaq 原始 ITCH。数据不足时按实际上市或来源日期记录，不补造历史。

## 当前入口和表

独立入口在 `markets/us/data-app`，不会启动 A 股 API、Batch 或主策略。业务字段定义在 `UsMarketDatasets`，目录说明由 US 的 `DataCatalogProvider` 注册。

| 表 | 完整业务键 | 时间含义 | 内容 |
| --- | --- | --- | --- |
| `us_equity_daily` | `trade_date,symbol,provider` | 纽约交易日编码为 UTC 零点 | 未复权及调整 OHLCV、现金分红、当日拆股因子、币种、来源摘要和取得时间 |
| `us_equity_intraday` | `ts,symbol,provider,interval_minutes` | `ts` 是真实 UTC 起点；`trade_date` 是纽约日期 | 1 分钟行情，以及按实有分钟合并的 5 分钟行情 |
| `us_equity_intraday_regular` | 与分钟原表相同 | 原时间和原价不变，另给出 `calendar_version` | 正常交易时段只读视图；按已核对的 2026 年日历排除休市、盘外及时间不匹配记录 |
| `us_security_universe` | `observed_at,symbol,directory` | 取得快照的真实 UTC 时间 | 官方挂牌原文范围、明确的候选与排除原因；不是历史成分表 |
| `us_security_history_catalog` | `observed_at,source_sha256,source_row` | 来源窗口日期用 UTC 零点；取得时间用真实 UTC | Tiingo 美国 Stock/ETF、OTC 目录；原始行号保留同代码的不同历史窗口 |
| `us_etf_equity_holdings` | `asof_date,fund,symbol` | 发行人持仓日期，UTC 零点编码 | SPY/QQQ 发行人的股票及 ADR 持仓，标记 `ETF_HOLDINGS_PROXY` |
| `us_nasdaq_itch_messages` | `ts,source_sha256,message_index` | 真实 UTC 纳秒 | 部分文件中完整消息、源字节偏移和原始十六进制 payload |
| `us_market_daily` | `ts_code,trade_date` | FRED 观测日期用 UTC 零点 | NASDAQCOM、SP500、VIXCLS 收盘点位；兼容既有六列，只补缺失键 |
| `crypto_ohlcv` | `ts,exchange,symbol,instrument,interval_minutes` | UTC 小时或日线起点 | Binance、Bybit 的 BTC/ETH 现货、USDT 永续；通常由齐备小时线合成日线，官方停市月份另核对原生日线 |
| `crypto_data_gap_events` | `ts,exchange,symbol,instrument,interval_minutes` | 缺失小时及官方停市区间，真实 UTC | 归档、REST 空记录、官方事实和原生日线核对证据；不生成价格 K 线 |

正式表使用 WAL、明确分区和 DEDUP；普通视图依赖原表，不单独写入。来源隔离，不能用不同供应商的数据互相静默覆盖。调整价采用当前来源调整基准，不表示历史当时已经可用的价格。

Tiingo 日线同时保留 `close` 与 `adj_close`，不能只存复权价。`dividend` 是来源除息日每股现金额，`split_factor` 无事件时为 1。IEX 分钟量仅代表该场所，不能据此计算全美股的成交份额。

供应商现金和拆股调整价不一定包含非现金派股的股票价值。COV 分拆 Mallinckrodt、SWY 派发 Blackhawk 是已核对的例子；不能补造现金或数量拆股，也不能单凭这两段调整价计算完整股东总回报，见[历史验收说明](decisions/2026-10-22-Covidien与Safeway及WholeFoods历史验收.md)。

需要固定到某个已验收窗口末日的价格基准时，可将供应商调整 OHLC 乘同一证券、供应商、来源摘要和末日记录的 `close / adj_close`。这项查询只统一价格基准，原始值和供应商调整量继续保留各自口径；证券身份、公司行动及历史当时的可用性仍须分别核对。下文 ULTI 留有实库逐行核对过的例子。

## 配置和命令

公开配置在 `config/apps/us-data.yml`。凭据在被忽略的 `config/application-local.yml`，使用 `app.market-data.tiingo-token`、`app.market-data.fred-key`。不将 key 放进 URL 日志、运行参数、Git 或发行包。

FRED 需要本机代理时，配置 `app.market-data.fred-proxy-url`；留空时沿用行情公共代理。Java 不自动读取 Windows 浏览器代理，所以浏览器或 Python 能访问不代表 Java 直连可用。

```powershell
# 查看字段、时间及键定义。
.\gradlew.bat usMarketData --args='definitions'

# 查看实库覆盖和 WAL。
.\gradlew.bat usMarketData --args='status'

# 核心 16 只证券：约 20 年日线、今年按月切分的 1/5 分钟。
.\gradlew.bat usMarketData --args='collect-core --through 2026-10-16'

# 当前挂牌及供应商历史股票候选；优先发行人持仓范围，先等核心日线结束。
.\gradlew.bat usMarketData --args='collect-broad --through 2026-10-16'

# 用已保留且与版本化日历相符的官方原文创建正常时段视图。
.\gradlew.bat usMarketData --args='create-regular-view --calendar-source var/market-data/probes/nasdaq-calendar-2026.response --hours-source var/market-data/probes/nyse-hours.response'

# FRED 三个指数序列；保留所有旧行及旧值，只写已确认缺失的键。
.\gradlew.bat usMarketData --args='collect-fred --through 2026-10-16'

# BTC 独立入口，2021-10-01 起，最后一天必须是已结束的 UTC 日。
.\gradlew.bat cryptoMarketData --args='collect --through 2026-10-18'

# 导入已取得并冻结的真实来源响应。
.\gradlew.bat usMarketData --args='import-tiingo-daily --source var/market-data/probes/tiingo-spy-daily.response --symbol SPY --through 2026-10-16'
.\gradlew.bat usMarketData --args='import-tiingo-minute --source var/market-data/probes/tiingo-spy-minute.response --symbol SPY --from 2026-01-02 --through 2026-01-02'
```

有限写入先检查来源摘要、重复业务键和目标结构，再冻结规范化文件，复用 `SyncJobRunner` 登记意图及区间锁。完整键和值回读、WAL 结清后才写成功终态；未决发送保留原账本和锁。原始响应、规范化输入和运行回执保存在 `var/market-data/`。导入冻结响应成功只证明该响应中的键和值，不等于全市场来源已经齐备。

QuestDB 10 的正式发送使用 QWP，保留 INT 和纳秒时间类型。发送后使用客户端 `drain` 等待全部已发布批次确认，覆盖恰好触发自动刷新的边界，再核对实库字段和 WAL。运行记录原进程 PID 与启动时间。`reconcile-undelivered` 只接受已停止的原发送进程、相同冻结摘要、仍为空且 WAL 为零的原物理表、单一未决切片和原锁；其他未决情况必须继续核对，不能用此入口重发。

`reconcile-frozen` 是明确的首切片恢复操作，要求原进程已停止、冻结摘要及原物理表不变、原未决锁仍存在。它逐键检查现有值；显式 `--repair-missing true` 才补已证实缺失的键，遇到不同值或重复键立即停止。完整来源、补缺意图、回读文件和摘要均保留；全部核对通过才完成原运行和释放原锁。`reconcile-core-task` / `reconcile-broad-task` 另行核验原队列归属和全部频率的字段，之后才推进队列。

全部冻结页已经发送、末页回读超时时，可用 `reconcile-complete-frozen --run <原运行ID> --prepared-sha256 <原摘要> --stopped-evidence <停止证据文件>` 只读核对。停止证据包含 `runId`、`writerStopped=true`、五分钟内的 `inspectedAt` 和非空 `evidence`；程序还会检查原 PID 与启动时间。入口按原页重算提交事件、字节边界和发送摘要，逐字段核对全部来源，要求 WAL、物理表、冻结文件和账本版本稳定。缺页、缺键、错值、重复键或取消请求都会拒绝推进，不提供补写参数；已验收页保留原修订和证明。原运行成功后，仍须用原队列核对命令推进检查点，详见[设计决定](decisions/2026-10-20-原冻结分页只读核对.md)。

同一行情目录的发布通过 `ledger-writer.lock` 串行执行，避免两个采集进程同时升级 SQLite 读事务。来源请求和配额等待在该锁之外进行。Bybit 的 HTTP 200 还必须检查 `retCode`；服务超时、服务错误和限流在任何正式发布之前有界重试，最多三次，权限错误保留为来源缺口。

若完整连续前缀已发送、尾部从未登记，可显式执行 `reconcile-frozen-tail --run <原运行ID> --prepared-sha256 <原摘要> --stopped-evidence <停止证据文件> --append-unsubmitted true`。它沿用上述停止、原文件、预算、目标和取消检查，要求已发送前缀逐字段齐备、全部尾部键仍不存在。只在原尝试下登记和发送原尾页，已验收页及失败回执保持；全量回读后才完成原运行并释放锁。前缀缺键、尾部已有数据或未完成的新分页均拒绝自动重发。原只读入口不放宽，具体限制见[设计决定](decisions/2026-10-21-原冻结未发送尾部恢复.md)。

全市场股票默认采用年度来源组：`broad-annual-enabled=true`，`broad-annual-symbols=128`，来源数不能超过预取缓存。完整原文和清单一起冻结，压缩及解压内容均不超过 64 MiB，单响应不超过 4 MiB；达到字节边界时按实际来源数成组。完整响应先验证所有字段并重算逐年行数，再按 QuestDB 年分区逐段发布，每段仍不超过正式十万行上限。原始 OHLCV、后续拆股、分红、来源摘要及取得时间不变；不会一次构建全部证券二十年的行对象。

关闭年度模式或关闭预取时，仍使用原版本 1 来源组，默认每组最多 12 个来源，`broad-publication-symbols` 可设为 1–16。两种 ZIP 都保留完整队列 ID、映射、URL 和摘要，每行的 `source_sha256` 指向对应原始响应，运行指纹使用整个 ZIP 摘要。

全市场日线的逻辑发送批次默认最多 32768 行，规范化字节预算为 32 MiB，分别由 `broad-publication-page-rows`、`broad-publication-page-bytes` 配置。新大页使用版本 4 写入请求，冻结 `publication_transactional=true` 和 `publication_visibility_seconds=600`，prepared 同时保存相同策略。QWP 自动分帧后在整批显式刷新时统一提交，再等待全部确认；单次 HTTP 回读仍最多请求 5000 个完整键，逐字段验证覆盖整批。新 v4 每批等待上限十分钟，单运行上限一小时、十万行；运行剩余时限仍再次约束等待。原年度 ZIP 清单仍为版本 2，来源配额不变。旧 v1/v2/v3 保留原分页、普通分帧和五分钟核验策略，v2 仍限制为 10000 行、8 MiB；其他入口默认仍为 5000 行、4 MiB。未决状态不自动重发，运行及恢复证据见[大页事务发送决定](decisions/2026-10-21-大页事务发送与冻结等待.md)。

每段回读先检查原物理目标和 WAL。WAL 追赶、挂起或缓冲未清时只等待，不反复扫描价格；就绪后仍核对完整业务键和全部字段，值匹配后再核对物理目标及 WAL。超时后的未决恢复规则继续适用，实库检查见[回读等待记录](decisions/2026-10-21-WAL就绪后回读行情.md)。

旧 v1/v2/v3 发布和明确的未发送尾部续接继续使用五分钟有界核验，v4 尾部按原请求恢复十分钟及事务模式。完整值、业务键、物理对象、版本、WAL 和取消门槛不变；到期仍保留未决状态，不重发。原冻结行数和字节分页保持，单次 HTTP 查询超时也不改变。此前五分钟策略的实库验收见[原 2021 年尾部恢复记录](decisions/2026-10-21-大分区核验等待与原2021尾部恢复.md)。

来源组及全部成员在同一 SQLite 事务中进入 `PUBLISHING`，全部字段回读及 WAL 通过后再一起进入 `VERIFIED`。未决组阻止重新发送。原正式运行已为 `VERIFIED`、但队列尚未推进时，可用 `reconcile-broad-bundle --bundle <原组ID> --run <原运行ID>` 重新解析冻结 ZIP 并逐字段核对后推进；该命令不会重发或解开未决写入锁。旧单证券任务仍使用 `reconcile-broad-task`。

年度清单使用版本 2，同时冻结连续窗口、每年行数及发送页。`broad_bundle_windows` 先保存全部 `PLANNED`，每段发送前提交 `PUBLISHING` 意图；成功段保存原运行，来源确无记录的空年份只保存零行证据，不生成价格。所有段再沿原成功运行逐字段回读、核对原物理表及 WAL，全部通过才原子推进来源组和证券成员。

年度模式可在段间响应停止请求，状态为 `STOPPED_BETWEEN_WINDOWS`。重启时先按原冻结输入重验成功段，再仅继续未发送的 `PLANNED`；不使用当前配置重算窗口或发送页。任何原 `PUBLISHING` 段都会阻止自动继续。先处理原正式运行，再用 `reconcile-broad-window --bundle <原组ID> --window <原窗口序号> --run <原运行ID>` 核对完整原段，之后重新执行同一截止日的 `collect-broad`。这个命令只回读，不重发；不能拿另一年或部分行的成功运行推进检查点。版本 1 来源组仍沿原核对命令处理。

IEX 实测在 2026-07-03 等休市日返回整日零量平价记录；这些是供应商补齐值，不能计作真实交易时段。原文及原表全部保留，研究读取 `us_equity_intraday_regular`。日历资源在 `markets/us/src/main/resources/market-us/calendars/us-equities-2026.json`，保留官方 URL、原文摘要、休市及半日市日期。创建视图时再核对完整官方原文与资源事实；未知同名视图不覆盖。视图按纽约夏令时筛选实际开闭市时刻，只投影原值，不补造缺失分钟。当前版本只覆盖 2026 年，不能拿它推算下一年。

FRED 沿用正式 `us_market_daily` 的结构。请求先固定完整观测数量，`.` 作为来源缺值统计。补缺前保存旧行快照，实际发送前再次拒绝任何已存在的业务键；补缺后核对全部旧行未变、新行完整可见及 WAL。已有未决运行时停止，不重新抓取后覆盖原任务。SP500 的来源窗口较短时，旧表中更早的日期仍保留。

2026-10-20 实际补入 NASDAQCOM 2676 条、SP500 21 条、VIXCLS 2678 条，原有 7691 条记录全部逐字段保持不变；同一截止日重跑三组均为零新增，WAL 已结清。NASDAQCOM 来源最后有效值为 2026-10-15，16 日缺值未填造；另两组有效至 16 日。FRED 默认 HTTP/2 和应用 UA 在本机实测出现重置或超时，入口只对该来源使用已验证的 HTTP/1.1 兼容请求头。

2026-10-21 再补入四行，三个指数均至 10 月 19 日；其中 NASDAQCOM 来源本次已提供此前缺失的 16 日值。旧 13066 行六列保持，原文、prepared、HTTP 和 PGWire 独立一致，WAL 结清。同截止日实际重跑零新增、Native 与 WAL 事务不变；完整来源缺值和 SP500 当前窗口之外的旧历史均保留。见 [FRED 指数增量与历史保护](decisions/2026-10-21-FRED指数增量与历史保护.md)。

同日稍后按纽约已结束日期请求至 10 月 20 日，NASDAQCOM、SP500 各补一行；VIXCLS 原响应仍只提供到 19 日，缺口保留。原 13070 行六列保持，现有 13072 行，来源原文及观察时间、prepared、HTTP、PGWire 一致。见 [DIRECTV 历史与十月二十日指数增量](decisions/2026-10-21-DIRECTV历史与十月二十日指数.md)。

请求预算放在 `api-budget.sqlite3`，先预留再请求；失败也计请求，未取得完整响应时保留带宽预留。来源队列的 `FETCHED` 可以继续使用同一冻结文件；`PUBLISHING` 不自动重发。SQLite 和不可变回执决定验收，状态 JSON 只用于观察。Windows 状态页被读者短暂占用时有界等待，不改变已验收结果。

BTC 的原始文件、队列、预算、账本及回执在 `var/crypto-market-data/`。对运行中的采集器，分别写入 `var/market-data/us-stop.request`、`us-broad-stop.request` 或 `var/crypto-market-data/stop.request`，会在当前完整任务验收后停止。再次执行命令继续未完成任务；未决发布仍阻止继续。

BTC 扩展 `collect --through` 时，保留原任务的日期、ID、原文及状态，只规划每个交易所、币对和产品尚未登记的日期。跨月也不把已有日归档换成重叠月归档；内部未登记日期会补进规划。已登记的失败或待核对范围不会另开任务绕过，具体见[增量约定](decisions/2026-10-21-加密行情增量规划与单日验收.md)。

加密来源可配置 `app.crypto-data.proxy-url` 或环境变量 `APP_CRYPTO_DATA_PROXY_URL`，留空时直连。Java 不自动沿用 Windows 系统代理；本轮直连握手失败后，以同一公开 URL 验证现有本机代理可用，再仅为本轮进程设置该覆盖。来源预算、证书校验和正式表连接规则保持。

## 已核实的来源边界

- Tiingo key 已通过真实日线和历史 IEX 请求，SPY、AAPL 的 2006 年以来日线可读取。
- IEX 大范围请求实测会只返回最后 10000 根，必须按月切分，达到边界则拒绝验收。不能把 HTTP 200 或返回 10000 行当成全年完整。
- 用户已确认当前账户为 Starter 免费套餐。[Tiingo 公开 Starter 配额](https://www.tiingo.com/about/pricing)是 50 次/小时、1000 次/日、500 标的/月和 1 GB/月，现有共享预算按这些上限执行。Tiingo 优先采集核心数据和补验失败项，全市场日线主要使用 Yahoo；本地账本不代表账户在其他应用中的剩余配额，来源响应也未提供可核实该余额的头。
- [Tiingo IEX 说明](https://www.tiingo.com/documentation/iex)明确分钟成交量为 IEX 场所量，且必须显式请求 `volume`。
- Yahoo 报价是拆股调整基准。通过响应中该交易日之后的全部拆股还原 OHLC、股数及现金分红，使用 `RECONSTRUCTED_FROM_YAHOO_SPLIT_ADJUSTED` 标明来源；`adj_close` 保留 Yahoo 当前调整值。AAPL 2006-01-03 的 Yahoo 收盘价乘 28 后约为 74.75，接近 Tiingo 原始 74.75；不同来源的盘中 OHLC 仍有差异，不能混合覆盖。缺失数值拒收，不补零。
- 本机 2026-10-20 核验 FISV 时，Yahoo 两个主机均缺少 2025-11-12 的 OHLCV。已用独立 Tiingo 来源补入 2006-01-03 至 2026-10-16 共 5230 条日线并验收；保留 Yahoo 原文和 `FAILED_SOURCE`，来源之间不拼值或覆盖。
- AMTD 的 Yahoo 原文只有截止日之后的记录。按[官方清单字段定义](https://www.nasdaqtrader.com/trader.aspx?id=symboldirdefs)核对挂牌代码 `N` 为 NYSE，并与 Tiingo 的 AMTD IDEA Group 名称及 2019 年起始窗口匹配后，独立补入 2019-08-05 至 2026-10-16 共 1811 条日线，字段、实有日期及 WAL 验收通过。原 Yahoo 拒收保留，当前公司价格不替代旧 NASDAQ 发行人。
- 旧 TD Ameritrade 经 Tiingo 搜索确定为永久代码 `US000000000721`，与当前 IDEA 的 `US000000080416` 不同。永久代码元数据请求返回 404，但价格请求实际成功；已按真实请求代码 `US000000000721` 验收 2006-01-03 至 2020-10-05 的 3715 条日线，完整 20 列与原文一致，当前 IDEA 的 1811 条逐字段未变。[Nasdaq 公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2020-175)确认最后交易日为 10 月 5 日；探针原文中 6–7 日的零量平价不计作交易历史。两家公司的独立映射共 2 条已写入 `us_security_identifiers`，18 列回读一致，重复导入后仍为 2 条；原目录行及 1997 年起的来源声明保留，2006 年之前不在本次价格请求范围。
- 2026-10-20 按当前官方名称、场所及 Tiingo 身份原文核对后，独立补入 ATLQ（JAB Acquisition Corp I）2026-08-05 起的 52 条、ATTT（Atlas Trinity Tech）2024-05-15 起的 608 条日线，均截至 2026-10-16。两组实有日期与已验收 SPY/Tiingo 参照一致；短于五年的范围保留实际来源起点，不填造上市前价格。
- ATLX 的 Tiingo 实有日线为 2013-02-28 至 2026-10-16 共 3428 条。2018-04-03、2018-05-03 缺记录，精确日期请求也返回空数组；这只能证明供应商缺口，不能推断停市或全市场无成交。最近五年（2021-10-16 起）实有 1255 个参照交易日，逐日一致；早年缺口保留，整段历史不判为满网格。上述三组共 4088 条的全部 20 列、原运行及 WAL 验收通过，独立回读一致；发布 ATLX 前已验收的另两组共 660 条未变，Yahoo 原拒收不改写。
- 同日继续补入 BRTX 3378 条、BTLN 1330 条、BTOC 609 条、CAPS 3818 条、CBIO 337 条、CECO 5230 条 Tiingo 日线，共 14702 条，均截至 2026-10-16。来源分别自 2013-05-14、2021-07-01、2024-05-14、2011-07-21、2025-06-16、2006-01-03 起。除 CAPS 的 15 个早年缺日外，各请求窗口与 SPY/Tiingo 参照逐日一致；CAPS 最近五年的 1255 个参照交易日齐全，早年缺口仍保留。六组全部 20 列与原文独立核对一致，原运行及 WAL 通过；此前三组的 4088 条也再次核对未变。
- CBIO 的 Tiingo 名称为空，不能直接沿用供应商起点 2025-06-13。[公司报告](https://www.sec.gov/Archives/edgar/data/1253689/000162828025036846/pressrelease-q22025.htm)确认 Crescent 于 2025-06-16 开始以 CBIO 交易，本次只接受该日起的当前证券窗口；此前目录窗口未判为覆盖。BTOC 的短来源范围与[公司披露的首次交易日](https://www.sec.gov/Archives/edgar/data/1972529/000121390024043682/ea020620101ex99-2_armlogi.htm) 2024-05-14 一致。
- CECO 当前官方目录场所为 `F`，Tiingo 仍为 NASDAQ。[Nasdaq 通知](https://www.nasdaqtrader.com/TraderNews.aspx?id=DTN2026-10)说明 `F` 指 TXSE；[公司公告](https://www.sec.gov/Archives/edgar/data/3197/000119312526413794/ceco-ex99_1.htm)说明转板自 2026-10-19 起生效、CUSIP 不变。截止 16 日的 Nasdaq 历史与当前目录分开核对，不能因当前场所变化丢弃原历史，也不能把这段历史标成 TXSE 成交。
- BURU 的部分近期缺价发生在 OTC 临时代码 `BURUD` 期间。[公司公告](https://ir.nuburu.net/news-and-presentations/news-details/2026/NUBURU-Announces-Reinstatement-of-Trading-on-NYSE-American/default.aspx)确认 2026-09-14 恢复 NYSE American 交易及反向拆股后的 CUSIP。已分别验收原 Yahoo 中 2021-10-18 至 2026-08-27 的 1220 条有效日线、Tiingo/BURU 自 2024-06-26 起的 571 条、Tiingo/BURUD 在 2026-09-02 至 11 日的 7 条，共 1798 条来源记录，全部 20 列、原运行及 WAL 均已核对。原代码、供应商、价格及摘要分别保存；只有明确的 7 个临时代码交易日用于联合日期核对，最近五年合计覆盖 1255 个参照日，不代表任一单来源独自完整。2023-02-01 前属于[前身 Tailwind 阶段](https://ir.nuburu.net/news-and-presentations/news-details/2023/NUBURU-Inc.-and-Tailwind-Acquisition-Corp.-Announce-Closing-of-Business-Combination/default.aspx)，不能解释为 Nuburu 相同业务的经营历史；原 Yahoo 全段拒收仍保留。
- 同日再验收六组来源共 17703 条：DDS、DHY、当前 DCM 发行人的 Tiingo 日线各 5230 条，均覆盖 2006-01-03 至 2026-10-16；CNL/Tiingo 为 2026-08-11 起的 48 条，CNL/Yahoo 为最近五年的 1255 条；DEC/Tiingo 为 2023-12-18 起的 710 条。六组请求窗口都与已验收 SPY 参照逐日一致，全部 20 列与原文独立核对，原运行和 WAL 通过；此前 31344 条记录及原物理表未变。CNL 两个来源有日期重叠，不能把来源记录数当作不同交易日数。
- DCM 的普通 Tiingo 元数据仍指向旧 NTT DoCoMo。[当前公司公告](https://www.sec.gov/Archives/edgar/data/102109/000110465926114373/tm2627199d1_ex99-1.htm)确认 Universal Safety Products 从 2026-10-19 更名换代码，CUSIP 不变；截至 16 日实际为 UUU 阶段。本次按搜索确认的永久代码 `US000000002893` 取得并保存当前法律主体的历史，不占用普通 `DCM` 价格键，也不把旧 NTT 历史或旧业务解释为当前数字资产业务。
- [TXSE 公告](https://www.txse.com/alerts/adbdaf8a-27af-4643-9e6a-c0655cf92561)列明 DDS 普通股从 2026-10-05 转板，供应商仍保留 NYSE 名称；同份公告将 DDT 列为 `Trust Preferred`。DDT 原未发布任务已转为 `SCOPE_REVIEW_REQUIRED`，保留原响应、拒收原因和历史 Stock 标签，未写入普通股行情。
- DHY 是封闭式基金普通份额，不能当作经营公司普通股；供应商搜索和历史目录的 `ETF` 原标签仍保留。[基金公告](https://www.prnewswire.com/news-releases/credit-suisse-high-yield-credit-fund-announces-board-approval-of-a-reverse-share-split-302854757.html)确认 2026-09-04 更名为 UBS Asset Management High Yield Credit Fund、9 月 30 日进行 10 股合为 1 股；正式记录保留该日 `split_factor=0.1` 及原复权字段。DDS、DHY、DCM 三条永久映射也已通过原搜索、目录行和成功价格运行核验，18 列独立回读一致，原有两条映射未变。
- CNL 的[转板公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=DTN2026-16)确认代码和 CUSIP 延续。原 Yahoo 全段在 2021-10-08 有非正价格，本次仅独立接受 2021-10-16 起、实际首日为 18 日的五年窗口，原全段拒收保留。旧永久代码虽取得 810 条，但少了 463 个参照日期，当前搜索还有多个同 FIGI 的永久代码；这份原文保留待核实，未发布或强选映射。
- DEC 的[公司公告](https://ir.div.energy/news-events/us-press-releases/detail/161/diversified-energy-begins-trading-on-nyse)确认 NYSE 自 2023-12-18 开始交易；710 条只证明这段实有挂牌期。当前供应商名称和搜索 FIGI 缺失，尚未建立永久映射。旧 OTC 永久代码取得的 1288 条截至 2023-12-04，与请求末日之间少 9 个参照日期；原文保留待核实，不宣称整个五年网格齐备。
- FRGT 补入 Tiingo 2017-08-08 至 2026-10-16 的 2311 条日线，全部 20 列与原文独立核对，原运行和 WAL 通过；此前 49047 条未变。其[公司年报](https://www.sec.gov/Archives/edgar/data/1687542/000149315226023206/form20-f.htm)说明初始代码为 CIFS，之后为 Hudson，2022-02-14 才完成货运业务合并；不能把整段都解释为同样的货运业务。供应商实际请求代码仍为 FRGT，永久代码 `US000000056595` 映射已独立验收，原 Yahoo 拒收保留。
- ATLQ 的[分开交易公告](https://www.sec.gov/Archives/edgar/data/2128739/000121390026085025/ea0300385-8k_jabacq1.htm)确认普通股自 2026-08-05 才能独立交易，[更名公告](https://www.accessnewswire.com/newsroom/en/exchange-traded-funds/jab-acquisition-corp.-i-announces-ticker-symbol-change-1212869)确认 8 月 31 日从 JAB 换为 ATLQ、CUSIP 不变。52 条来源不拿此前单位证券补齐。ATTT 前身 Raytech [首次交易日为 2024-05-15](https://www.sec.gov/Archives/edgar/data/1948443/000121390024059478/ea0208984-6k_raytech.htm)，[2026-09-10 从 RAY 换为 ATTT](https://www.sec.gov/Archives/edgar/data/1948443/000121390026098242/ea030480301ex99-2.htm)，CUSIP 不变；608 条包含此前 RAY 阶段。两条永久映射已导入，ATTT 的来源 FIGI 明确保留 null；18 列独立回读一致，旧六条映射和两组 660 条价格未变，映射表累计 8 条。
- 对原 18 个 Yahoo 失败代码的只读复核显示，均有 Native 成功的替代来源价格；13 个代码的最近五年参照日期齐全。ATLQ、ATTT、BTOC、CBIO、DEC 的短范围缺日都在本次实际来源起点以前，这不等于全市场或整个挂牌寿命已完成；ATLX、CAPS 的早年来源缺口及历史身份复核仍保留。
- [Nasdaq 公共 ITCH 目录](https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/)存在真实二进制文件；部分文件达数 GB 至十余 GB。部分采集需明确文件、日期、压缩字节或消息边界，不能宣称完整交易日。
- [Binance 公共归档](https://github.com/binance/binance-public-data)提供现货及合约数据和校验和；2025 年以来现货归档时间单位为微秒，不能沿用毫秒解释。
- Binance 2023-03-24 现货归档及 REST 均缺少 13:00Z 小时。只有取得[官方停市事实](https://www.binance.com/en/blog/from-our-ceo/6789340645608890113)、原归档及原生日线的官方摘要、精确小时 REST 空记录，并核对全部日线 OHLCV 与实际小时成交一致后，才登记独立缺口。实际 23 根小时记录保留，不能填造第 24 根。

## 全目标验收清单

下面各项都需真实来源和实库证据，不能用本文、字段定义或小样本代替：

- [ ] 全美股证券范围及标普 500、纳指 100 覆盖；保留范围快照、上市和退市边界。
- [ ] 日线约 20 年，来源较短的至少检查约 5 年或真实上市日期；未复权、调整价、分红和拆股逐项齐备。
- [x] SPY、QQQ、IWM、TLT、GLD、USO 历史全部落库并逐字段核验至 2026-10-20；USO 自 2006-04-10，其余自 2006-01-03。10 月 19、20 日单日原文和增量另留证明，旧值保持。
- [x] 16 只核心 ETF 和股票的当年 1 分钟及 5 分钟数据已核验至 2026-10-20，覆盖 201 个交易日；正常时段共 1254240 根 1 分钟、250848 根 5 分钟，逐日完整且独立聚合通过。原 176 个来源任务与两日独立补录分别保留原文、成功账本及 WAL 证明。
- [x] Nasdaq `S061226-v50.txt.gz` 前 8 MiB 的 SHA-256 与范围元数据已保留；原消息序号 309047–359046 共 50000 条已逐字段回读。真实时间 2026-06-12 08:00:00.000425736Z–08:00:05.055189073Z；这段约 5 秒的盘前数据不代表全日订单流。
- [x] Binance、Bybit 的 BTC/ETH 现货及 USDT 永续：2021-10-01 至 2026-10-20 的小时、日线已验收。572 个来源任务、369198 根 K 线；十六条 UTC 序列均覆盖已验收的实际交易时段，WAL 结清且无未决锁。两条官方停市小时缺口保留。10 月 19 日有一项日成交额独立求和差一 ULP，原值与差异见[原增量验收](decisions/2026-10-21-加密行情增量规划与单日验收.md)；10 月 20 日归档缺失时显式接回原 Binance 任务的 REST 双频原件，见[核心与加密新日验收](decisions/2026-10-21-核心十月二十日增量与币安REST恢复.md)。
- [x] 来源请求频率、配额及重试预算持久化；429 不绕过限流。用户已确认 Tiingo 为 Starter 免费套餐，继续按 Starter 上限运行。
- [ ] 各产物结构、完整业务键和值、WAL、恢复和最终覆盖报告通过。

这份清单仍在推进，不能因为两个新表已建立就判定目标完成。

全市场入口在持有原队列锁并完成规划后，启动一个后台来源预取线程。下载和前一组正式验收可以同时进行；仅预取 `PLANNED` 日线，按原请求频率、截止日和 SHA-256 校验保存为 `FETCHED`，不接触已发布、未决或范围待复核任务。正式写入仍由原线程串行执行，只有全字段回读与 WAL 验收后才推进组和成员。

`app.market-data.broad-prefetch-enabled` 默认开启；`broad-prefetch-buffer-symbols` 默认 256，允许 16 至 500 个磁盘响应。缓存满后暂停下载，消费侧等待完整来源组，来源全部取得后保留不足一组的尾部。预取异常会阻止启动后续正式发布，停止请求会等待当前正式窗口验收后退出并关闭预取线程。进程重启仍先检查原 `PUBLISHING`，不会自动重发；没有增加队列状态、改变任务身份或清除失败证据。预取状态在 `us-broad-prefetch-status.json`，正式队列和状态页仍分开记录。

年度续跑在原来源、队列和成功前缀逐字段核验通过，且正式数据集没有未决锁后启动预取，剩余年度发布与其他 `PLANNED` 来源下载可以同时推进。尚未通过该门槛时不启动预取线程；停止或异常时关闭同一个生产者，不另起采集账户或清除原配额。代码和实库验收见[年度续跑期间恢复预取](decisions/2026-10-22-年度续跑期间恢复预取.md)。

预取的 `broad-source-max-attempts` 默认 3（允许 1 至 5），`broad-source-retry-delay-ms` 默认 5000（允许 1000 至 60000）。尚未绑定原文的 I/O 错误和 HTTP 5xx 可重试，等待从失败时刻起递增，默认五秒、十秒，单次退避最多六十秒，并遵守更晚的 `Retry-After`。每次请求仍计入原共享配额；429、配额等待和取消不消耗该错误次数。SQLite 的 `broad_source_retries` 保留尝试历史、原 proof 和下次时刻；重启不清零，等待时状态页显示 `WAITING_SOURCE_RETRY`，不会提前显示来源范围完成。其他证券可继续下载。4xx、响应超限及已冻结原文的校验失败直接留证；旧失败任务、范围复核和正式未决状态不自动重置。关闭预取时不启用这项重试。

2026-10-20 已核对 IRD、IRDM 两次真实来源 I/O 失败后的自动恢复：沿原任务、原截止日取得有效冻结原文，退避时刻、失败历史及两次共享预算请求都保留，无人工重排。证据在 `var/market-data/scope-review/source-retry-v23-20261020/automatic-real-io-recovery.json`；这里只证明来源恢复，正式价格仍需原发布验收。

年度组的报价数组、纽约日期和完整公司行动只解析一次，私有解析结构只在本组内复用；每次投影仍创建独立行，原文列表和清单不含缓存，原始 ZIP 摘要、全字段回读及 WAL 和物理对象检查不变。128 个响应、401681 行、21 个年度的保留原件与原成功 Native prepared 二十列一致。单轮离线年度投影从 16.68 秒降至 1.13 秒，解析后驻留约 153 MiB、观测堆峰值约 703 MiB，在原 2 GiB 堆上限下通过；不包含 WAL 和正式回读，不能等同全流程提速。62 个美股模块测试通过，原件对照及内存记录在 `var/market-data/scope-review/parser-cache-20261020/`。

当前挂牌范围来自 Nasdaq Trader 两份官方列表。候选之外的 ETF、测试证券、权证、优先股等仍保留排除原因。历史范围另保留 Tiingo 完整 ZIP 中美国场所的 51629 条 Stock/ETF 记录，按原始行号保存重复代码的不同窗口。候选证券继续逐个请求价格；目录中的日期只是供应商声称的可用范围，不能当作正式上市退市事件。历史退市证券、OTC 的实际价格及历史指数成分尚未齐备。

官方名称末尾的单数 `Right` 也属于非普通股票，不能误识别成普通股；含 Bright、Wright 的公司名及 ADR 解释里的 right 不受这条规则影响。当前官方明确为非普通证券或 ETF、但历史目录笼统标作 Stock 的未发布候选进入 `SCOPE_REVIEW_REQUIRED`，保留原文、原拒收原因及历史窗口，待核实历史类别和代码复用。ETF 还须有官方 `is_etf=true`，不能只按名字猜测。已经验收、发送未决或绑定来源组的任务不会被该规则改写；六只已验收参照 ETF 的来源保持原样。

[SPY 官方持仓](https://www.ssga.com/library-content/products/fund-data/etfs/us/holdings-daily-us-en-spy.xlsx)和 [QQQ 官方持仓](https://dng-api.invesco.com/cache/v1/accounts/en_US/shareclasses/QQQ/holdings/fund?idType=ticker&interval=monthly&productType=ETF)的 2026-10-16 股票范围已取得，分别为 503、101 只，并已逐字段验收。基金中的现金、CVR 和衍生品不纳入股票范围。挂牌队列优先这组证券，并补入当前挂牌清单之外的基金股票；这只是当前覆盖参照，不是官方历史指数成分。

## 独立只读覆盖核对

```powershell
python tools/audit_us_security_identity.py --from 2006-01-01 --through 2026-10-16
```

身份报告保留每条历史目录行、当前官方类别及重叠窗口。同代码可能来自转板、类别变化或不同发行人；当前有价格不能证明每个历史窗口已覆盖。当前 ETF 的旧 Stock 窗口不删除，也不拿基金价格替代；没有冲突标记同样不能单独证明目标完成。

复用代码取得独立价格后，用 `us_security_identifiers` 查询原市场代码与实际价格键。日线表 `symbol` 可以是供应商真正接受的永久请求代码；原市场代码保存在映射的 `source_ticker`，当前发行人的价格键保持原样。价格先完成原文、字段与 WAL 验收，再导入映射：

数据定义版本 2 的 `openfigi_composite` 可保留来源明确的 null，其余永久身份和价格证据仍须核对。清单的整数 `version=1` 仍要求非空 FIGI；`version=2` 才接受该列的 JSON null，字段不能省略，也不能写成文本 `"null"`。表名、十八列、业务键及已有快照不变。

```powershell
.\gradlew.bat usMarketData --args='import-security-identifiers --source var/market-data/scope-review/symbol-disambiguation-20261020/amtd-identifiers-manifest.json'
```

清单版本为 1，包含 `records` 和 `sources`。每条记录指向实际的搜索原文、历史目录原文与原成功价格运行；不能只提供证券名称和日期。入口逐字段回读完整价格窗口，拒绝未决、错误物理对象、不同发行人或只覆盖部分价格的清单。该映射不是完整上市退市登记，搜索原文也不能单独证明数据可用。

```sql
select security_id, source_ticker, security_name, price_symbol, price_from, price_through
from us_security_identifiers
where source_ticker = 'AMTD'
latest on observed_at partition by provider, security_id;
```

```powershell
python tools/audit_cross_market_coverage.py --through 2026-10-20 --crypto-through 2026-10-20
```

结果保存在 `var/market-data/coverage-audit.json`，包括来源队列、未决锁、WAL、证券目录及持仓覆盖、分钟范围、ITCH 局部范围和加密时间网格。加密小时线另独立聚合 OHLCV 与日线对比；官方停市减计只接受完整本地证据及仍不存在的价格键。原始 UTC 满网格和扣除已核对停市后的场所网格分别报告。首尾日期或行数吻合不替代逐字段回执，也不证明美股中间交易日全部齐备。

日线另用已验收的 SPY/Tiingo 实有日期作逐日诊断参照，按年检查各证券及来源在已采集首尾之间的缺日、重复日和参照外日期。来源组须沿原成功运行核对成员摘要；核心单日 ZIP 还核对原 prepared、全部成员和日线二十列。日期集合用逐年成员检查与唯一日期数证明相等，对异常序列读日期明细，避免全证券全日期笛卡尔积；未决及采集中来源不参与结论，核对前后的已验收来源范围必须稳定。这项诊断不能代替官方历史交易日历、停牌及上市退市边界，也不能将尚未取得的证券判为完成。

分钟覆盖另用保留的 Nasdaq 当年日历原文和 NYSE 交易时间原文逐日核对，分别报告原表的休市日补齐记录、正常时段视图的缺日及稀疏分钟。2026-01-01 至 2026-10-16 应有 199 个交易日；排除休市日不等于把尚未采集的交易日判为完整。

5 分钟字段另由实库 1 分钟数据按 UTC 五分钟桶独立聚合核对 OHLCV，同时报告缺少派生行、多余派生行及不足五根的来源桶；不只比较数量。首批 SPY 2026-01-02 的冻结样本已补入缺少的 78 根派生行，原有 390 根分钟线逐字段未变。

### 2026-10-20 补充的七份日线来源

以下 Tiingo 原文已由 Java 正式写入，并从原文独立重建期望值核对二十列，共新增 16726 行。原有 51358 行价格、8 条永久映射及原 Yahoo 拒收和历史候选状态未变；同一发布锁内确认 WAL 结清，原物理表不变。

| 请求代码 | 行数 | 实有来源窗口 |
| --- | ---: | --- |
| GMEX | 153 | 2026-03-11 至 2026-10-16 |
| FTEL | 650 | 2023-08-08 至 2026-03-11 |
| GP | 2857 | 2015-02-10 至 2026-10-16 |
| GTBP | 2279 | 2017-09-22 至 2026-10-16 |
| HLSQ | 1910 | 2019-03-13 至 2026-10-16 |
| IGR | 5230 | 2006-01-03 至 2026-10-16 |
| IMMP | 3647 | 2012-04-17 至 2026-10-16 |

以 SPY 实有日期对照，GP 早年有 82 个缺日，HLSQ 缺 2019-06-07，最近五年均无参照缺日；未用平价或零量补齐。这个对照不证明 OTC 或稀疏证券每天一定有成交。[IMMP 前身 ADS 自 2012-04-16 交易](https://www.sec.gov/Archives/edgar/data/1506184/000119312518302492/d544337d20f.htm)，该首日尚未取得，不能宣称完整首次上市历史。

[FTEL 改名为 GMEX](https://www.sec.gov/Archives/edgar/data/1928581/000149315226009687/ex99-1.htm)，两段仍按供应商实际请求代码和各自调整口径保存，3 月 11 日的重叠原文保留，不拼成连续复权序列。[HLSQ 原为 BiomX/PHGE](https://www.sec.gov/Archives/edgar/data/1739174/000121390026100156/ea0305538-8k_tessera.htm)，不能把此前价格解释成同一国防业务历史。[IGR 是封闭式基金](https://www.cbreim.com/strategies-and-solutions/investment/listed-real-assets/global-real-estate-income-fund)，现金分配沿用来源，不据此推断税务性质，也不计作普通经营公司覆盖。

本轮 20 次 Tiingo 请求均成功，最短间隔 2000 毫秒，使用原共享持久预算。本机近 31 天记录涉及 49 个请求代码，不能据此断定账户在其他位置的剩余额度。本机原文、身份材料、日期诊断、Native 回执、独立核对和 WAL 证据保存在 `var/market-data/scope-review/newest-six-20261020/`。这些补充不代表全市场、全部历史身份及剩余来源缺口已完成。

### DEC 合股前后窗口

DEC 的九日断点来自临时代码 `DECPD`。原目录保留了这段 OTCQX/USD 窗口，实际 Tiingo 原文首日 `splitFactor=0.05`，与[发行人的 SEC 回函](https://www.sec.gov/Archives/edgar/data/1922446/000110465923125440/filename1.htm)确认的 2023-12-05 二十合一一致；发行人原始 HTML 也已冻结。

| 供应商请求键 | 行数 | 实有来源窗口 |
| --- | ---: | --- |
| US000000087028 | 1288 | 2018-10-22 至 2023-12-04 |
| DECPD | 9 | 2023-12-05 至 2023-12-15 |
| DEC | 710 | 2023-12-18 至 2026-10-16，原已验收 NYSE 段 |

本次新增前两段 1297 行，三个独立来源的日期并集与 SPY 的 2007 个参照日一致，无缺日或重叠。各段二十列原值独立验收，原有 68084 行价格未变；各自调整价继续分开，不构造跨代码连续复权序列，也不改原 Yahoo 失败和历史候选状态。名称中的 GBP 面值文字不当作报价币种，报价仍按原目录的 USD 保存。

新增两条永久映射，十八列已独立核对，原八条未变，总数为 10。DECPD 的 FIGI 保留真实 null；搜索中的 `isActive=true` 只是供应商声明，不能据此认定这个九日临时代码现在仍交易。锁内检查确认两张原表和 WAL 有效。新原件、三段日期证据、价格及映射回执在 `var/market-data/scope-review/dec-temporary-window-20261020/`；早前失败诊断继续作为当时证据保留，全市场剩余采集与身份复核继续推进。

### AAAP 历史股票与现行 ETF

旧 AAAP 是 Advanced Accelerator Applications S.A.，已按 Tiingo 实际接受的永久代码 `US000000013210` 写入 2015-11-11 至 2018-02-09 的 566 行日线。[Nasdaq 首次挂牌公告](https://www.globenewswire.com/news-release/2015/11/13/787188/0/en/Nasdaq-Welcomes-Advanced-Accelerator-Applications-S-A-Nasdaq-AAAP-to-The-Nasdaq-Stock-Market.html)确认起点；[发行人退市条款](https://www.sec.gov/Archives/edgar/data/1611787/000110465918003180/a18-3541_2ex99d1.htm)与[收购方申报](https://www.sec.gov/Archives/edgar/data/1114448/000110465918005861/a18-3997_2sc13d.htm)显示 Form 25 于 2018-02-01 提交，十日后生效。末个交易日据此推定为 2 月 9 日，并与原文正量记录吻合；不是另有交易所终止日期公告。原文完整保留 572 行，2 月 12–20 日的六根零量平价记录未写入。

价格二十列及新增永久映射十八列独立回读一致，原有 69381 行价格和十条映射未变，映射累计 11 条；FIGI 继续保留来源真实 null。同一发布锁内确认两表 WAL 结清、缓冲为零、未挂起，原物理对象未变。[当前 AAAP 的 Nasdaq 通函](https://www.nasdaqtrader.com/content/newsalerts/2026/InfoCircular/AAAP_Circular_NQ.pdf)对应 2026-06-23 起的 Pacer Barings CLO Market Flex ETF，不能替代旧股票历史。原范围复核任务和两类目录行保留。历史公告经网页读取核对，本机 HTML 下载的 403 回执及事实摘录另存，不冒充完整 HTML 原件；现行 ETF 的完整 PDF、价格原文、Native 回执及独立核对在 `var/market-data/scope-review/aaap-historical-stock-20261020/`。这次补充不代表全市场已齐备。

### ACOM、BREW、BUFF 历史窗口及公司行动缺口

2026-10-20 又补入三段旧股票价格，共 5197 行；当前同代码 ETF 与原范围复核任务保留。价格二十列及三条永久映射十八列独立回读一致，原 69947 行价格、11 条映射未变，映射累计 14 条。同一发布锁内确认原物理表及 WAL 有效。

| 历史市场代码 | 实际永久价格键 | 行数 | 已验收来源窗口 |
| --- | --- | ---: | --- |
| ACOM | `US000000008464` | 791 | 2009-11-05 至 2012-12-27 |
| BREW | `US000000001185` | 3711 | 2006-01-03 至 2020-09-29 |
| BUFF | `US000000009668` | 695 | 2015-07-22 至 2018-04-24 |

[BREW 交易所公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2020-172)明确最后交易日为 2020-09-29，次日零量平价原文不计入价格；2006 年窗口内三根来源零量记录按原值保留，日期覆盖不证明这三天实际有成交。[BUFF 交易所公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2018-64)明确停牌生效日为 2018-04-25，因此保留 24 日的正量记录，排除其后八根平价记录。两份完整公告及 SHA-256 已冻结。[ACOM 发行人](https://www.sec.gov/Archives/edgar/data/1469433/000119312512517358/d459290dex991.htm)称于 2012-12-28 停止交易，未给出具体时刻；该日正量原文仍待核，另十根后续平价原文完整保留。ACOM 搜索中的 `isActive=true` 仅保留供应商声明，不当作当前仍上市的证明。

AIQ 旧股票原文已取得，但 2012-12-27 的 `splitFactor` 仍为 1，与[发行人已实施的一比五合股](https://www.sec.gov/Archives/edgar/data/817135/000119312513105726/d502254dex991.htm)不符，尚未写入。ARIA 的代码搜索达到上限，返回的旧 Stock 永久代码名称却是现行 ETF；名称搜索也未找到旧公司，因此仍需独立身份来源。对 32 份补验与探针 Tiingo 日线原文、65686 行做调整股数比例与拆股字段跳变检查，仅发现上述 AIQ 疑点；这项诊断不覆盖全部来源，也不生成拆股事件。

原文、三段成功运行、三条映射、字段回读、锁内 WAL 和待核事项在 `var/market-data/scope-review/historical-four-20261020/`。全市场采集和这些缺口继续推进，不能将已取得的原文或部分历史窗口视作全部完成。

### MTNB 停牌空区间及两次合股

MTNB 的 Yahoo 原文在 2026-09-28 缺少整根 OHLCV，原失败任务及原文继续保留。已用当前官方挂牌清单、历史目录和 Tiingo 唯一身份结果核对 Matinas Biopharma Holdings，另取得两份真实 Tiingo 响应，共写入 3052 行：2014-08-18 至 2024-08-27 的 2519 行，以及 2024-09-03 至 2026-10-16 的 533 行。代码和发行人相符，价格键仍为 `MTNB`，未新增永久映射。

[发行人年度报告第 56 页](https://www.matinasbiopharma.com/investors/sec-filings/all-sec-filings/content/0001493152-26-014132/0001493152-26-014132.pdf)确认 2024-08-27 宣布停牌，至 2024-09-03 开盘恢复。完整探针保留 3055 行，8 月 28–30 日三根零量平价记录未写入；两个独立请求窗口的全部原始字段与探针对应行一致。2024-09-03 的 `splitFactor=0.02` 与[当年合股公告](https://www.matinasbiopharma.com/investors/sec-filings/all-sec-filings/content/0001493152-24-034729/form8-k.htm)一致，2026-09-28 的 `0.0666666667` 与[十五合一公告](https://content.equisolve.net/sec/0001493152-26-044451/form8-k.htm)一致，保留来源精度。

两段价格二十列均用 PGWire 独立回读一致，原有 75144 行价格和 14 条映射未变；锁内确认两表 WAL 结清、缓冲为零、未挂起，原物理表及结构不变。最近五年相对 SPY 实有日期仅扣除上述三日停牌；早年仍缺 2014-09-09、10、15、16 及 2015-06-30，来源中的其他早年零量报价不证明实际成交，也不据此补造价格。四份完整发行人原件及摘要、真实价格响应、Native 回执、独立回读和 WAL 证据在 `var/market-data/scope-review/mtnb-recovery-20261020/`。全市场采集、历史身份和剩余来源缺口仍未完成。

### NFE 重复合股来源待核

NFE 的 Yahoo 原文有 988 行负调整收盘价，另缺 2026-09-10 整根报价，原失败任务保留。独立取得的 Tiingo 原文有 1939 行，日期相对 SPY 无缺失，字段为正；但 2026-09-11 的零量平价行及 9 月 14 日各记录一次 `splitFactor=0.02`，9 月 10 日原始收盘价 0.3296、调整收盘价 824，相差 2500 倍。[Nasdaq 公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-654)只列一次五十合一、9 月 14 日生效，来源公司行动与调整值不接受，1939 行全部留待核实，未写入正式表，也未编辑原文或自行删除事件。

完整 Tiingo 原文、完整 Nasdaq 公告与摘要、原 Yahoo 诊断及拒收决定在 `var/market-data/scope-review/nfe-recovery-20261020/`。发行人及首次挂牌网页经网页读取核对，本机下载返回 403，失败回执和另存事实摘录保留，不当作完整本地 HTML。日期齐全、字段为正不能替代公司行动核对；该缺口仍未完成。

### OBAI 直接挂牌窗口与观察时复权口径

OBAI 的 Yahoo 响应没有目标区间行情，原失败任务保留。已核对当前官方挂牌、历史目录及 Tiingo 精确代码身份，写入 2026-02-04 至 2026-10-16 的 177 行真实日线，每行成交量为正，相对 SPY 日期参照无缺日。[发行人当日 SEC 披露](https://www.sec.gov/Archives/edgar/data/1756064/000149315226005082/ex99-1.htm)确认 2 月 4 日直接挂牌，挂牌寿命短于五年；本机该 HTML 下载的 403 回执和网页读取事实另存，不冒充完整原件。

[Nasdaq 公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-736)确认二十合一自 2026-10-19 生效。10 月 20 日取得的这份响应，截止日前调整 OHLC 已含该次合股，均为原价的 20 倍，调整量按供应商原始取整值保留；这是本次观察时口径，不能作为 10 月 16 日当时可知的复权快照。177 行窗口内拆股因子仍为 1，没有把截止日后的事件或行情写进来，也没有按截止日反推改写供应商调整值。

价格二十列独立 PGWire 回读一致，原有 78196 行价格和 14 条映射未变；锁内 WAL 结清、缓冲为零、未挂起，原物理表及结构未变。搜索原文的 FIGI 为文本 `nan`，不接受为有效 FIGI，也不转换成真实 null；本次沿当前 `OBAI` 价格键保存，未新增映射。完整价格原件、完整 Nasdaq 公告、身份与观察口径决定、Native 回执、字段回读及 WAL 证据在 `var/market-data/scope-review/obai-recovery-20261020/`。这次验收不代表全市场和历史来源缺口已完成。

### EMC、NFX 历史股票及 USG、ULTI 待核项

2026-10-20 补入两段历史普通股，共 5989 行，按实际永久请求键保存，与当前同代码 ETF 分开：

| 历史市场代码 | 永久价格键 | 行数 | 已验收来源窗口 |
| --- | --- | ---: | --- |
| EMC | `US000000000529` | 2688 | 2006-01-03 至 2016-09-06 |
| NFX | `US000000000459` | 3301 | 2006-01-03 至 2019-02-13 |

[EMC 发行人申报](https://www.sec.gov/Archives/edgar/data/790070/000119312516706576/d258881d8k.htm)确认 2016-09-07 完成并购，价格窗口先止于前一日，与原文正量报价末日吻合；其后七根零量平价原文保留，未写入。未取得独立交易所最后交易日公告，9 月 6 日仅作为本次已验收窗口末日。[NFX 发行人申报](https://www.sec.gov/Archives/edgar/data/912750/000119312519044446/d710595d8k.htm)明确 2019-02-13 收盘后停止交易，因此保留当天正量报价，排除次日平价记录。每股换取 2.6719 股 Encana 的并购对价不当作 NFX 拆股，也不续接收购方价格。

两段相对 SPY 实有日期无缺日，窗口内成交量均为正。价格二十列及两条永久映射十八列独立回读一致，原 78373 行价格、14 条映射未变，映射累计 16 条；两条 FIGI 都保留来源真实 null。NFX 搜索中的 `isActive=true` 只是供应商声明，不能据此认定旧股票现在仍上市。原范围复核任务及当前 ETF 类别不改；锁内确认两表 WAL 结清、缓冲为零、未挂起，原物理表及结构未变。

USG 的旧 Stock 搜索记录却显示现行基金名称，公司名搜索返回空，身份仍待核。ULTI 改用公司名搜索后身份唯一，首轮三次价格请求及一次 HTTP/1.1 补取均连接失败，失败回执保留；后续取得的完整响应及复权基准核对见下文。其[交易所并购通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2019-83)完整 HTML 已冻结；暂停生效日为 2019-05-06，不能直接等同最后成交日。

证据在 `var/market-data/scope-review/historical-large-caps-20261020/`，包括两份完整价格原文、原始身份搜索、目录关联、Native 回执、全字段回读、WAL、ULTI 失败请求和 USG 身份冲突。EMC、NFX 的一手申报及 Dell 公告经网页读取，本机下载的 403/404 回执和事实摘录另存，不冒充完整本地原件。全市场采集与剩余历史来源继续推进。

### ULTI 历史窗口及供应商复权基准

先取得 2019 年短窗口，再次完整请求取得 3356 行原文；两个响应重叠的 85 行全部原始字段一致。已按实际永久价格键 `US000000002862` 写入 2006-01-03 至 2019-05-02 的 3355 行，窗口内成交量均为正，相对 SPY 实有日期无缺日。5 月 3 日零量平价行留在原文中，未写入；5 月 2 日作为本次窗口末日，不另声称有独立交易所最后成交日公告。旧永久代码在 2019-05-04 至 2026-10-16 的查询返回空数组；现行 `ULTI` 则返回 2025-10-31 起的 REX ETF 行情，两者不混写。

来源窗口的分红全为 0、拆股因子全为 1，原始量与调整量逐行相同；调整 OHLC 与原价约差固定比例 `0.63351033257`。逐日收益最大差异约 `1.2e-11`，这只能证明窗口内的相对价格一致，比例形成原因仍未解释。数据库保留全部供应商原值；末日原始收盘价为 331.36，供应商调整收盘价为 209.9199838006，不能把后者当作已归一化到该末日的价格。

以下只读查询将价格统一到 2019-05-02 基准。完整四个 OHLC 版本在 `var/market-data/scope-review/ulti-recovery-20261020/window-normalization.sql`，已用实库 3355 行逐项核对，归一化四价与该窗口原价的最大误差约 `1.2e-10`。它不回写来源字段，也不证明这些值在 2019 年当时已经可知。

```sql
WITH anchor AS (
    SELECT close / adj_close AS scale_factor
    FROM us_equity_daily
    WHERE symbol = 'US000000002862' AND provider = 'TIINGO'
      AND trade_date = '2019-05-02'
      AND source_sha256 = 'cf8b515bb2f2f5754a43f95208ccc65280b37c0c2e559a68ab85c286b0d9cf5e'
)
SELECT p.trade_date, p.close AS raw_close, p.adj_close AS vendor_adj_close,
       p.adj_close * a.scale_factor AS window_adj_close
FROM us_equity_daily p CROSS JOIN anchor a
WHERE p.symbol = 'US000000002862' AND p.provider = 'TIINGO'
  AND p.trade_date >= '2006-01-03' AND p.trade_date <= '2019-05-02'
  AND p.source_sha256 = 'cf8b515bb2f2f5754a43f95208ccc65280b37c0c2e559a68ab85c286b0d9cf5e'
ORDER BY p.trade_date;
```

价格二十列与新增永久映射十八列独立回读一致，原 84362 行价格及 16 条映射未变，映射累计 17 条；FIGI 保留真实 null。锁内确认两表 WAL 结清、缓冲为零、未挂起，原物理表和结构未变，原 `ULTI` 范围复核任务与当前 ETF 类别保持。完整原文、固定比例核对、末日归一化查询与回执、Native 运行、映射及 WAL 证据在 `var/market-data/scope-review/ulti-recovery-20261020/`，较短响应和终止后查询另在 `ulti-bounded-20261020/`、`ulti-source-audit-20261020/`。全市场及未解释的来源口径继续核对。

### PRTA 上市前零价格与主采集新增验收

Yahoo 的 PRTA 原响应在 2012-12-18 至 20 日返回三根全零价格。[发行人 2025 年报 Item 5](https://www.sec.gov/Archives/edgar/data/1559053/000155905326000008/prta-20251231.htm)明确普通股自 2012-12-21 开始交易；本次另从该日起请求 Tiingo，取得并写入至 2026-10-16 的 3475 行真实响应。未截改原 Yahoo 文件，也不重排原失败任务。发行人年报经网页核读，本机 HTTP 403 回执保留，不冒充完整本地 HTML。

这段来源成交量均为正，日期与 SPY 实有参照完全一致，分红为 0、拆股因子为 1；原始与调整后的开、高、收和成交量相同。两日低价存在供应商差异：2020-06-30 的 `low=9.98`、`adjLow=9.985`，2020-07-02 的 `low=10.54`、`adjLow=10.535`。两列各自满足 OHLC 边界并按原值保存，不把差异改造成公司行动，也不声称原因已独立查明。调整值仍是观察时供应商版本，不证明历史当时已知。

3475 行二十列已独立从 PGWire 回读一致，原 87717 行价格与 17 条映射未变；本次不新增永久映射。锁内确认价格和映射表 WAL 结清、缓冲为零、未挂起，物理表和结构未变，上市前三根零价格及截止日后的行情仍未入库。原文、身份和边界核对、Native 回执及验证记录在 `var/market-data/scope-review/prta-recovery-20261020/`。

该目录的 `main-progress-independent-verification.json` 还核对两个主采集年度组：共 256 个来源、744038 行、42 个年度窗口，均为新增行。每个成员原文和 ZIP 内容、Native prepared 与回读文件摘要已对照，全部二十列另经 PGWire 逐年重读一致，核对前后已完成队列不变。主进程继续处理后续来源；RCAT 后续合法窗口及剩余九日缺口见下文。上述已验收范围不代表全市场及全部历史已完成。

### RCAT 合法历史窗口与九日缺口

原 Yahoo 响应在 2006-01-03 至 2026-10-16 有 5230 个日期，与 SPY 实有参照一致，但九天含十二个非法零价字段。保留原响应及 `FAILED_SOURCE` 任务，将其余日期分成十个连续窗口，已写入并逐字段验收 5221 行；九天仍待补全，不把原任务标成完整成功。缺口为 2008-01-31、08-27、11-07，2009-04-14、05-28、09-16、11-30，2010-01-05 和 2012-12-28。

这些行使用 `raw_price_basis=RECONSTRUCTED_FROM_YAHOO_SPLIT_ADJUSTED`，按完整响应中的后续合股还原价格和股数，保留来源精度。5221 行中有 2796 行来源量为零，不能当作这些日期有实际成交的证据，也不能据此判定当日无成交；其余 2425 行为来源正量报价。

历史主体先后为 Broadleaf、EnergyTek、Timefire，2019 年再与 Red Cat 合并，不能把早期报价解释成无人机业务历史。[2014 年申报](https://ir.redcatholdings.com/sec-filings/all-sec-filings/content/0001445866-14-000925/energytekcorp07302014.htm)确认 7 月 23 日一百五十合一，[2016 年发行人公告](https://www.globenewswire.com/news-release/2016/11/21/1126262/0/en/TimefireVR-Announces-Name-Change-Symbol-Change-and-Reverse-Stock-Split.html)确认 11 月 22 日十合一，[2019 年申报](https://ir.redcatholdings.com/sec-filings/all-sec-filings/content/0001554795-19-000332/rcat0919form10q.htm)确认 8 月 1 日一千二百合一，均与 Yahoo 事件一致。2016 年公告经网页核读，本机 HTTP 403 回执保留；另外四份发行人 HTML 已完整冻结，包括旧主体、更名合并和 2021 年计划升板资料。

另请求的 Tiingo 全历史只返回 2262 行，自其首日 2008-01-31 起相对 SPY 参照仍少 2446 天，响应仅包含 2019 年合股行。九条早期调整收盘价与 Yahoo 相差 1500 倍，等于前两次合股比例乘积；这只是诊断，未独立证明供应商的比例形成原因，不修改两份原件，也不把这份稀疏响应当作完整替代，本次不将其写入正式价格。

十段 Native 运行均已验收，全部二十列另由 PGWire 独立重建并回读一致；原 91192 行价格和 17 条映射未变。锁内确认两表 WAL 结清、缓冲为零、未挂起，结构和物理表未变，九个非法日期与截止日后的价格仍未写入。证据在 `var/market-data/scope-review/rcat-recovery-20261020/`，含原文关联、发行人资料、十段意图和回执、供应商差异、独立回读及 WAL。

同目录 `main-progress-independent-verification.json` 另对一个新完成的主采集组核对了 128 个来源、391046 行、21 个年度窗口，均为新增行。ZIP 成员、原件和 Native 证据摘要全部通过，二十列经 PGWire 逐年回读一致，已完成队列前后未变；全市场采集继续推进。

### 当前持仓范围的日期验收与 NBIS 旧窗口

按 SPY/QQQ 的 2026-10-16 股票持仓，共 604 条持仓、518 个不同代码，补录 NBIS 前独立读回 528 条供应商序列、2486410 行日期键。518 个代码都有已验收价格，全部序列到达 2026-10-16，各自已采集首尾间无 SPY 参照缺日、重复键或参照外日期。持仓、来源集合及物理表在核对前后稳定。这是日期和来源状态验收，字段值仍沿各自原 Native 回执；当前持仓继续标记 `ETF_HOLDINGS_PROXY`。

首尾之间没有缺日，仍不能证明历史起点已齐备。此次发现 NBIS 的目录声称始于 2011 年，Yahoo 却只有 2024 年复牌后的 499 行。已通过 Tiingo 当前 `NBIS` 请求键补入 2011-05-24 至 2022-02-25 的 2709 行旧 Yandex 日线，窗口内量均为正，原始与调整 OHLCV 一致，来源分红为 0、拆股因子为 1；未改写已有 Yahoo 段。

[Nasdaq 公告](https://www.nasdaq.com/press-release/nasdaq-resumes-trading-nebius-group-nv-2024-10-18)确认原 `YNDX` 于 2022-02-28 美东 6:38 停牌，2024-10-21 美东 9:00 以 `NBIS` 复牌。[发行人说明](https://group.nebius.com/newsroom/nebius-group-announces-planned-resumption-of-trading-on-nasdaq-and-provides-investor-update)确认同一 Class A 股份更名及俄罗斯资产剥离；另有复牌当日确认公告，三份完整 HTML 已冻结。停牌区间包含 665 个 SPY 常规交易日参照，均未补造价格；补入前段与原后段合计 3208 个不同日期，恰好覆盖 3873 个参照日扣除这段停牌后的集合。旧 Yandex 业务与目前 AI 基础设施业务属于不同阶段，两个供应商的调整价仍分开，不自动拼接成连续复权收益。

新增 2709 行二十列独立重建并经 PGWire 回读一致，原保护组 96912 行价格（含 NBIS 原 499 行）及 17 条映射未变，原成功 Yahoo 队列未改。锁内核对 WAL 结清、缓冲为零、未挂起，原物理表与结构保持。持仓日期验收及首尾诊断在 `var/market-data/scope-review/index-proxy-coverage-20261020/`，NBIS 原文、身份、停牌参照集合、Native、独立回读及 WAL 在 `nbis-history-20261020/`。

前一目录另验收主采集的一组 128 个来源、360948 行、21 个年度窗口：从原 Yahoo 响应独立重建全部二十列，对照原 Native prepared、回读文件和当前 PGWire 行均一致，已完成队列前后稳定。匿名试取 WIKI 历史档案返回 HTTP 403，未取得价格；回执在 `var/market-data/scope-review/wiki-access-20261020/`，该渠道不计入覆盖。全市场及剩余历史缺口继续推进。

### SKIN、SPRC、SRTM 合股来源及原运行恢复

2026-10-20 从 Tiingo 取得三份完整价格响应，按免费 Starter 预算采集，共写入 3526 行，截止日保持 2026-10-16。原 Yahoo 失败响应和任务保留，未新增永久映射。

| 当前来源请求键 | 已验收窗口 | 行数 | 保留的边界和缺口 |
| --- | --- | ---: | --- |
| SKIN | 2020-11-24 至 2026-10-16 | 1480 | 正量报价，窗口内相对 SPY 实有日期无缺日 |
| SPRC | 2021-08-30 至 2026-10-16 | 1288 | 仍缺 2021-09-29；2021-11-16 的供应商零量报价保留，不证明实际成交 |
| SRTM | 2023-10-10 至 2026-10-16 | 758 | 排除上市前 2023-10-09 的零量平价记录；合法窗口内正量报价、参照日期无缺日 |

SKIN 的[交易所公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-728)确认 2026-10-14 二十合一。SPRC 的三次合股分别由 [2023 年公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2023-555)、[2025 年公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2025-344)和 [2026 年公告](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-126)确认，市场生效日为 2023-09-28、2025-07-03、2026-03-04，比例为二十六合一、二十一合一、九合一。来源因子按原精度保存。发行人 [3 月 2 日执行公告](https://www.sec.gov/Archives/edgar/data/1611746/000121390026022005/f6k0302202601ex99-1.htm)也写 3 月 4 日生效，但 [20-F/A 附注 18](https://www.sec.gov/Archives/edgar/data/1611746/000121390026091427/ea0302520-20fa1_scisparc.htm)另写 2 月 5 日；差异原因尚未查明，不修改财务原文，也不将其当作第二次市场合股。9 月 29 日缺口在两家来源均无合法报价，继续留缺，不推定停牌。

SRTM 当前请求键返回原 La Rosa 的 LRHC 历史。[首次交易申报](https://www.sec.gov/Archives/edgar/data/1879403/000157587223001597/lrhc-20231009.htm)明确于 2023-10-10 开始交易，因此 9 日的供应商零量记录只留在原响应中。四次合股的交易所公告为 [2025-07-07 八十合一](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2025-350)、[2026-01-26 十合一](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-38)、[2026-04-20 十合一](https://www.nasdaqtrader.com/TraderNews.aspx?id=eca2026-249)和 [2026-09-08 六合一](https://www.nasdaqtrader.com/TraderNews.aspx?id=eca2026-641)，均与来源事件一致。[更名申报](https://www.sec.gov/Archives/edgar/data/1879403/000121390026110734/ea0309022-8k_stratum.htm)确认 2026-10-19 开盘改用 Stratum 名称和 SRTM 代码、CUSIP 未变；这晚于价格截止日，当前请求键不能当作截止日当时的市场代码或早期 AI 业务历史。

八份完整 Nasdaq HTML 及摘要已冻结；首次交易、更名和财务附注经网页核读，本机 SEC 下载的 403 回执另存，不冒充完整本地 HTML。三段二十列从原响应独立重建并经 PGWire 回读一致；原保护组 99621 行价格及 17 条映射未变，保护组累计 103147 行。读取按年份和证券分块，逐块拒绝截断。锁内确认价格与映射表 WAL 结清、缓冲为零、未挂起，原物理表和结构不变。SPRC 缺日、SRTM 上市前记录及截止日后价格仍未入库；供应商调整值均为观察时版本，不证明历史当时可知。

本次 SRTM 原运行曾在发送确认后超过两分钟可见性期限，进入 `IN_DOUBT`，原意图和锁保留。待 PGWire、HTTP 两路均确认 758 行全部字段与原冻结值一致、WAL 结清且原写入进程退出后，使用 `reconcile-frozen --repair-missing false` 核对原运行；补写行数为零。初始未决回执原样保留，另存当前状态及正式对账回执，没有重发 SRTM。

主采集随后在 2023 年窗口初始 WAL 预检失败退出。原运行 `market-ec35b05f-0566-4e79-ab6d-b1e806a0da60` 只有一条失败 RUN，无 ATTEMPT、SLICE 和所属区间锁；原 26421 个完整键全部不存在。确认原进程停止、原件和物理表不变、WAL 结清后，保存一次恢复意图，沿同一完整原件和原分页建立新 Native 运行 `market-a2e1ceef-921e-4d76-83b9-2f6bdf73b535`，26421 行完整验收。另从 Yahoo 原文独立重建二十列并核对 PGWire、原生证明文件及摘要，通过后仅用 `reconcile-broad-window` 推进原窗口 17，未改原失败运行，也未重置原窗口为未发送。

原 v24 发行包、两 GiB 堆和 2026-10-16 截止日不变，恢复进程 PID 为 39576。重启后先复核已完成原窗口，再完成 2024、2025、2026 三段共 82504 行；原组 128 个来源、383307 行、21 个年度窗口全部经原生协议逐字段再验，父组及成员已推进 `VERIFIED`。原失败账本保留，所有 prepared 和完整回读文件摘要已另核对，检查时无 `IN_DOUBT`，进程已继续后续来源。启动回执在 `var/market-data/us-broad-launch-v24-recovered.json`，恢复检查在该证据目录的 `main-resume-verification.json`。

证据均在 `var/market-data/scope-review/three-split-sources-20261020/`。其中 `main-progress-independent-verification.json` 另核对三个已完成主采集组，共 384 个来源、1072131 行、63 个年度窗口，全部从原 Yahoo 响应独立重建二十列并对照原 Native、PGWire，已完成队列前后不变。这些验收不代表全市场、全部历史或剩余来源缺口已经完成。

### SVA 停牌前历史与分红日期口径

2026-10-20 取得 SVA 的完整 Tiingo 响应，共 4445 行。已写入 2006-01-03 至 2019-02-22 的 3307 行，窗口内成交量均为正，与 SPY 实有参照日期一致，分红为 0、拆股因子为 1。[Nasdaq 停牌公告](https://www.globenewswire.com/news-release/2019/02/22/1741044/0/en/Nasdaq-Halts-Sinovac-Biotech-Ltd.html)确认 2019-02-22 美东 16:02:01 停牌，最后成交价为 6.47，保留当日常规日线；[后续交易所通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2019-36)也说明停牌及该日收盘的权利安排。

原响应另有 1138 根停牌后的 OHLC 平价记录，价格均为 6.47；其中 2020-06-29、07-23 各列成交量 8227，其余为零。全部保留在原件中，未作为停牌后实际交易写入。原 Yahoo 响应只有一根 2026-10-14 记录且开盘为零，原 `FAILED_SOURCE` 任务保持；其首次交易时间元数据不当作复牌证据。

Tiingo 在 2025-07-08 的平价行上另列 `divCash=55`。[发行人 4 月 30 日公告](https://www.sinovac.com/en-us/news/SINOVAC-Announces-Record-and-Distribution-Dates-for-Special-Cash-Dividend)当时称记录日为 5 月 23 日、预计约 7 月 9 日支付；[7 月 10 日披露](https://www.sec.gov/Archives/edgar/data/1084201/000110465925066785/tm2520228d1_ex99-2.htm)更新为按 7 月 7 日收盘的有效持有人支付，7 月 8 日由代理确认资格、7 月 9 日起寄支票或汇款。记录日、资格确认日、支付日分别保存；现有材料未独立确认供应商 7 月 8 日就是市场除息日。这条分红事件仍留在完整原件及决定记录中，未靠生成平价日线把它写成真实交易。

停牌前来源的调整收盘价/原价约为固定比例 `0.10525459574`，数值接近 `6.47 / (6.47 + 55)`；这是诊断，供应商实际算法未独立证实，原调整字段照存。按 2019-02-22 末日归一化的只读 SQL 在证据目录的 `window-normalization.sql`，全部 3307 行、四个 OHLC 已用 PGWire 核对，与原价最大误差约 `8.2e-10`。它沿用上文 ULTI 的末日归一化规则，不回写价格，也不证明这些调整值在 2019 年当时已经可知。

3307 行二十列从原响应独立重建并回读一致，原保护组 103147 行价格及 17 条映射未变，保护组累计 106454 行。分块读取、Native 原运行和锁内 WAL 检查通过，价格与映射表缓冲为零、未挂起，物理表和结构保持，1138 根后续平价记录仍未入库。本次未新增映射。完整价格和身份响应、完整交易所通函及 4 月分红公告、发布意图、Native、字段回读、归一化查询与 WAL 证据在 `var/market-data/scope-review/sva-recovery-20261020/`；停牌公告和 7 月披露经网页核读，本机 HTTP 403 回执保留，不冒充完整本地 HTML。全市场采集和剩余来源缺口仍在推进。

同目录 `main-progress-independent-verification.json` 另对两个已完成主采集组做独立复核，共 256 个来源、750501 行、42 个年度窗口，包含上节恢复后的完整原组。从原 Yahoo 响应重建全部二十列，再对照 prepared、原生完整回读文件及当前 PGWire 行，成员原文、ZIP 内容和所有摘要均一致，已完成队列前后不变。该统计是两个原组的完整验收范围，不将此前已写入的行重新计为本次补写。

### Red Hat、Cerner、Xilinx 历史普通股

2026-10-20 按唯一历史 Stock 搜索结果及原目录行取得三份永久代码价格原文，补入 11593 行和三条永久映射。各段从 2006-01-03 开始，全部为来源正量报价，窗口内相对 SPY 实有日期无缺日。

| 历史代码 | 永久价格键 | 行数 | 最后交易日 |
| --- | --- | ---: | --- |
| RHT | `US000000002460` | 3400 | 2019-07-08 |
| CERN | `US000000000082` | 4136 | 2022-06-07 |
| XLNX | `US000000000410` | 4057 | 2022-02-11 |

[Red Hat 申报](https://www.sec.gov/Archives/edgar/data/1087423/000095014219001516/eh1900894_8k-rh.htm)明确 2019-07-09 开盘前停止 NYSE 交易，每股合并对价为 190 美元现金。[Cerner 交易所通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=eca2022-127)明确最后交易日为 2022-06-07，8 日继续停牌、9 日暂停挂牌，对价为 95 美元现金。[Xilinx 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2022-23)明确最后交易日为 2022-02-11，14 日合并当天仍停牌、15 日暂停挂牌，每股换取 1.7234 股 AMD。并购对价单独保留，不生成普通分红或拆股，也不续接收购方价格。

CERN 的 6 月 8、9 日平价原文仍列 203、222 股，XLNX 的 2 月 14 日平价原文列 353 股；这三条均按明确交易边界排除，不能仅凭非零成交量判为有效交易日。CERN 目录声称的结束日为 2022-09-08，目录日期照存，已验收价格窗口止于交易所最后交易日。两份完整 Nasdaq HTML 已冻结，Red Hat 申报经网页核读，本机 403 回执保留；另一次 IBM 网页下载为 404，不当作有效原件。

RHT 窗口内来源分红为 0、拆股因子为 1，原始与调整收盘价相同。CERN 原文保留 2006-01-10、2011-06-27、2013-07-01 三个二合一事件和十二条现金分红；XLNX 保留六十二条现金分红，窗口内拆股因子为 1。逐日调整股数与来源后续拆股比例一致，全部价格、股数和事件值保留供应商精度及观察时版本，不证明历史当时可知。

11593 行价格二十列、三条映射十八列均独立回读一致，原保护组 106454 行价格及 17 条映射未变，保护组累计 118047 行价格、20 条映射。RHT 的 FIGI 保留真实 null；三条原 Yahoo 任务未推进，当前市场代码的价格键保持独立。锁内确认两表 WAL 结清、缓冲为零、未挂起，原物理表和结构保持。来源、身份和边界决定、Native、独立回读、映射及 WAL 在 `var/market-data/scope-review/legacy-tech-history-20261020/`。

CERN、XLNX 首次发布都超过两分钟可见性期限。各自原进程退出后，PGWire、HTTP 两路对完整冻结字段及 WAL 核对通过，再以 `reconcile-frozen --repair-missing false` 恢复各自原运行；两次补写均为零，初始未决回执原样保留。XLNX 此前尚未发送，沿原批次留下单独剩余发布意图，RHT、CERN 没有重发。

主采集原组 `broad-9ad5c99e-062d-43ca-ada9-c64739f56683` 的 2026 年窗口随后在发送前 WAL 预检失败，原运行 `market-9d332dfd-6959-40e1-8c8d-ae5059eaa54f` 无 ATTEMPT、SLICE 或所属区间锁，24452 个目标键全部不存在。保留原失败账本和窗口意图，在本批历史及映射全部验收后，以同一冻结原件建立新运行 `market-cea04e9a-a409-4d7d-94d1-70f6be0f16e0`；24452 行经原生验收、独立 Yahoo 原文重建及 PGWire 二十列核对通过，再由 `reconcile-broad-window` 推进原窗口 20。没有重置窗口或重发已确认行。

原 v24 发行包、两 GiB 堆和 2026-10-16 截止日保持，恢复进程 PID 为 27900。原组 128 个来源、361868 行、21 个年度窗口已由原生协议再次完整回读，全部 prepared 和回读文件摘要另核对，父组和成员已为 `VERIFIED`；检查时无 `IN_DOUBT`，进程已进入下一组。独立末段核对在 `main-2026-recovery-independent-verification.json`，原组恢复核对在 `main-resume-verification.json`，启动回执在 `var/market-data/us-broad-launch-v24-legacy-recovered.json`。

后续同表手动历史补验先请求主采集在年度窗口边界正常停止，确认原 PID 退出及账本状态，再发布和验收补充来源，最后恢复原队列。停止文件本身不能证明进程已停，原未决写入仍先按原运行核对。

另单次试取 AIQ 永久代码的独立合股接口返回 HTTP 403，未取得公司行动数据，AIQ 原缺失事件继续待核。[Tiingo 文档](https://www.tiingo.com/documentation/corporate-actions/splits)保留 Beta/企业早期访问说明，具体拒绝原因尚未由服务端确认；没有继续尝试其他代码或批量接口，回执在 `var/market-data/scope-review/corporate-actions-access-20261020/`。本地 Tiingo 账本于 09:26 UTC 记录最近一小时九次请求、滚动 31 天 72 个请求键，接收约 190 MB、保留约 59 MB 预留，均在配置预算内；其他客户端用量未知。全市场和剩余来源缺口继续推进。

### USBC 完整行补缺与 VATE 最近五年

2026-10-20 新增 6413 行，截止日保持 2026-10-16，没有新增永久映射。先让主采集在年度窗口边界正常停止，确认原 PID 27900 已退出、没有活动或未决 Native 运行、WAL 结清，再逐窗口发布。原主组的已验收窗口和未发送窗口都保持，未重置任务或清理锁。

| 来源请求键 | 本次验收范围 | 行数 | 来源及保留边界 |
| --- | --- | ---: | --- |
| USBC | 2006-04-18 至 2026-10-16 | 5158 | Yahoo 三段共 5157 行，Tiingo 只补 2025-02-19 的一整行；日期相对 SPY 实有参照无缺口或交叉重复 |
| VATE | 2021-10-18 至 2026-10-16 | 1255 | Tiingo 最近五年完整正量报价，分红为零；较早历史继续待核 |

USBC 的完整 Yahoo 原件只有 2025-02-19 六个行情数值全为 null。Tiingo 当日完整报价及四十合一事件有效，因此整行补入，未混合两家字段。[发行人申报](https://www.sec.gov/Archives/edgar/data/1074828/000165495425001652/knwn_8k.htm)确认当日以临时代码 `KNWND` 在 OTC Pink 按合股价交易。Yahoo 的另一次一百五十合一为 2015-06-17，与 [Visualant 原公告](https://www.sec.gov/Archives/edgar/data/1074828/000119983515000262/exhibit_99-1.htm)一致；Tiingo 则记为 6 月 18 日，并在实际生效日仍给旧基准平价零量报价，完整 Tiingo 历史因此不接受。它另缺 126 个 2025 年参照日，缺日集合和错期事件保留，没有手工改日期或因子。

NYSE American 暂停后，[1 月申报](https://www.sec.gov/Archives/edgar/data/1074828/000165495425001064/knwn_8k.htm)说明自 2025-01-30 在 OTC Pink 以 `KNWN` 交易；[后续申报](https://www.sec.gov/Archives/edgar/data/1074828/000165495425002090/knwn_8k.htm)说明 3 月 5 日以 `KNW` 恢复 NYSE American。单一场所暂停不等于全市场没有交易。[更名公告](https://www.sec.gov/Archives/edgar/data/1074828/000165495425009690/usbc_ex991.htm)确认 8 月 15 日开盘改用 USBC、CUSIP 不变，控制权收购于 8 月 6 日完成。当前代码回溯的是原 Visualant、Know Labs 股份阶段，不能当作新存款代币业务的历史。Yahoo 已验收段有 933 根供应商零量报价，照存但不证明实际成交；2006-04-18 只是本次来源首日，不据此推定首次上市日。

VATE 的 Yahoo 原件有 2729 根负调整收盘价，覆盖 2009-07-13 至 2020-05-13；其 2020-05-14 分红为拆股后数值 38.9。发行人[同期披露](https://www.sec.gov/Archives/edgar/data/1006837/000100683720000062/a202005048-kxexh991.htm)说明每股 3.89 美元由子公司 DBM Global 派给 DBMG 股东，HC2 是收款大股东。Tiingo 较早段另在 2017-01-05 列 2.59 美元；完整 [2016 年报](https://www.annualreports.com/HostedData/AnnualReportArchive/h/NYSE_HCHC_2016.pdf)第 164 页、印刷页 F-68 也明确付款方为 DBMG，记录日为 2017-01-09、支付日为 1 月 23 日，HC2 收到约 920 万美元。这些记录不能当作已核实的母公司普通股分红，较早历史仍待核，未删除来源事件或重算复权值。

两家来源在 VATE 最近五年均没有现金分红，2024 年合股市场生效日均为 8 月 9 日、因子为 0.1。[原申报](https://www.sec.gov/Archives/edgar/data/1006837/000100683724000124/hchc-20240808.htm)区分了 8 月 8 日美东 17 时法律生效和 9 日按合股价交易，来源日期保留。1255 个重叠日期中，OHLC 及调整收盘价共有 587 个字段的两源相对差异超过 0.1%，最大约 0.91%；差异明细另存，原因未独立确认，没有按容差混列。正式验收是 Tiingo 原件到二十列的精确回读，供应商调整值仍只代表观察时版本，不证明历史当时可知，也不自动将 USBC 两源调整价拼成连续收益。

6413 行从各自完整原件独立重建，Native 和 PGWire 二十列均一致；原保护组 118047 行价格、20 条映射以及两条原 `FAILED_SOURCE` 任务保持，保护组累计 124460 行。读取按年和证券分块，拒绝截断；锁内检查两表 WAL 结清、缓冲为零、未挂起，原物理表和结构不变。所有窗口各有不可覆盖的发布意图，没有重发已验收行。

证据在 `var/market-data/scope-review/usbc-vate-recovery-20261020/`，包括四份完整 Tiingo 响应、两份原失败 Yahoo、五个窗口决定、字段回读、两源差异、旧数据保护和 WAL。完整年报、相关整页文字及渲染图已冻结；其余申报经网页核读，本机 403/404 下载回执保留，不冒充完整本地原文。全部门禁通过后，仅核对摘要并归档本轮自建的停止标记，沿原 v24 发行包、两 GiB 堆和原截止日恢复同一主采集队列。全市场及剩余历史缺口继续推进。

恢复进程 PID 为 43696，启动回执在 `var/market-data/us-broad-launch-v24-usbc-vate-resumed.json`。原组 `broad-920a8584-0a33-4c56-8e0c-01155871655b` 的前十六个窗口保留原运行及回执，随后只发送原来未发送的五段共 129926 行。原组 128 个来源、372917 行、21 个年度窗口均完成原生完整回读，父组和成员为 `VERIFIED`；再从原 Yahoo 响应独立重建整组二十列，对照 prepared、原生回读文件和当前 PGWire 行均一致，来源及证明文件摘要、已完成队列前后不变。检查时无 `IN_DOUBT`，主进程已继续下一组。恢复核对在同目录 `main-resume-verification.json`，整组独立核对在 `main-progress-independent-verification.json`；整组行数不重复计入本批 6413 行补录。

### WCT 上市以来行情与 WORX 停复牌边界

2026-10-20 按 Starter 预算再取两份身份、两份价格原件，补入 2957 行，截止日保持 2026-10-16，没有新增永久映射。发布前，主进程 PID 43696 已在原组第十三个年度窗口验收后正常退出，活动和未决 Native 运行均为零，原队列保留。

| 请求键 | 已验收窗口 | 行数 | 排除范围 |
| --- | --- | ---: | --- |
| WCT | 2024-10-02 至 2026-10-16 | 512 | 不补造上市前历史 |
| WORX | 2016-10-06 至 2020-04-21；2020-08-10 至 2026-10-16 | 890 + 1555 | 排除 Nasdaq 停牌区间 2020-04-22 至 2020-08-07 的 76 根平价记录；其他场所覆盖仍待核 |

WCT 的[首次交易披露](https://www.sec.gov/Archives/edgar/data/1990251/000121390024085035/ea021611502ex99-2_wellchange.htm)确认 2024-10-02 开始交易。两次合股由交易所确认：[2026-03-06 五十合一](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-131)、[2026-09-08 五合一](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-642)。完整 Tiingo 原件补齐 Yahoo 在 9 月 8 日的整行 null，512 行均为正量报价，日期与 SPY 实有参照一致。[7 月议案](https://www.sec.gov/Archives/edgar/data/1990251/000121390026067545/ea029375201ex99-4.htm)另列每股面值从 0.0025 减至 0.000005，股数保持；[表决结果](https://www.sec.gov/Archives/edgar/data/1990251/000121390026075209/ea0297092-6k_wellchange.htm)中的后续合股授权也不等于当日实际执行。没有因面值变化或授权上限增造拆股事件。

WORX 当前请求键回溯原 Alliance MMA 的 AMMA 普通股，[原 IPO 披露](https://www.sec.gov/Archives/edgar/data/1674227/000114420416127480/v450341_ex99-1.htm)确认 2016-10-06 开始交易。[交易所合并通知](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2019-22)区分了 2019-02-01 法律完成和 2 月 4 日市场更名、改代码及十九合一。后续合股市场日为 [2023-10-11 十五合一](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2023-581)、[2026-04-10 十五合一](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-227)和 [2026-08-04 十二合一](https://ir.scworx.com/all-sec-filings/xbrl_doc_only/1907)。最后一次的修章法律生效时间为 8 月 3 日美东 23:59，[原 8-K](https://www.sec.gov/Archives/edgar/data/1674227/000121390026083096/ea0299755-8k_scworx.htm)及后续季报确认与次日市场日期的区别。来源事件日期及小数精度保留，旧体育业务阶段不标作医疗数据业务历史。

[Nasdaq 停复牌公告](https://ir.nasdaq.com/news-releases/news-release-details/nasdaq-scheduled-resumption-scworx-corp)确认 WORX 于 2020-04-22 美东 09:25:05 停牌，8 月 10 日美东 07:00 复牌。[同期发行人披露](https://www.sec.gov/Archives/edgar/data/1674227/000121390020020840/ea125215-8k_scworxcorp.htm)另区分了 5 月 5 日届满的 SEC 暂停与随后 Nasdaq T12 停牌；其他场所在后续区间的实际成交覆盖仍待核。完整 Tiingo 原件有 76 根区间内 OHLC 都为 5.755 的记录；其中 4 月 22 日、6 月 29 日、7 月 23 日仍列 108514 股，8 月 5 日列 187 股，其余 72 根为零量。它们全部留在原件及排除清单中，不以非零量证明常规时段成交。两个已验收窗口均为正量报价，2445 个日期等于 2521 个 SPY 参照日扣除本次排除区间后的集合；该参照集合只证明本次窗口边界。

2026 年的场所变化另行核对：[9 月披露](https://www.sec.gov/Archives/edgar/data/1674227/000121390026102644/ea0306457-8k_scworx.htm)说明 Nasdaq 自 4 月 14 日暂停后，WORX 继续在 OTCQB 报价；[发行人 10 月通知](https://ir.scworx.com/press-releases/detail/80/scworx-announces-nasdaq-trading-to-resume-october-7-2026)及[后续 S-3](https://ir.scworx.com/all-sec-filings/content/0001213900-26-110177/ea0308692-s3_scworx.htm)确认 10 月 7 日恢复 Nasdaq 交易。Yahoo 9 月 8 至 11 日的四个 null 日期可从完整 Tiingo 原件补齐，不能把 Nasdaq 暂停期间的全部 OTC 报价当成全市场停牌。Tiingo 身份原件仍写 `OTCMKTS`，这不证明截止日当时场所；正式价格沿用 `US_COMPOSITE`，不把全部历史标作 Nasdaq 成交。

本批分红均为零，原始与调整 OHLC 及取整后的调整股数已按公告的精确整数合股比核对，来源值未改写。2957 行二十列从原件独立重建，Native 和 PGWire 回读一致；原保护组 124460 行价格、20 条映射及两条原 `FAILED_SOURCE` 任务保持，保护组累计 127417 行。按年和证券分块读取并拒绝截断，76 根排除记录仍未入库，锁内确认两表 WAL 结清、缓冲为零、未挂起，物理表及结构保持。调整值是观察时版本，不证明历史当时可知。

完整原件、八份可下载的完整公开材料、其余七份下载失败回执、网页原始事实、窗口决定、不可覆盖的发布意图、独立回读及 WAL 在 `var/market-data/scope-review/wct-worx-recovery-20261020/`。正常停机、原队列快照和恢复前检查也在同目录；恢复继续使用原 v24 发行包、两 GiB 堆及原截止日。全市场和剩余历史缺口继续推进。

同目录 `tiingo-budget-verification.json` 于 11:07 UTC 只读核对实际客户端限额和本地用量：最近一小时 4 次、一天 334 次；滚动 31 天 76 个请求键，接收 192468176 字节，另有 58785799 字节预留。实际配置为每小时 50 次、每天 1000 次、滚动月 500 个请求键及 1 GiB，均在本地预算内。失败请求也计入次数，请求键不等于不同发行人数；未查询服务端账户用量，其他客户端的用量未知。

主采集已以 PID 41960 恢复，47 个 JAR 摘要与原 v24 发行包一致，启动回执为 `var/market-data/us-broad-launch-v24-wct-worx-resumed.json`。原组 `broad-d406d3db-0045-4b21-8747-83ea08700068` 的前十三个窗口保留原运行和回执，随后仅续采原来未发送的八段共 212285 行。原组 128 个来源、455186 行、21 个窗口已完成原生完整回读，父组和成员均为 `VERIFIED`；再从原 Yahoo 响应独立重建全部二十列，对照 prepared、回读文件及当前 PGWire 均一致，原件、证明文件摘要和已完成队列前后稳定。检查时无 `IN_DOUBT`，进程已继续下一组。完整核对分别在同目录 `main-resume-verification.json`、`main-progress-independent-verification.json`；整组总量不重复计入本批 2957 行补录。

### Aetna、Celgene、Express Scripts 历史普通股

2026-10-20 从未截断的 Tiingo 历史搜索确认三只非活跃美国普通股的永久请求键，补入 10011 行及三条永久映射。三段从 2006-01-03 开始，全部为正量来源报价，与 SPY 实有参照日期一致。

| 历史代码 | 永久价格键 | 行数 | 最后验收日期 |
| --- | --- | ---: | --- |
| AET | `US000000000146` | 3250 | 2018-11-28 |
| CELG | `US000000000276` | 3496 | 2019-11-20 |
| ESRX | `US000000000425` | 3265 | 2018-12-20 |

[Aetna 8-K](https://www.sec.gov/Archives/edgar/data/1122304/000112230418000178/form8-k.htm)明确请求 2018-11-29 开盘前暂停交易，28 日来源仍有正常价格幅度及正量。[Celgene 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2019-65)明确最后交易日为 2019-11-20，21 日起暂停；原件在 22 日另给 OHLC 同为 108.24、量为 114302 的记录，按边界排除。[Express Scripts 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2018-152)明确 2018-12-21 起暂停，20 日来源仍有正常幅度及正量；请求涵盖 21 日，供应商只返回到 20 日，没有补造价格。

三笔并购对价分别为 AET 每股 145 美元现金加 0.8378 股 CVS、CELG 每股 50 美元加一股 BMY 及一个新 CVR、ESRX 每股 48.75 美元加 0.2434 股 CI，均单独记录，没有生成普通现金分红、换股拆股或收购方续接。搜索另返回的 `CELGZ` 是旧权利证券，与 CELG 普通股及本次并购产生的 CVR 分开，未选作这段价格。

日期类型分别保留：[Aetna 2006 申报](https://www.sec.gov/Archives/edgar/data/1122304/000095012306002054/y17756e8vk.htm)记一拆二股票股息在 2 月 17 日分派，供应商市场事件为 2 月 21 日；[公开拆股资料](https://www.stocksplithistory.com/aetna/)佐证该市场日期。[Celgene 2014 公告](https://www.sec.gov/Archives/edgar/data/816284/000119312514241821/d744699dex991.htm)明确 6 月 25 日分派、26 日 Nasdaq 按拆股价交易，也列出此前 2006-02-27 一拆二。[Express Scripts 2007 公告](https://www.sec.gov/Archives/edgar/data/885721/000088572107000034/stocksplit2007.htm)使用 6 月 22 日分派或生效口径，供应商事件为 25 日，由[同期盘前报道](https://247wallst.com/investing/2007/06/25/pre-market-st-2-8/)佐证；[2010 季报](https://www.sec.gov/Archives/edgar/data/885721/000095012310096652/c60119e10vq.htm)另确认 2010-06-08 一拆二。两个分派日之后的下一 SPY 参照日也分别为 2 月 21 日、6 月 25 日；这是日期交叉核对，未宣称两个市场日均取得独立交易所原件。没有把分派日重记为第二次拆股或挪动来源事件。

AET 原件保留 36 条现金分红，最大每股 0.5 美元；CELG、ESRX 没有现金分红。各段调整股数与完整原件中的后续拆股比例一致，CELG、ESRX 的调整 OHLC 与纯拆股比例相符，AET 同日各 OHLC 的调整比例一致。价格、股数、分红、事件因子均保持供应商精度和观察时版本，不证明历史当时可知。

10011 行价格二十列、三条映射十八列均独立核对；原保护组 127417 行价格和 20 条映射保持，保护组累计 137428 行价格、23 条映射。三只股票的 FIGI 均保留来源真实 null。三条原 Yahoo 任务保持 `PLANNED`，当前代码价格与历史永久键独立。按年和证券分块读取并拒绝截断，锁内确认两表 WAL 结清、缓冲为零、未挂起，原物理表和结构不变。

证据在 `var/market-data/scope-review/legacy-healthcare-history-20261020/`，包括三份完整搜索、三份完整价格、两份完整 Nasdaq 通函、完整次级日期资料，以及其他六份下载失败回执、网页核读事实、发布意图、独立回读、映射与 WAL。发行人原文与次级佐证类别分开，失败下载不冒充完整本地原件。

发布前原 PID 41960 已在年度窗口边界正常退出。验收后仅归档本轮停止标记，以 PID 33868 恢复原队列、同一 v24 发行包、两 GiB 堆及 2026-10-16 截止日，47 个 JAR 摘要一致。启动回执为 `var/market-data/us-broad-launch-v24-healthcare-resumed.json`。原组 `broad-71ee42ee-9c23-4131-b8e9-5a64eefe478a` 的前十七段运行及证明不变，随后只发送原来未发送的四段共 106708 行；128 个来源、21 个窗口、381154 行全部 `VERIFIED`。再从原 Yahoo 响应独立重建二十列，对照 prepared、完整回读文件及当前 PGWire 全部一致，来源、证明摘要和已完成队列前后稳定。检查时无 `IN_DOUBT`，主采集已进入下一组。记录分别在同目录 `main-resume-verification.json`、`main-progress-independent-verification.json`；整组总量不重复计入本批补录的 10011 行，全市场和剩余来源缺口继续推进。

用户确认 Tiingo 为 Starter 免费套餐。本轮 `tiingo-budget-verification.json` 于 12:01 UTC 核对客户端限额及本地账本：最近一小时 6 次、一天 340 次；滚动 31 天 79 个请求键，接收 194802686 字节，另有 58785799 字节预留，未超出本地配置预算。失败请求计数，请求键不等于不同发行人数；没有查询服务端账户用量，其他客户端用量未知。

### ABAX、AAWW 历史普通股与 BMC 来源缺口

2026-10-20 按完整历史搜索确认普通股身份，补入两段从 2006-01-03 开始的 Tiingo 原件，共 7497 行及两条永久映射。来源窗口内的日期与 SPY 实有参照日期一致。

| 历史代码 | 永久价格键 | 行数 | 最后验收日期 |
| --- | --- | ---: | --- |
| ABAX | `US000000000606` | 3166 | 2018-07-31 |
| AAWW | `US000000000604` | 4331 | 2023-03-17 |

[Abaxis 的 Nasdaq 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2018-128)确认 2018-08-01 起暂停普通股交易，并购对价每股 83 美元现金。7 月 31 日原件仍有正常价格幅度和正量；排除 8 月 1 日 OHLC 同为 83、量为零的一行。[Atlas Air 的 Nasdaq 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=eca2023-160)明确最后交易日 2023-03-17，20 日起暂停，对价每股 102.50 美元现金；原件另有 4 月 3 日 OHLC 同为 102.48、量为 1 的记录，按边界排除。两笔并购对价未生成普通现金分红或拆股。

AAWW 的[2006 年报](https://www.sec.gov/Archives/edgar/data/1135185/000095012307003924/y30610e10vk.htm)明确旧 `AAWHQ` 股份于 2004-07-27 注销，持有人未获对价。重整后的普通股先以 `AAWWV.PK` 在 Pink Sheets 报价，2005-07-14 去掉 when-issued 标记，随后使用 `AAWW.PK`，2006-05-31 才转 Nasdaq。2006 年初仍属于重整后的同一普通股；未选择搜索返回的旧 AAWHQ 永久键，也未跨旧股注销续接。正式价格使用 `US_COMPOSITE`，没有把早期 OTC 报价写成 Nasdaq 成交。2006-02-10、02-22、03-06、03-07、04-10 共五条原零量平价报价保留原值，只证明来源报价，不证明实际成交。

两份完整原件均没有拆股，调整成交量与原成交量一致；AAWW 调整 OHLC 与原 OHLC 相同，现金分红均为零。ABAX 原件有 18 笔现金分红，合计每股 3.14 美元；按未来事件的 `close/(close+divCash)` 累乘，独立复算全部调整 OHLC，与原件最大绝对差为 `4.997957603336545e-11`。这是本次供应商原件的数值核验，保持原观察版本和精度，不证明历史当时可知。

ABAX 的[2013 年报](https://www.sec.gov/Archives/edgar/data/881890/000114036113025319/form10k.htm)确认每股 1 美元特别分红，登记日 2012-12-17，支付日 12 月 28 日；来源事件日为 12 月 13 日。[2018 年 8-K](https://www.sec.gov/Archives/edgar/data/881890/000114036118019751/form8k.htm)确认最后一笔季度分红每股 0.18 美元，登记日 6 月 1 日、支付日 6 月 15 日；来源事件为 5 月 31 日。核读事实分别记录三类日期，没有增加第二次现金事件或挪动价格。

ABAX 首次发布直接通过。AAWW 原单切片运行 `market-f39e1509-9dca-4d3c-9300-51c1ddc3b370` 已确认发送 4331 行，但两分钟内未通过值及 WAL 核验而进入 `IN_DOUBT`。确认原 PID 25648 和主 PID 33868 均已退出、全部二十列在 HTTP 与 PGWire 中等于原件和冻结值、旧价格与映射保持且 WAL 结清后，只对原运行执行 `reconcile-frozen --repair-missing false`，补写零行。原超时回执保留，未重发、清锁或创建替代价格运行。

7497 行价格二十列、两条映射十八列均独立回读一致；原 137428 行价格和 23 条映射保持，保护组累计 144925 行价格、25 条映射。ABAX 的 FIGI 保留真实 null，AAWW 保留 `BBG000Q57YP0`。两条原 Yahoo `FAILED_SOURCE` 任务保持原样；按年及证券分块回读，拒绝截断。补录时锁内确认两表 WAL 结清、缓冲为零、未挂起，原物理表和结构不变。

BMC 历史搜索唯一命中旧 BMC Software 普通股 `US000000008423`，但 Tiingo 对 2006-01-01 至 2013-09-11 的请求返回 HTTP 200、两字节空数组；另用同一公共预算查询 Yahoo 的原历史代码，得到 HTTP 404。冻结原目录也没有 BMC 记录。保留两份来源回执和身份原件，没有编造价格、目录行或映射；BMC 仍是待补来源缺口。

证据在 `var/market-data/scope-review/legacy-cash-mergers-20261020/`。两份 Nasdaq 通函完整下载，其他三份发行人申报保留 HTTP 403 下载回执及独立网页核读事实，未把失败下载当作完整本地原件。`source-failure-classification.json` 只读归类停止时 233 个原来源失败任务，其中 178 个为 Yahoo 404，其余为身份、缺值、非法价格或空窗口，没有据此宣称整段历史不存在或重置原任务。

`source-row-quality-review.json` 复用其他 55 个失败任务的原件，47 份能展开完整字段数组，定位到 83198 行数值合格但仍被整段拒绝的记录。FISV、IMMP 等原件只出现少量数值缺口，可据此安排下一轮来源核对。该诊断没有新增请求、写库或推进队列；数值合格仍不等于身份、公司行动和历史覆盖通过，也没有将旧补录重复计作新行。

本轮 `tiingo-budget-verification.json` 于 12:36 UTC 核对 Starter 客户端配置及本地账本：最近一小时 6 次、一天 346 次；滚动 31 天 82 个请求键，接收 196537917 字节，另预留 58785799 字节，仍在本地预算内。失败请求计数，请求键与不同发行人数分开，服务端和其他客户端用量仍未知。

原主 PID 33868 在 2020 年窗口完成后正常退出。补录验收后仅归档本轮停止标记，以 PID 33240 恢复原队列、同一 v24 发行包、两 GiB 堆及 2026-10-16 截止日，47 个 JAR 摘要一致；启动回执为 `var/market-data/us-broad-launch-v24-cash-mergers-resumed.json`。原组 `broad-909568a1-226c-424a-bafb-df7ba0abd57c` 前十五段的运行和证明保持，只续采原未发送的六段共 155055 行。原组 128 个来源、21 段、390029 行均为 `VERIFIED`；再从原 Yahoo 响应独立重建二十列，对照 prepared、完整回读及当前 PGWire 全部一致，来源和证明摘要、已完成队列前后稳定。检查时没有 `IN_DOUBT`，主采集已继续原队列；记录在同目录 `main-resume-verification.json`、`main-progress-independent-verification.json`。原组总量不重复计入本批补录的 7497 行；全市场及剩余来源缺口继续推进。

### FISV、IMMP、BRTX 的缺值来源与早期前缀

2026-10-20 复用已冻结的三份 Tiingo 原文，补入 12255 行及三条永久代码映射；另从 BRTX 的原 Yahoo 响应补入早期 109 行，共新增 12364 行。三条原 Yahoo 全窗口任务各有一条空值日，仍保持 `FAILED_SOURCE`，不把其他来源的成功改写成原任务成功。

| 来源代码 | 来源及正式价格键 | 行数 | 已验收窗口 |
| --- | --- | ---: | --- |
| FISV | TIINGO / `US000000000280` | 5230 | 2006-01-03 至 2026-10-16 |
| IMMP | TIINGO / `US000000064953` | 3647 | 2012-04-17 至 2026-10-16 |
| BRTX | TIINGO / `US000000008591` | 3378 | 2013-05-14 至 2026-10-16 |
| BRTX 零量报价前缀 | YAHOO / `BRTX` | 109 | 2012-12-05 至 2013-05-13 |

每段窗口内的日期均与 SPY 实有参照日期一致。BRTX 两段保留独立来源和价格键；Yahoo 未复权 OHLCV 仍按其后续拆股反推，保留 `RECONSTRUCTED_FROM_YAHOO_SPLIT_ADJUSTED`，不把它伪装成 Tiingo 原价。原 Yahoo 后期空值没有补零、插值或沿用前收盘。

FISV 选择美国股票，排除同名加拿大记录。原件保留 2013-12-17、2018-03-20 两次 1 拆 2；[2014 半年报](https://www.sec.gov/Archives/edgar/data/798354/000119312514286896/d732279d10q.htm)和[2020 年报](https://www.sec.gov/Archives/edgar/data/798354/000079835421000004/fisv-20201231.htm)分别确认市场生效日，不把登记、支付日写成第二次拆股。[发行人确认](https://investors.fiserv.com/news-releases/news-release-details/fiserv-completes-listing-transfer-new-york-stock-exchange) 2023-06-07 转 NYSE 并改为 FI；[2025 公告](https://investors.fiserv.com/news-releases/news-release-details/fiserv-announces-transfer-stock-exchange-listing-nasdaq)预计 11-11 转回 Nasdaq 并改回 FISV。公告计划与实际交易证据分开，转场不产生数量拆股；Tiingo 在 Yahoo 缺失的 2025-11-12 返回完整正量报价。

IMMP 选择美国 ADS，排除 OTC 普通股 PRRUF 和澳洲 IMM。[Nasdaq 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ETA2016-333)确认 PBMD 的 ADS 比率在 2016-12-28 从 1:30 改为 1:100，表述为 1 新换 3.333333 旧；数学数量比为 0.3。Tiingo 保留其近似因子 `0.30000003` 和对应调整价格，不静默改精度。[2020 年报](https://www.sec.gov/Archives/edgar/data/1506184/000119312520253940/d53581d20f.htm)确认 PBMD 更名为 IMMP，以及 2019 普通股合股与 ADS 比率变化相抵，未新增美国 ADS 数量拆股。年报记载 ADS 从 2012-04-16 交易，Tiingo 从 04-17、Yahoo 从 04-19 开始，因此前沿仍有来源缺口，不能称完整上市历史。[2026 公告](https://www.sec.gov/Archives/edgar/data/1506184/000119312526397189/d79097dex991.htm)确认 09-28 ADS 比率 10 改 200、旧 20 换新 1，普通股数量不变；来源该日的完整报价和 0.05 因子均保留。

BRTX 的[2015 申报](https://www.sec.gov/Archives/edgar/data/1505497/000114420415063158/v423219_s1a.htm)确认 2013-04-15 旧 50 换新 1、2015-07-07 旧 20 换新 1。[2021 申报](https://www.sec.gov/Archives/edgar/data/1505497/000149315221026766/forms-1a.htm)确认 10-27 旧 4000 换新 1，当时仍为 OTC 的 BRTXD 报价，Nasdaq 上市尚在申请。[Nasdaq 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=eca2026-636)确认 2026-09-08 旧 20 换新 1，来源空值日的 Tiingo 完整报价和 0.05 因子保留。[2022 8-K](https://www.sec.gov/Archives/edgar/data/1505497/000102177123000002/form8kdtd12292022.htm)记载迁册协议签署 12-23、提交 12-29、12-31 生效；每股换一股 Nevada 普通股，没有增加数量拆股。

BRTX 的[2020 最终年报](https://www.sec.gov/Archives/edgar/data/1505497/000149315221010062/form10-k.htm)记载重整 10-30 确认、11-16 生效。审计普通股滚存为 `77851633 + 1000000 + 1515799750 + 1049726797 + 217796200 = 2862174380`，保留期初并仅列增发，支持普通股经济权益延续的推断；这不是投票结果证明，也不把拟议的有条件注销写成已发生注销。

三份 Tiingo 原件均无现金分红。用未来拆股累乘独立复算调整 OHLC，最大相对差低于 `2e-13`；全部调整成交量等于来源原量乘未来因子后向下取整。IMMP 的 96 条、BRTX 的 196 条 Tiingo 原零量报价保持原值，只证明来源报价，不证明发生交易。BRTX 的 109 条 Yahoo 前缀也全部为零量报价，不能据此宣称补齐了早期成交覆盖，不能当作有成交的 K 线计算实际交易流动性。保护组总行数包含这些原报价，原值、来源、观察时间和精度均不变，不据此证明历史当时可知；单独说明见 `single-gap-quote-coverage-clarification.json`。

FISV、BRTX 和 Yahoo 前缀直接通过原运行。IMMP 原运行 `market-0a58e628-3095-4088-8bcd-44910a9b8949` 发送 3647 行后超时；确认原 PID 33564 已退出，全部二十列与原文、prepared、HTTP、PGWire 一致，旧数据和物理表未变、WAL 结清后，用 `reconcile-complete-frozen` 只读完成原运行，补写零行。原失败回执未改。全部新增价格二十列、三条映射十八列独立回读一致；原 144925 行价格、25 条映射保持，保护组累计 157289 行价格、28 条映射。

证据在 `var/market-data/scope-review/single-gap-recovery-20261020/`，包括价格、身份决定、原运行核对及逐字段证明。两份 Nasdaq 通函完整下载，12 项发行人或交易所网页事实另行核读。Fiserv 公告第一次在本地请求间隔处暂缓，后续一次请求为 HTTP 403；两份回执均保留，未把网页核读当作完整本地下载。14:26 UTC 的本地 Tiingo 账本为最近一小时零次、一天 352 次、滚动 31 天 85 个请求键，接收 199249498 字节、另预留 58785799 字节；本批发布未新增 Tiingo 行情请求。这是客户端账本，服务端和其他客户端用量仍未知。

主 PID 6936 完成 2021 年窗口后正常停止。原组 `broad-33f33540-9951-4ce2-a057-49c86e5f7069` 前 16 段共 168047 行从原 128 份 Yahoo 响应重新独立核验二十列，旧 13 段证明保持；停下时剩余五段共 114539 行仍是原 `PLANNED`。补录后只归档本轮停止标记，以同一 v25 发行包、47 个 JAR、两 GiB 堆及 2026-10-16 截止日恢复，启动回执为 `var/market-data/us-broad-launch-v25-single-gap-resumed.json`。14:29 UTC 按 PID 和启动时刻核验恢复进程仍存活，已进入 2023 年；前 16 段的运行和证明保持、五个手动发布原运行均为 `VERIFIED`，没有 `IN_DOUBT`。这批 12364 行不重复计入原年度组；全市场采集继续。

### DDS、ABCO 与 Abcam 美国 ADS

2026-10-20 补入三份经独立回读的完整 Tiingo 窗口，共 9007 行、三条永久证券映射；三条原 Yahoo 失败任务保持原样。

| 来源代码 | 永久价格键 | 行数 | 已验收窗口 |
| --- | --- | ---: | --- |
| DDS | `US000000001493` | 5230 | 2006-01-03 至 2026-10-16 |
| ABCO | `US000000000609` | 2992 | 2006-01-03 至 2017-11-17 |
| ABCM | `US000000046432` | 785 | 2020-10-22 至 2023-12-05 |

DDS 选择 Class A 普通股，排除资本信托 DDT。Yahoo 在 2026-10-12、10-13 缺值，Tiingo 两日均有完整正量报价；来源内 5230 个日期与 SPY 实有参照日期一致。[发行人 8-K](https://www.sec.gov/Archives/edgar/data/28917/000110465926108945/tm2625597d1_8k.htm)记载计划于 2026-10-05 将主挂牌转往 TXSE、代码不变。这是转场计划，不能单独证明实际成交场所；价格仍标为 `US_COMPOSITE`，目录的 NYSE 也不改写成每根报价的场所。

DDS 原件无拆股，保留 88 笔现金事件，合计每股 119.43 美元；[发行人股息表](https://dillards.gcs-web.com/stock/dividend-history)列出 2021、2022 各 15 美元、2023 年 20 美元、2024 年 25 美元、2025 年 30 美元的特别分红及各自登记、支付日期。来源除息日单独记录，不挪到登记或支付日。[2012 10-Q](https://www.sec.gov/Archives/edgar/data/28917/000110465912080554/a12-24553_110q.htm)确认特别现金 5 美元、登记 12-07、支付 12-21；原件在 12-05 的合计为 5.05，保留原合计，不人工拆成额外事件，剩余 0.05 的季度归属没有另作已核实事实。按未来事件的 `close/(close+divCash)` 累乘独立复算全部调整 OHLC，最大绝对差 `5.005063030694146e-11`，成交量原值保持。

ABCO 的[2012 8-K](https://www.sec.gov/Archives/edgar/data/1157377/000129993312001489/htm_45373.htm)确认 06-18 收市完成 1 拆 2，来源市场生效日为 06-19；调整 OHLC、成交量均独立复算一致。[Nasdaq 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2017-74)确认 2017-11-17 并购生效、11-20 起暂停交易，现金对价每股 53.81 美元。11-17 原件仍有价格幅度及正量，窗口内日期与参照一致；没有把并购对价写成分红或拆股。FIGI 保留来源真实 null。

Abcam 的[美国发行招股书](https://www.sec.gov/Archives/edgar/data/1492074/000119312520274479/d930102d424b4.htm)明确此前美国普通股或 ADS 没有公开市场；[发行完成公告](https://www.globenewswire.com/news-release/2020/10/26/2114501/0/en/Abcam-Announces-Closing-of-Offering.html)确认 ADS 自 2020-10-22 在 Nasdaq 交易，一 ADS 对应一普通股。[Nasdaq 停牌通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2023-672)明确最后交易日 2023-12-05，当日盘后 20 点后停牌，12-06 开市前并购完成、12-07 停牌生效，现金对价每 ADS 24 美元。785 行均有正量、无分红和拆股，原及调整 OHLCV 一致，覆盖来源可验证的美国 ADS 上市期，不能补造五年历史。

同一 Abcam 永久代码的首次宽窗口响应竟从 2010-12-03 开始。上市前 2488 行保留待查，不因代码相同就认可为美国 ADS 历史；排除另外的 OTC ABCZF 及带到期标记的活跃搜索记录。本次另取 2020-10-22 至 2023-12-05 的完整响应，785 行与宽响应中对应部分逐字段相同，只用这份有界原文及其原成功运行建立映射。目录中更早的来源起点、较晚的结束日不作为美国 ADS 存续证明。

DDS 原运行 `market-73d5a3ad-af4e-4e11-a967-dacf767ee4fc` 在首批 5000 行发送后超时，尚未发送尾部 230 行。确认原进程已退出、已有 5000 行二十列与原文及 prepared、HTTP、PGWire 一致，旧数据和标识保持、WAL 结清后，用原 `reconcile-frozen --repair-missing true` 只补已逐键证实缺失的 230 行；完整 5230 行通过才完成原运行。保留原失败回执和原 5000 键，未创建替代价格运行。ABCO 直接通过。Abcam 原运行 `market-1e87cf72-2b50-45be-a787-e79f45e7f5b8` 全部 785 行发送后超时，独立确认全量字段和 WAL 后用 `reconcile-complete-frozen` 只读完成，补写零行。

9007 行价格二十列、三条映射十八列均经 HTTP 和 PGWire 独立回读一致。原 157289 行价格和 28 条映射保持，保护组累计 166296 行价格、31 条映射；本批三段均无零量报价。原物理表、原任务、原年度窗口检查点不变。证据在 `var/market-data/scope-review/retail-advisory-antibody-20261020/`，其中 `ABCM-window-identity-clarification.json` 保留前 ADS 排除说明，`DDS-cash-event-clarification.json` 区分已确认现金与未独立核实的组成解释。一份 Nasdaq 并购通函完整下载，同时保留八项网页核读事实的原网址及摘要，不把网页核读冒充本地完整下载。

15:00 UTC 核对本地 Starter 预算：Tiingo 最近一小时 7 次、一天 359 次、滚动 31 天 88 个请求键，接收 202157692 字节、另预留 58785799 字节；公开参考来源一天 100 次，达到本地日请求上限，本轮没有继续增加参考请求。失败也计数；这些是客户端账本，不能代表服务端和其他客户端的用量。

此前原组 `broad-33f33540-9951-4ce2-a057-49c86e5f7069` 的 128 个来源、21 段、282586 行已全部验收；再从每份原 Yahoo 响应独立重建二十列，对照 prepared、完整回读证明和当前 PGWire 一致，队列检查前后稳定。该整组总量不算作本批 9007 行新增价格。

主 PID 14736 在下一组 `broad-99d93f78-0a25-413b-9afb-819b147fd597` 的 2014 年窗口完成后正常停止。该组前九段 85294 行独立核验一致，停下时剩余 12 段 262165 行仍为原 `PLANNED`。补录后只归档本轮停止标记，以原 v25 发行包、47 个 JAR、两 GiB 堆、2026-10-16 截止日恢复原队列，启动回执为 `var/market-data/us-broad-launch-v25-retail-advisory-resumed.json`。15:02 UTC 按 PID 与启动时刻确认恢复进程存活，已进入 2016 年；前九段的原运行和证明保持，四个本批正式运行均为 `VERIFIED`，没有 `IN_DOUBT`。全市场采集及未证实来源继续处理。

### AATI 与待核对的 DOCOMO

随后验收 AATI 的 1517 条 Tiingo 日线及一条永久映射，价格键为 `US000000008459`，覆盖 2006-01-03 至 2012-01-10。价格二十列、映射十八列均经独立 HTTP、PGWire 回读，原 Native 直接通过、WAL 结清；此前 166296 行价格、31 条映射未变，保护组累计 167813 行价格、32 条映射。旧 DOCOMO 两份来源因缺日及分红差异均未发布，没有拼接或修补复权字段。

补录后沿原 v25、截止日和队列恢复，原年度组全部 21 段、128 个来源、347459 行已完成。前二十段 323715 行的独立核对及原证明保持；新增 2026 窗口 23744 行又从原文重建二十列，对照 prepared、原 Native 及 PGWire 一致，整组队列检查前后稳定。边界公告、原运行、DOCOMO 差异及恢复证据见 [AATI 历史验收与 DOCOMO 待核对](decisions/2026-10-20-AATI历史验收与DOCOMO待核对.md)。全市场仍在采集。

### Advent 与旧 ADC Telecommunications

再补入 ADVS 的 2394 条、旧 ADCT 的 1243 条 Tiingo 来源记录及两条永久映射，价格二十列和映射十八列独立回读一致，WAL 结清。保留 ADVS 最后一条零量报价和旧 ADCT 的一个参照缺日，不冒充完整成交覆盖；旧 ADCT 超时后只读完成原运行，补写零行。原有补录价格、32 条映射、当前 ADCT 发行人的 1613 条 Yahoo 价格及原任务均保持。来源边界、公司行动、恢复和主采集继续情况见 [Advent 与旧 ADC 历史行情验收](decisions/2026-10-20-Advent与旧ADC历史行情验收.md)。

### 固定持仓参照的核心股票验收

2026-10-21 对 2026-10-16 的 SPY/QQQ 股票持仓完成只读核对：604 条持仓、518 个代码，所选 117 个原价格运行的 2437749 行二十列均与原文、prepared 和 PGWire 一致。517 只用 Yahoo，FISV 用已验收的 Tiingo 永久价格键；所选来源首日起无 SPY 实有参照日期缺口，但这不是官方完整交易日历、历史指数成分或历史可见信息证明。17 只所选来源短于五年，不直接解释为发行人只有这段历史。

新增[只读正量报价查询](../tools/sql/us-core-holdings-2026-10-16.sql)，固定持仓日与来源，保留 `price_symbol`，应用 AMCR、SW、FER、FERG 四个已确认美国挂牌边界及 `volume > 0`。原表全部保持，查询返回 2426075 行、518 个代码，完整业务键已按年分段核验；数值仍取供应商原列。挂牌前正量报价的身份尚未逐条证实，不能把它们直接称为坏值。

原年度组的 2025 年末页回读超时后，在同一 Native 运行上只读完成，补写零行、已成功页与失败回执保持；恢复后整组 21 段、397044 行再次独立逐字段核验一致。来源边界、查询验证范围及本地证据见[核心股票来源回读与挂牌边界](decisions/2026-10-21-核心股票来源回读与挂牌边界.md)。全市场采集继续。

### ALXN 历史与 AFFX 短来源

随后补入 ALXN 的 3913 条 Tiingo 日线及一条永久映射，覆盖 2006-01-03 至已确认最后交易日 2021-07-20，二十列价格、十八列映射均独立回读一致，原 Native 直接通过、WAL 结清。两次拆股与并购对价分开核对，原保护组 173063 条价格、34 条映射保持。AFFX 只取得 2016 年的 61 行，所请求十年范围缺 2517 个实有参照日期，原文保留待补、未发布。

主采集在前 17 段、312423 行验收后正常停下，补录期间原任务与剩余四段保持，再以同一发行包和截止日恢复。边界、来源不足及全部证据见 [ALXN 历史验收与 AFFX 短来源](decisions/2026-10-21-ALXN历史验收与AFFX短来源.md)。

### 两家 ALTR 与 Analogic

再补入旧 Altera 的 2513 行、Altair 的 1858 行和 Analogic 的 3140 行，共 7511 条日线及三条永久映射。两家 ALTR 的发行人、永久价格键和历史窗口分别保存；原保护组 176976 条价格、35 条映射保持，二十列价格与十八列映射独立回读一致，原 Native 及 WAL 均通过。

此前主采集组的 128 份来源、21 段、421429 行也已全部完成独立字段核对，补录后继续以原发行包和截止日运行。代码复用、日期推定、现金事件、回执标签及原文证据见 [两家 ALTR 与 Analogic 历史验收](decisions/2026-10-21-两家ALTR与Analogic历史验收.md)。

### 核心标的增量至 2026-10-19

核心 16 个标的单独补入 10 月 19 日的 Tiingo 日线 16 条、IEX 一分钟 6240 根及其五分钟聚合 1248 根，四个原 Native 均直接通过；全部字段、五分钟聚合及 WAL 验收一致。原 79974 条核心 Tiingo 日线、1542528 条分钟原表记录的全部字段摘要保持，原队列及其 10 月 16 日截止日不改写。

正常时段视图现覆盖核心 32 条序列、今年以来 200 个开市日，一分钟累计 1248000 根、五分钟 249600 根。新日的一分钟原文有 218 根零量报价，保留来源原值；完整网格和 IEX 场所量分别说明，不能当作每分钟均有成交或全市场量。单日边界、来源包、原运行及旧数据保护见 [核心十月十九日日线分钟增量](decisions/2026-10-21-核心十月十九日日线分钟增量.md)。

恢复主采集后，原 2018 年窗口回读超时，留下未发送的 850 行尾部。经独立逐字段核对后，在原 Native 上只续接尾部，原 20850 行及队列窗口全部通过，已提交前缀重发零行。原十三个年度窗口 183264 行、核心历史与新增数据、此前补录和标识保持，随后恢复全市场采集；规则、限制和实库证据见[原冻结未发送尾部恢复](decisions/2026-10-21-原冻结未发送尾部恢复.md)。

### Arena、Aruba 与 Arotech 历史

补入 ARNA 的 4075 行、ARUN 的 2051 行、ARTX 的 3515 行，共 9641 条日线及三条永久身份映射；价格二十列和映射十八列独立回读一致，旧保护组保持。ARNA 全部发送后回读超时，在原 Native 上只读完成，补写零行。两条零量报价及 ARTX 截断拆股因子的原精度保留，收购现金未记成分红。

APOL 请求十年却只返回 236 行，保留待补，未发布。主采集在前十九个年度窗口、316273 行通过后正常停下，补录后继续原 v26、截止日和队列；原组全部 21 段、368146 行随后完成独立字段核对。来源边界、官方事实、原运行及证据见 [Arena、Aruba 与 Arotech 历史验收](decisions/2026-10-21-Arena与Aruba和Arotech历史验收.md)。全市场仍在采集。

### NFE 复权冲突

NFE 的 Tiingo 替代原文有 1939 行，但把同一次五十股并一股在 9 月 11 日、14 日各记一次，1913 条早期记录累计按 2500 倍调整。数值复算虽与原文一致，仍与官方单次事件冲突；原文、原 Yahoo 失败任务和正式表缺口继续保留，未发布价格或映射。主采集另一个 128 标的组已完成 21 段、366969 行的独立二十列验收，并继续后续任务，详见 [NFE 来源复权冲突与全市场继续](decisions/2026-10-21-NFE来源复权冲突与全市场继续.md)。

后续新组的 2007 年窗口回读未决后，确认全部 4626 行与原文、prepared、HTTP、PGWire 一致，在同一 Native 上只读完成，并核对原年度检查点；补写、重发均为零，四表 WAL 前沿不变。原前两段 8547 行及剩余十九段保持，以原 v26 和截止日恢复队列。

### ITCH 盘前连续前缀

复用原 8 MiB 压缩前缀，新补入 300836 条完整消息，正式表共 350836 条，原序号 309047 至 659882 连续。十六列、纳秒、旧 50000 条及原运行事件独立核对通过；原文和正式表重建的订单引用、5078 个成交编号及样本计算一致。范围为纽约盘前约 86.7 秒，`source_complete=false`；[只读查询](../tools/sql/us-itch-premarket-prefix-2026-06-12.sql)保留整数纳秒和执行消息的空价格。

同时修复组合来源追溯和日期审计查询，十四项测试通过，实际跨市场审计完成。核心正常时段分钟视图和加密聚合核对仍通过，日线相对 SPY 实有参照的 205 个缺日、一条参照外日期继续保留诊断。范围、原运行及证据见 [ITCH 盘前连续前缀与覆盖追溯](decisions/2026-10-21-ITCH盘前连续前缀与覆盖追溯.md)。

### ARC 并购前的美国 OTC 历史

AETUF 原 Yahoo 来源仅一条休市日记录。本次按永久代码 `US000000004783` 补入 2006-01-03 至 2026-09-01 的 5167 条日线和一条映射，全部字段独立回读通过；原 Yahoo 行、旧 194128 条保护价格及 41 条映射未变。首批 5000 行回读超时后，在同一原运行只续接未发送的 167 行，原前缀未重发，WAL 已结清。

并购完成后的 32 条原报价保留待核；2006 年缺 31 个 SPY 参照日，来源中的 166 条零量报价保留。最近五年窗口的 1255 个参照日逐日一致，供应商 `isActive` 不证明当前挂牌。[只读查询](../tools/sql/us-aetuf-pre-acquisition.sql)固定并购前窗口并标出来源正量记录。两组主采集的 367472 行及 350971 行另已独立核对，主采集继续原版本及截止日；来源边界、原尾部恢复、Starter 本地预算和验收证据见 [ARC 并购前历史与原尾部续接](decisions/2026-10-21-ARC并购前历史与原尾部续接.md)。全市场仍有未验收任务，旧覆盖快照不作为本轮最新全库总数。

### BHI、STJ 与 SIAL 退市历史

按原永久代码分别补入 BHI 2895 行、STJ 2771 行、SIAL 2487 行，共 8153 条日线和三条映射，全部字段及实有参照日期验收一致。BHI 在原 Native 上只读完成，补写、重发均为零；旧保护组保持。BHI 特别分红的来源标记日和实际支付日、SIAL 拆股的来源日和股份发放日分别保留，收购现金不补成分红。

LLTC 只返回 298 行，未达到五年要求，保留待补。主采集在原前 18 段、313128 行完成后正常停下，补录后继续原发行包、截止日及后三个年度窗口；[只读查询](../tools/sql/us-legacy-industrials-history.sql)、边界和证据见 [BHI 与 STJ 及 SIAL 历史验收](decisions/2026-10-21-BHI与STJ及SIAL历史验收.md)。全市场仍有未验收范围。

### ARRXF 与 BARUF 的五年报价

复用原失败响应，按明确五年范围补入 ARRXF、BARUF 各 1255 行，十二个年度 Native 直接验收，二十列与独立原文投影及 HTTP、PGWire 一致。本批没有新增行情 API 请求，原长区间失败任务保持；756 条零量原报价保留，不冒充每日成交或完整二十年历史。旧核心与补录价格、标识和原主运行成功前缀保持，主采集继续运行。范围、公司行动边界及[只读查询](../tools/sql/us-otc-five-year-quotes.sql)见 [ARRXF 与 BARUF 五年报价](decisions/2026-10-21-ARRXF与BARUF五年报价.md)。

### BCR、BRCD、BMS 与旧 BID 历史

补入四家原发行人的 12852 条正量日线及四条永久映射，分别覆盖 2006 年至 2017 或 2019 年，全部字段经独立回读一致。并购边界后的 23 条原报价保持待核，其中 BRCD 一条仍报告正量；没有把并购对价补成分红或拆股。旧 Sotheby’s 与当前 BID 的 64 条报价使用不同价格键，原记录保持；来源、已执行的[只读查询](../tools/sql/us-historical-b-pre-acquisition.sql)、写入保护和主队列恢复见 [四家历史发行人验收](decisions/2026-10-21-BCR与BRCD及BMS和旧BID历史.md)。全市场仍在采集。

### CA、Citrix 与 Rockwell Collins 历史

补入三个原发行人的 10696 条 Tiingo 日线及三条永久映射，价格二十列、映射十八列均与原文、prepared、HTTP 和 PGWire 独立一致，旧价格及原 49 条映射保持。旧 CA 与后来复用该代码的 ETF 分开；CA、CTXS 停牌边界后的九条原报价继续留存待核，其中两条仍有正量。COL 保留 2018-11-26 的供应商原报价，未推定当日停牌时刻；DTV 候选请求为空，仍待补。来源、已执行的[只读查询](../tools/sql/us-historical-c-pre-acquisition.sql)、正式运行和恢复证明见 [CA、Citrix 与 Rockwell Collins 历史验收](decisions/2026-10-21-CA与Citrix和Rockwell历史验收.md)。全市场仍在采集。

### APSI、BWMG 与 BSPK 的五年报价

复用三个原 Yahoo 拒收响应，按 2021-10-16 至 2026-10-16 的明确窗口新增 3765 条报价，全部二十列独立回读一致，旧价格、52 条映射和发布期间的 API 账本保持。1378 条原零量报价保留，BSPK 的 2023-01-13 反向拆股因子为 `1/45`；这些报价不证明每日成交或五年相同业务历史。原长区间失败任务保持，[只读查询](../tools/sql/us-numeric-otc-five-year-quotes.sql)单独标出正量记录。两个原年度组的 708036 行也已完成独立核对；来源筛选、DIRECTV 第二个空源、原运行和恢复证据见 [三个 OTC 普通股的五年原报价](decisions/2026-10-21-三个OTC普通股五年报价.md)。全市场仍有未验收任务。

### DIRECTV 的实际历史来源

第三个供应商价格键 `US000000012569` 实际取得并验收 2006-01-03 至 2015-07-24 的 2406 条正量日线；此前两个空源仍保留。全部二十列独立回读一致，2007 年转板、2009 年一比一换股及 2015 年并购边界另据官方报告分段说明，供应商调整基准保持。原目录场所为空，52 条既有永久映射保持，本批没有补造映射或推进原计划任务。[只读查询](../tools/sql/us-dtv-source-history.sql)固定原摘要并标出两项阶段提示；来源限制、两个指数增量及主队列恢复见 [DIRECTV 历史与十月二十日指数增量](decisions/2026-10-21-DIRECTV历史与十月二十日指数.md)。全市场仍在采集。

### Family Dollar 与 Hospira 历史

新增 FDO 2391 行、HSP 2434 行及两条永久映射，价格二十列、映射十八列独立回读一致，原价格及 52 条标识保持，新总数为 54 条。FDO 7 月 6 日的正量边界报价仍留原文待核，不宣称本批末日已证明为官方最后交易日。EMC 原有 2688 行及映射已重新核对，原文摘要与本次相同，原观察时间保持，未重复导入。另完成原年度组的 407952 行独立核对；来源边界、已执行的[只读查询](../tools/sql/us-fdo-hsp-source-history.sql)及主队列恢复见 [Family Dollar 与 Hospira 历史验收](decisions/2026-10-21-FamilyDollar与Hospira历史验收.md)。全市场仍有未验收任务。

### E*TRADE、Heinz 与 Forest 历史

新增 ETFC 3713 行、HNZ 1861 行、FRX 2137 行，共 7711 条日线和三条永久映射。价格二十列、映射十八列及已执行的 22 列查询均独立回读一致，旧价格和 54 条映射保持。HNZ 的 2006 年九个缺日保留，最近五年的 1259 行日期完整；ETFC、FRX 的两个停牌后原报价继续留存，ETFC 的九条复权成交量保留供应商取整值。原主组的 403813 行、128 个来源、21 个年度均已完整独立核验，主采集继续原队列；[只读查询](../tools/sql/us-etfc-hnz-frx-source-history.sql)、官方停牌与拆股边界、预算和证据见 [三家历史验收](decisions/2026-10-21-ETrade与Heinz及Forest历史验收.md)。全市场仍有未验收任务。

### Panera 与旧 SanDisk 历史

新增 PNRA 2904 行、旧 SNDK 2607 行及两条永久映射，价格二十列、映射十八列和已执行的 22 列查询均独立一致，映射共 59 条。旧 SNDK 使用独立永久键，当前 `SNDK/YAHOO` 的 421 行保持；两个停牌后平价报价留在原文。PETM 的 2312 条候选因最后一次现金分红与复权关系冲突继续待核，没有改写来源。另完成原组 128 个来源、21 段、364181 行的独立二十列核对，原 16 段 237723 行保持，WAL 结清。主采集使用合并后 121 项测试通过的发行包继续后续来源；[只读查询](../tools/sql/us-pnra-sndk-source-history.sql)、官方边界及证据见 [Panera 与旧 SanDisk 历史验收](decisions/2026-10-22-Panera与旧SanDisk历史验收.md)。本轮 FRED 返回维护网页，原 13072 行保持，VIX 仍到 10-19；全市场仍有未验收任务。

### Covidien、Safeway 与 Whole Foods 历史

新增 COV 1918 行、SWY 2284 行、WFM 2933 行，共 7135 条日线和三条永久映射；价格二十列、映射十八列、已执行查询二十三列均独立一致，旧价格及 59 条映射保持，映射共 62 条。十四条边界后原报价留存，其中 WFM 一条仍有正量。非现金派股、COV 一比一迁册及 WFM 十位小数复算单独说明；[只读查询](../tools/sql/us-cov-swy-wfm-source-history.sql)保留来源限制提示。另完成一组 128 个来源、21 段、356497 行独立核对，原下一组 12 段 146419 行保持，主采集继续原窗口。来源、预算、VIX 维护重查及证据见[三家历史验收](decisions/2026-10-22-Covidien与Safeway及WholeFoods历史验收.md)。全市场仍有未验收范围。

### 四段 Yahoo 原报价与年度大批次

复用四份原失败响应，新增 CBIH 3772 行、DBMM 1255 行、CUEN 3772 行、CSOC 2514 行，共 11313 条报价；分别保留十五年、五年、十五年、十年窗口，止于 2026-10-16。价格二十列及已执行查询二十二列独立一致，旧价格、全部 62 条映射、原失败任务和发布期间 API 账本保持。3230 条来源零量报价保留，Yahoo 原价重建基准、代码及业务变化和 CUEN 四次合股分别说明；[只读查询](../tools/sql/us-selected-yahoo-history-ranges.sql)固定原摘要与窗口，标出正量和范围限制，没有新增永久映射。

原 128 来源组另有 21 个年度、367131 行已完成独立二十列核对；下一组的六段 52693 行保持。补录直接通过原 Native，WAL 结清、无未决记录，主采集已按原发行包及截止日恢复。恢复后的 2012 年 13834 行也完成原文、prepared、HTTP 和 PGWire 独立二十列核对，原成功前缀和新增价格均保持。冻结诊断的计数更正、官方事实和实库证据见[四段原报价与年度大批次验收](decisions/2026-10-22-四段Yahoo原报价与年度大批次验收.md)。全市场仍有未验收任务。

### CIWV、CATV 与 BLFR 原报价

复用三个原 Yahoo 失败响应，新增 CIWV 十年 2514 行、CATV 与 BLFR 五年各 1255 行，共 5024 条原报价。价格二十列和已执行查询二十二列独立一致；2595 条来源零量保留，31 次 CIWV 现金事件另做复权阶跃诊断，普通股与 Class A 金额分开核对。[只读查询](../tools/sql/us-domestic-otc-selected-ranges.sql)固定摘要及窗口，标出正量、名称变化与 Expert Market 限制，不用最新标签证明历史身份或每天可交易。

另两个原组共 256 份来源、42 个年度窗口、781679 行完成独立二十列核对。原主队列八段 85433 行、全部队列表内容、旧价格及 62 条映射保持，本批不消耗行情 API 预算，WAL 结清。固定 SPY/QQQ 持仓仍有 518 个不同代码、2426075 条已验收正量日线。主采集已按原发行包和截止日恢复，恢复后的 2014 年 19849 行也完成全部二十列独立核对；来源事实、预算、剩余队列与回执见[三段原报价验收](decisions/2026-10-22-CIWV与CATV及BLFR原报价验收.md)。全市场仍有未验收任务。

### Dunkin 与 Duke Realty 历史

新增 DNKN 2363 行、DRE 4216 行，共 6579 条 Tiingo 正量日线和两条永久映射，映射共 64 条。价格二十列、映射十八列及已执行查询二十三列均独立一致，旧价格和 62 条映射保持。两条并购边界后零量报价留在原文，DRE 本批末日不独立证明官方最后交易日；并购现金和换股对价不补成分红或拆股。CVC 只返回六个月且有缺日，继续待核。

另完成 128 份来源、21 个年度窗口、404723 行的独立二十列验收；下一组 18 段 301486 行成功前缀保持。主采集按原发行包和截止日继续，续跑的 2024 年 28868 行也已完成二十列独立核对，原来源、旧价格、64 条映射及本批新增行保持。来源精度、DRE 截断搜索处理、Starter 本机预算、[只读查询](../tools/sql/us-dnkn-dre-source-history.sql)和实库证明见[两家历史验收](decisions/2026-10-22-Dunkin与DukeRealty历史验收.md)。全市场仍有未验收任务。

### 五十九段失败原报价的批量窗口

复用冻结失败响应，新增 59 个 OTC 窗口共 116841 行，分别保留十五年、十年、五年范围。四批价格二十列及已执行查询二十二列均独立一致；92752 条来源零量报价保持，不当作每日成交证明。第二批曾进入未决，完整键值及原发送摘要核对后，沿原运行只读恢复，没有补写或重发；后两批仅对未发送页缩页，来源与范围保持。

旧价格、全部 64 条映射、原二十段 407455 行成功前缀、队列和 API 账本保持，本批未新增行情请求。另完成一个 128 来源、21 年度、384490 行的完整独立核验。恢复后的 2026 年 25148 行也经二十列独立核对，原来源、旧值及本批新增行均保持，主采集继续下一组。CATKU、CRDE、CHEV、DJSP 四项继续待核，原长区间失败任务不改成完成；筛选、精度边界、[只读查询](../tools/sql/us-failed-yahoo-selected-ranges.sql)、原运行核对与恢复证据见[批量窗口验收](decisions/2026-10-22-失败原报价批量窗口验收.md)。全市场仍未齐备。
