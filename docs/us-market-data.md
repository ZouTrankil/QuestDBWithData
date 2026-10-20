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

同一行情目录的发布通过 `ledger-writer.lock` 串行执行，避免两个采集进程同时升级 SQLite 读事务。来源请求和配额等待在该锁之外进行。Bybit 的 HTTP 200 还必须检查 `retCode`；服务超时、服务错误和限流在任何正式发布之前有界重试，最多三次，权限错误保留为来源缺口。

全市场股票默认采用年度来源组：`broad-annual-enabled=true`，`broad-annual-symbols=128`，来源数不能超过预取缓存。完整原文和清单一起冻结，压缩及解压内容均不超过 64 MiB，单响应不超过 4 MiB；达到字节边界时按实际来源数成组。完整响应先验证所有字段并重算逐年行数，再按 QuestDB 年分区逐段发布，每段仍不超过正式十万行上限。原始 OHLCV、后续拆股、分红、来源摘要及取得时间不变；不会一次构建全部证券二十年的行对象。

关闭年度模式或关闭预取时，仍使用原版本 1 来源组，默认每组最多 12 个来源，`broad-publication-symbols` 可设为 1–16。两种 ZIP 都保留完整队列 ID、映射、URL 和摘要，每行的 `source_sha256` 指向对应原始响应，运行指纹使用整个 ZIP 摘要。

全市场日线每次发送默认最多 10000 行，规范化字节预算为 8 MiB，分别由 `broad-publication-page-rows`、`broad-publication-page-bytes` 配置。单次 HTTP 回读仍最多请求 5000 个完整键，逐字段验证覆盖整批；来源请求配额不变。自定义分页作为版本 2 写入请求参数及 prepared，恢复只能使用原冻结预算。其他入口及旧请求仍按原有 5000 行、4 MiB 执行，未决写入不自动重发。

来源组及全部成员在同一 SQLite 事务中进入 `PUBLISHING`，全部字段回读及 WAL 通过后再一起进入 `VERIFIED`。未决组阻止重新发送。原正式运行已为 `VERIFIED`、但队列尚未推进时，可用 `reconcile-broad-bundle --bundle <原组ID> --run <原运行ID>` 重新解析冻结 ZIP 并逐字段核对后推进；该命令不会重发或解开未决写入锁。旧单证券任务仍使用 `reconcile-broad-task`。

年度清单使用版本 2，同时冻结连续窗口、每年行数及发送页。`broad_bundle_windows` 先保存全部 `PLANNED`，每段发送前提交 `PUBLISHING` 意图；成功段保存原运行，来源确无记录的空年份只保存零行证据，不生成价格。所有段再沿原成功运行逐字段回读、核对原物理表及 WAL，全部通过才原子推进来源组和证券成员。

年度模式可在段间响应停止请求，状态为 `STOPPED_BETWEEN_WINDOWS`。重启时先按原冻结输入重验成功段，再仅继续未发送的 `PLANNED`；不使用当前配置重算窗口或发送页。任何原 `PUBLISHING` 段都会阻止自动继续。先处理原正式运行，再用 `reconcile-broad-window --bundle <原组ID> --window <原窗口序号> --run <原运行ID>` 核对完整原段，之后重新执行同一截止日的 `collect-broad`。这个命令只回读，不重发；不能拿另一年或部分行的成功运行推进检查点。版本 1 来源组仍沿原核对命令处理。

IEX 实测在 2026-07-03 等休市日返回整日零量平价记录；这些是供应商补齐值，不能计作真实交易时段。原文及原表全部保留，研究读取 `us_equity_intraday_regular`。日历资源在 `markets/us/src/main/resources/market-us/calendars/us-equities-2026.json`，保留官方 URL、原文摘要、休市及半日市日期。创建视图时再核对完整官方原文与资源事实；未知同名视图不覆盖。视图按纽约夏令时筛选实际开闭市时刻，只投影原值，不补造缺失分钟。当前版本只覆盖 2026 年，不能拿它推算下一年。

FRED 沿用正式 `us_market_daily` 的结构。请求先固定完整观测数量，`.` 作为来源缺值统计。补缺前保存旧行快照，实际发送前再次拒绝任何已存在的业务键；补缺后核对全部旧行未变、新行完整可见及 WAL。已有未决运行时停止，不重新抓取后覆盖原任务。SP500 的来源窗口较短时，旧表中更早的日期仍保留。

2026-10-20 实际补入 NASDAQCOM 2676 条、SP500 21 条、VIXCLS 2678 条，原有 7691 条记录全部逐字段保持不变；同一截止日重跑三组均为零新增，WAL 已结清。NASDAQCOM 来源最后有效值为 2026-10-15，16 日缺值未填造；另两组有效至 16 日。FRED 默认 HTTP/2 和应用 UA 在本机实测出现重置或超时，入口只对该来源使用已验证的 HTTP/1.1 兼容请求头。

请求预算放在 `api-budget.sqlite3`，先预留再请求；失败也计请求，未取得完整响应时保留带宽预留。来源队列的 `FETCHED` 可以继续使用同一冻结文件；`PUBLISHING` 不自动重发。SQLite 和不可变回执决定验收，状态 JSON 只用于观察。Windows 状态页被读者短暂占用时有界等待，不改变已验收结果。

BTC 的原始文件、队列、预算、账本及回执在 `var/crypto-market-data/`。对运行中的采集器，分别写入 `var/market-data/us-stop.request`、`us-broad-stop.request` 或 `var/crypto-market-data/stop.request`，会在当前完整任务验收后停止。再次执行命令继续未完成任务；未决发布仍阻止继续。

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
- [x] SPY、QQQ、IWM、TLT、GLD、USO 历史全部落库并逐字段核验至 2026-10-16；USO 自 2006-04-10，其余自 2006-01-03。
- [x] 16 只核心 ETF 和股票的当年 1 分钟及 5 分钟数据已核验至 2026-10-16，覆盖 199 个交易日；正常时段共 1241760 根 1 分钟、248352 根 5 分钟，逐日完整且独立聚合无差异。176 个来源任务的原文摘要、成功账本及 WAL 均已核对。
- [x] Nasdaq `S061226-v50.txt.gz` 前 8 MiB 的 SHA-256 与范围元数据已保留；原消息序号 309047–359046 共 50000 条已逐字段回读。真实时间 2026-06-12 08:00:00.000425736Z–08:00:05.055189073Z；这段约 5 秒的盘前数据不代表全日订单流。
- [x] Binance、Bybit 的 BTC/ETH 现货及 USDT 永续：2021-10-01 至 2026-10-18 的小时、日线已验收。556 个来源任务、368798 根 K 线；14752 个交易日的日线与实有小时 OHLCV 核对无差异，WAL 结清且无未决锁。Binance 两个现货序列各保留一个已核对的停市小时缺口，未补造价格。
- [x] 来源请求频率、配额及重试预算持久化；429 不绕过限流。用户已确认 Tiingo 为 Starter 免费套餐，继续按 Starter 上限运行。
- [ ] 各产物结构、完整业务键和值、WAL、恢复和最终覆盖报告通过。

这份清单仍在推进，不能因为两个新表已建立就判定目标完成。

全市场入口在持有原队列锁并完成规划后，启动一个后台来源预取线程。下载和前一组正式验收可以同时进行；仅预取 `PLANNED` 日线，按原请求频率、截止日和 SHA-256 校验保存为 `FETCHED`，不接触已发布、未决或范围待复核任务。正式写入仍由原线程串行执行，只有全字段回读与 WAL 验收后才推进组和成员。

`app.market-data.broad-prefetch-enabled` 默认开启；`broad-prefetch-buffer-symbols` 默认 256，允许 16 至 500 个磁盘响应。缓存满后暂停下载，消费侧等待完整来源组，来源全部取得后保留不足一组的尾部。预取异常会阻止启动后续正式发布，停止请求会等待当前正式组结束并关闭预取线程。进程重启仍先检查原 `PUBLISHING`，不会自动重发；没有增加队列状态、改变任务身份或清除失败证据。预取状态在 `us-broad-prefetch-status.json`，正式队列和状态页仍分开记录。

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
python tools/audit_cross_market_coverage.py --through 2026-10-16 --crypto-through 2026-10-18
```

结果保存在 `var/market-data/coverage-audit.json`，包括来源队列、未决锁、WAL、证券目录及持仓覆盖、分钟范围、ITCH 局部范围和加密时间网格。加密小时线另独立聚合 OHLCV 与日线对比；官方停市减计只接受完整本地证据及仍不存在的价格键。原始 UTC 满网格和扣除已核对停市后的场所网格分别报告。首尾日期或行数吻合不替代逐字段回执，也不证明美股中间交易日全部齐备。

日线另用已验收的 SPY/Tiingo 实有日期作逐日诊断参照，按年检查各证券及来源在已采集首尾之间的缺日、重复日和参照外日期。来源组须沿原成功运行核对成员摘要；未决及采集中来源不参与结论，核对前后的已验收来源范围必须稳定。这项诊断不能代替官方历史交易日历、停牌及上市退市边界，也不能将尚未取得的证券判为完成。

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
