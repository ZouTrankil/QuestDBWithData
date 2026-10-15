# 因子增量计算与多池复用（2026-10-11）

## 数据流

同一交易日读取一次带版本的源快照，生成 GLOBAL 原始因子，再按池成员选择样本。
池内去极值、标准化、中性化和评价仍各自计算，因为样本总体不同，不能直接复用另一个池的结果。
价格标签与基础暴露共享；相同样本和控制变量的投影可复用，缓存有容量上限。

`FactorPanelComputation` 负责计算；`DailyMetricPipelineService` 负责日评价；
`FactorPipelineService` 提供有限日期的计算、覆盖记录和分片发布，返回带 receipt 路径的结果。
`EtfFactorPanelComputation` 提供带类型的 ETF 计算与观察值导出入口，CLI 只处理参数与输出。
`run-factor-pipeline` 仅转换命令参数、打印结果和设置 CLI 退出状态。
Spring 正式入口直接构造应用服务，不再通过另外两个 CLI 执行计算和发布。
盘后 Batch 的 `FactorReady` 尚未接入完整原生链路，现有部分结果不能作为生产就绪证明。

## 增量边界

- GLOBAL 完整业务键包含日期、资产类型、证券、因子和定义版本。
- 发布前比较完整记录，只发送缺失或变化的行；重复执行仍回读确认。
- 大结果拆为最多 100,000 行的证据分片，每片独立保留运行标识和校验摘要。
- 源版本、计算内核、目录定义、别名规则、日期和池计划全部相同时，可复用冻结计算结果。
- 任一输入变化时重新计算当前请求日期。当前没有按每个因子的依赖精确失效，也没有跨日期的滚动状态持久化。
- 可用的部分数据允许发布，缺口保留。`sourceComplete=false` 与 `factorReady=false` 不能因为行数回读成功而改成 true。
- 进程中断后先核对原运行和物理结果；不能把观测超时当作失败后另开写入。

## 已有证据

证据目录：`artifacts/local-factor-audit/20261011/`。

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| 2026-10-08，66 个非 L2 股票因子 × 4 个池 | 299,508 条 GLOBAL 记录；264 个评价组合 | `four-pool-all-stock-observation-parity.json` |
| 四池日评价与 Python | 264 组合无差异；样本、状态和原始/有效指标按参考实现核对 | `four-pool-all-stock-python-parity.json` |
| 正式日评价表回读 | 264 条 × 39 字段一致 | `four-pool-all-stock-metric-physical-audit.json` |
| 沪深 800、中证全指成员补入 | 800 + 5,121 条；保持原有值 | `captured-index-weight-formal-audit.json` |

264 个组合中，250 个可评价，8 个为常量因子，6 个缺有效评价值；Python 的结果相同。
这些计算结果不证明历史时点可得性。新增成员来源采集于 10 月 11 日，不能倒推为 10 月 8 日已取得。

## 性能结论和范围

已有 43 因子 × 2 池的冻结输入比较：Java 热运行中位数 1.357 秒，Python 12.219 秒，约 9 倍。
该比较是日评价阶段，不含数据库、API 和发布，也不是整套因子生成的端到端倍率。
独立进程观测的峰值 RSS 为 Java 630.77 MiB、Python 338.18 MiB；当前证据支持用更多驻留内存换取计算时间，不能声称 Java 更省内存。
详细范围与限制见 `daily-multipool-performance-comparison.json`。

发布回读另有开销：混合因子页的长 OR 查询改为有候选行数上限的 IN 查询，再在内存中按完整键过滤。
候选超过 10,000 行或超过请求规模限制时，回退原精确查询；前后仍核对表版本、WAL 和重复键。
`observation-read-old.json` / `observation-read-new.json` 对同一分片四个 2,000 行页面逐字段比较。
这是局部诊断，两个进程按顺序执行，数据库缓存与并行发布负载并未控制，不应视为整体性能倍率。

随后完成同一完整 bundle 的两次运行：三片均回读通过，实际写入都为 0；第二次 `computationReused=true`，
复用的 manifest 摘要相同。三片发布校验合计从 1,179.9 秒降至 60.8 秒。
这是本地顺序运行的发布阶段观测，不包含计算复用检查及最后的文件校验，也不代表 Python 对比。
详见 `native-factor-bundle-reuse-comparison.json`。
发布核心已提取为 `FactorObservationPublicationService`，CLI 保留兼容入口。
应用服务正式入口的再次运行也完成：复用计算、299,508 行回读、增量写入 0；
正式表事务版本与上次逐字段全量审计一致，见 `native-factor-application-service-audit.json`。
结果仍为业务 `PARTIAL`，CLI 的非零退出用于表达来源未齐全，不能当作无写入或就绪的判据。

ETF 服务提取后用相同历史窗口（2025-07-05 至 2026-10-08）重新计算：面板除耗时与代码摘要外完全一致；
30 个因子的 52,442 条观察值与已有完整观察值加增长因子修复结果逐字段一致。
证据为 `etf-native-service-panel-parity.json` 和 `etf-native-service-observation-parity.json`。
该次仅计算和导出本地文件，没有发布到正式表。

ETF pipeline 已增加冻结计算复用：日期、历史窗口、目录、别名规则、计算代码、面板摘要、
观察值摘要及九组源状态必须一致；原生 backtest 视图身份和 WAL 也重新检查。
还会从保存的面板重新生成观察值做完整比较，而非仅相信状态文件。
旧 receipt 缺少这些证据时重新计算；异常中断继续使用原来的显式 resume 路径。
ETF 输入指纹使用 canonical mapper 排序字段，避免不同 JVM 的 Map 迭代顺序影响摘要。

实际连续两次运行同一日期：第一次计算，第二次 `computationReused=true`，
面板、观察值摘要与原计算时间全部保持一致；两次均验证 52,442 行，实际写入均为 0。
独立物理回读确认 30 个因子的所有记录一致、无多余键。
证据：`etf-incremental-reuse-audit.json`、`etf-incremental-reuse-physical-audit.json`。
7 个 L2 依赖因子仍为空，复用不会将 `sourceComplete` 或 `factorReady` 提升为 true。

## 后续顺序

1. 已完成三分片 pipeline 原运行回读：299,508 行一致，增量写入 0。
2. 已编译回读优化，完整重跑确认计算复用、299,508 行回读和零重复写入。
3. 已将 CLI 编排下沉为应用服务；继续通过模块边界接入完整盘后任务。
4. 保留缺口状态：10 月 8 日 L2 源缺失、历史来源凭证不足；补齐后重跑对应日期。
5. 扩展同输入的因子生成端到端计时，再决定按依赖失效、滚动窗口状态等进一步优化。

Java 旧发布账本恢复继续暂缓。

## 最新交易日推进至 2026-10-09

实际发布并独立逐字段回读：股票 66 因子 299,574 行、ETF 30 因子 52,541 行、市场 10 因子 10 行，
合计 352,125 行。股票四池仍共用计算，264 个池/因子组合；投影构建 50 次、复用 154 次。
股票各因子均有有限值，但这不代表每只证券都有值。ETF 的 7 个 L2 因子继续保留缺失结果。

10 月 8 日已有的 299,508 条股票和 52,442 条 ETF 记录重新逐字段核对，保持原值。
证据汇总：`october9-factor-publication-summary.json`，各资产回读见其中的 proofFiles。

ETF 首次使用固定历史起始日时超过 460 天读取上限，在计算前终止且未启动发布；
将历史起点随目标日期滚动至 2025-07-06 后完成发布，保留原失败记录。
市场计算首次因股票发布期间观察值 WAL 未稳定而停止，确认其终止后，在股票发布结束时重试成功。
所有写入任务均保留原运行标识，没有因等待超时另开写入。

本地交易日历显示 10 月 9 日的次一交易日是 10 月 12 日；截至 10 月 11 日标签尚未成熟，
本次未生成 10 月 9 日的次日收益评价。所有新发布仍为 `PARTIAL`，没有提升来源完整性或历史 PIT 就绪状态。

## 融资融券缺口重试

2026-10-11 再次通过正式 Java 入口补跑 10 月 9 日：接口仍只返回沪市 2,001 行，深市和北交所均为 0。
运行 `margin-detail-1bc63318-28ef-4414-9925-9de9042aee48` 为 `PARTIAL`，2,001 行全部回读通过。

`MarginDetailWritePort` 现先在共享运行器的日期锁内比较完整键和值，只发送缺失或变化行；
共享执行器随后仍验证完整请求页。无状态 mapper 在编码器中共享。
本次 9 个批次全部命中已有相同记录，实际写入 0；独立审计确认来源所有字段一致，
当天记录和正式表代际、事务版本均未变化。证据：`margin-october9-refresh-audit.json`。
本次运行验证了无变化路径；没有用人为修改正式数据的方式验证新增或修订路径。

## 与 Python 盘后编排的对应关系

已查阅 Python `scripts/pipeline/run_post_close_factor_data.ps1`、
`src/quant_platform/workflows/data/factor_sync.py` 和
`src/quant_platform/workflows/research/factor_data_product.py`。

Python 先等待数据基础阶段，再执行股票增量刷新、ETF 刷新，最后等待数据库可见性。
恢复时允许复用已完成的股票发布或整个发布，但仍重新检查物理可见性。
`FactorReady` 因而包含股票与 ETF 产品；股票的 L2 刷新仍是依赖，不能用 66 个非 L2 股票因子的结果替代完整阶段。

Java 目前还存在两个接入缺口：

- Batch 的 `DataReadiness` 验证隔离目标 `jdb_test_*` 的来源证书，尚不是本地正式表同步的就绪适配器。
- `FactorReady` / `FactorMetrics` 仍经 external 路径调度，默认命令配置为空。应用服务可直接调用，并不等于 Batch 已配置完成。

上面两项专指通用 `post_close` 图，不能推广为所有盘后任务都没有 Java 实现。
`main-strategy-runtime` 中的 `main_strategy_daily` 已有正式 Java 源 owner、派生表、L2、策略评分和输入包链路。
其 `ScoreInputs` 调用 `MainStrategyDataService.prepareScores`，直接组合原始输入与已批准权重计算，
与通用 GLOBAL 因子产品刷新是不同入口。运行就绪需要分别检查。

新增只读入口 `inspect-main-strategy-data`（通过根项目 `:run` 调用）用于核对当前配置与原生接线，
不启动 Web 调度，也不生成评分、计划或补数据。输出的 `nativePipeline` 是当前 CLI 实例状态，不代表另一个服务进程。
检查中修正了主策略构造器两个 final 服务参数上的 `@Lazy` 类代理问题；通过实际 Spring 实例化复核。

当前 `main-strategy-current-inspection.json` 显示：配置中的 `promoted_recipe.json`、`accepted-sha256.json`、
`main_strategy_plans.sqlite3` 均缺失，没有 Java `score_panel`，目标 ETF 配置为空。
已搜索两个项目的 artifacts 目录，只找到测试用摘要和计划库，不能作为正式材料接入。
正式路径已向用户询问。该检查成功只证明接线与只读入口可运行，不证明生产策略可运行。

`formal-source-latest-run-audit.json` 记录本地正式同步账本近期任务的运行状态和请求覆盖日期，
不把账本记录当成重新验证过的物理证明。例如 daily 的逻辑运行日为 2026-10-11，实际请求区间为 10 月 8—9 日；
正式就绪适配器必须核对请求区间、切片来源证据和当前物理表，不能只匹配逻辑运行日。

后续接入应使用正式表的实际来源证据，组合股票、L2、ETF 产品的结果。
缺数据时应保留已完成分项、日期和具体缺口，不能把程序退出或部分行已发布当作全阶段成功。

## Python 日评价与历史统计边界复核

已核对 `workflows/data/factor_daily_metrics.py`：按成熟的 signal/maturity 日期对处理，
完整物理 checkpoint 可复用；逐池数值比对与覆盖判定后发布日指标并回读。
该入口没有调用历史滚动统计发布。
Python 引擎另提供 `build_history_statistics`，窗口为 5/20/60/252 日，
并有 SQL 生成器；本次不能把这些查询能力计作已经迁移的 Java 功能，
也不能据此断言日评价 writer 缺少必需的滚动写表阶段。

后续性能验证仍需补充同一冻结输入、相同因子/证券/历史窗口的完整因子生成比较，
分别记录冷启动、预热计算、峰值内存、读取与发布耗时；现有约 9 倍结果仅适用于日评价。

## 当前冻结输入的共享日行情生成性能

新增可重跑基准：`freeze_generation_input.py`、`GenerationKernelBenchmark.java`、
`generation_python_benchmark.py`、`generation_benchmark_compare.py`，均位于本次审计目录。
冻结 2025-07-06 至 2026-10-09 的 1,672,764 行、5,605 只历史证券；输出日 5,572 只。
输入 SHA-256：`eba1f1be0334f4a3c78778747692d88b37ac851b2afe4218f522b9220fdde8cf`。
采用 Python 的别名和上市范围处理后，两边读取同一 JSONL。来源状态和输入摘要留档，
此文件只用于计算基准，不充当视图身份或生产就绪凭证。

三轮生成计时：Java 首轮 2.035 秒、后两轮中位数 1.488 秒；
Python Polars 首轮 8.816 秒、后两轮中位数 2.470 秒，局部热运行比值约 1.66。
Python 当前接口生成全历史并转 pandas，Java 内核保留指定输出日；
包含各自这些输出处理成本。运行顺序、CPU/OS 缓存未控制，Java 峰值 RSS 未采集。
该范围不含金融、融资融券、L2、ETF 因子、池内处理、观察值转换及发布，不能当作整套 66 因子的端到端倍率。

严格逐列比对：67 个共有列中 66 个通过；`amount_mean_3` 有 3 个值超过固定绝对容差 1e-10。
冻结输入证明这三只证券最后三天成交额均为 0，Java 精确返回 0，Python 留下约 1e-10 的滚动残差。
保留 strictParityPassed=false，不修改整体容差或生产计算去追随这个残差。
详见 `generation-benchmark-comparison.json` 和 `generation-zero-window-differences.json`。
新增证据表明生成阶段已有加速，但倍数明显小于此前日评价约 9 倍；还需要完整 pipeline 的逐阶段计时。

## ETF 编排下沉为应用服务

新增 `EtfFactorPipelineService`，将 run、冻结产物复用、发布和显式 resume 从 CLI 提取。
调用方传入类型化 Request，取得 Result（receipt、业务状态及 observationsVerified），
不依赖 CLI 输出或异常判断业务成功。observationsVerified 同时检查发布状态与完整预期行数；
它不代表 sourceComplete 或 FactorReady。CLI 保留原命令以及部分完成时的非零退出行为。
日期锁、发布 runId 预先落盘、摘要校验、resume 前置核查和异常中断 receipt 保持原逻辑。

编译通过：`etf-pipeline-service-compile.log`。
实际 10 月 9 日运行 `2026-10-09-d8bb53d2-97be-46de-8676-fa5357692738` 已终止；
发布 `factor-observation-c0dff3d4-b0c1-4f79-9ac7-8e9d23d3ca2b`：
计算复用、52,541 行验证、deltaRows=0，业务 PARTIAL。
独立再次物理回读 30 个因子全量一致、无多余键，
证据 `etf-pipeline-service-physical-audit.json`；完整日志 `etf-pipeline-service-live.log`。
本次实际覆盖 run/复用路径，未实际触发 resume；恢复逻辑提取后仍需有针对性的运行核查。

这完成了调度器直接调用 ETF 服务的前置工作；通用 post_close 的正式来源就绪适配、
股票/L2/ETF 组合和阶段接线仍待完成，没有将当前 PARTIAL 配置为 FactorReady。

## ETF 恢复与股票/市场混合复用

已实际执行 ETF 应用服务提取后的 resume：
`factor-observation-8b453ace-454b-4672-828c-41489eeebd00` 绑定前次发布
`factor-observation-c0dff3d4-b0c1-4f79-9ac7-8e9d23d3ca2b`，冻结证据摘要一致，
52,541 行全部验证并复用，pages=0、deltaRows=0，保持 PARTIAL。
证据：`etf-pipeline-service-resume-audit.json`、`etf-pipeline-service-resume.log`。
这验证了完整已发布结果的恢复复用路径，不是人为制造 IN_DOUBT 后的恢复演练。

修复 `FactorPipelineReuse` 仅支持纯股票的限制：混合股票/market_state 请求现在以股票子集
匹配池身份，并检查完整请求定义快照、bundle 因子顺序及日期；源状态前后复核保留。
计算报告增加 requestedDefinitions；旧报告缺少快照时重新计算。

实际以 `market_beta,market_amount` 和四个股票池连续运行两次：第一次计算，第二次复用，
4,540 行观察值、bundle 摘要一致，两次增量写入均为 0。
独立物理回读两个因子所有值一致、无多余键。
证据：`mixed-factor-reuse-audit.json`、`mixed-factor-reuse-physical-audit.json`。
本次覆盖两因子的混合路径，不表示完整 76 因子混合请求已重新验证。
编译日志：`mixed-factor-reuse-compile.log`。通用 post_close 阶段接线仍未完成。

## L2 计算应用服务迁移

新增 `L2FactorPanelComputation`，将 L2 读取、共享计算、池处理、来源核查和证据导出
从 CLI 下沉到应用层。原 `compute-l2-factor-panel` 命令保留，CLI 仅解析入口并输出结果。
应用层不再依赖 `FactorPanelCommands`，直接调用 `FactorPanelComputation` 核对来源状态。
导出时计算时钟统一为 Asia/Shanghai，与其他因子服务一致；本次未实际导出/发布 L2 观察值。

编译通过：`l2-panel-service-compile.log`。
实际用原 2026-09-23 至 2026-09-30 输入，重算 9 月 30 日全部 125 个 L2 因子：
39,597 条输入，986,000 个输出值，与原面板完全一致（仅排除耗时、代码身份）。
来源状态也相同。证据：`l2-panel-service-parity.json`、`l2-panel-service-live.log`。
该验证覆盖原始共享 L2 计算路径，未覆盖本次提取后的池/导出分支。
仍为 sourceComplete=false、publicationReady=false；历史计算一致不补齐最新交易日来源。

通用 post_close 默认 DataReady 仍检查隔离 jdb_test 来源，外部因子命令配置仍需要正式接入。
当前主策略调度的 cron 默认 20:35、21—23 点每 15 分钟重试，仅属于 main_strategy_daily。
配置存在不证明对应 Web 进程或调度器已经启动，不能据此声称本地盘后自动运行正常。

## 本机盘后运行状态核查（2026-10-11 21:35）

`local-post-close-runtime-audit.json` 记录只读 Windows 运行状态：
QuestDB 服务 Running / Auto（PID 5820），其 Java 子进程 PID 6572 监听 9000/8812；
另一个可识别 Java 进程是 Gradle daemon。
按任务动作搜索 back-monitor、data 工作区、QuestDataApplication、post_close、factor_sync，
未找到匹配的 Windows 计划任务；按已知路径搜索服务仅找到 QuestDB。
应用默认 APP_PORT=8080，8080/8081 未监听，默认 `var/main-strategy-batch.sqlite` 不存在。

这些证据没有证明主应用/盘后调度正在运行，不能把“数据已迁移、CLI 已运行”当成自动盘后已恢复。
检查仍有边界：自定义端口、通过其他包装器间接启动、其他机器的调度不在本次证据范围。
正式部署验收还需确认启动进程、实际配置、调度器状态、任务触发记录和业务阶段证据。
现有 Web 默认启动追赶；正式主策略材料缺失和通用 post_close 接线缺口需要先解决。

## L2 分片发布应用服务迁移

新增 `L2ObservationPublicationService`，原 CLI 的分片顺序发布、持久化尝试记录、
前次运行状态核查和逐片物理验证下沉到应用层；CLI 保留原命令和兼容构造入口。
结束时新增整包摘要、总行数复核，避免发布期间包改变却标记所有分片已验证。
应用服务返回 receipt 与业务状态，仍保持来源不完整的 PARTIAL。

编译通过：`l2-publication-service-compile.log`。
实际 Spring CLI 只读检查原冻结包通过：125 因子、6 分片、567,375 行，
manifest SHA `51c0e51379e7519038dac98e5fbdf5c37bb656d8de2b53531aa830f9050e466b`。
证据：`l2-publication-service-inspect.log`。本次没有运行提取后的写入分支，
不能将只读包检查当作新发布逻辑的运行验收。

股票、ETF、L2 的计算及观察值发布现均已有应用服务入口。
下一项仍是组合这些服务并接入正式来源 DataReady 与通用 post_close，
不是继续把各个 CLI 运行成功当成整条盘后自动任务完成。

## 正式来源候选按数据区间查找

`SyncRunLedger.coveringHistory` 增加按 jobId、准确 targetId、实际数据日查候选记录的只读接口，
使用冻结请求 from/to，保留有界 keyset 分页；不按逻辑运行日过滤，不自动过滤或提升业务状态。
用途是正式 DataReady 适配器选择待核查的来源批次，之后仍须验证分片来源、完整性和物理表。

编译通过：`formal-source-candidates-compile.log`。
实际只读融资融券账本核查：10 月 9 日找到 3 个候选，包含逻辑运行日为 10 月 11 日的补跑；
两条一页遍历结果与独立解析冻结日期的全作业浏览一致，无重复；错误目标身份返回空。
证据 `formal-source-candidates-audit.json`、可重跑工具 `FormalSourceCandidateAudit.java`。
此处 physicalReadinessVerified=false，未把融资融券 PARTIAL 或迁移数据提升为就绪。

## 正式来源身份兼容性核查

当前代码及只读账本确认两种身份规则并存：
`DailyQuestDbTarget` / `DailyBasicQuestDbTarget` 使用配置 host、PG/QWP 端口、database 和物理表身份生成 questdb-*；
其他许多静态 owner 经 StaticTargetIdentity 使用实际 PG URL、表 id 与 directory 生成 static-v2-*。
本地 data.daily / data.daily_basic 各 1 次运行属于前者；etf_daily、margin_detail 属于后者。
完整清单见 `formal-source-target-identity-inventory.json`。

因此正式来源适配器必须从对应 owner 获取目标身份，不能以统一 static-v2 重算并比对所有旧批次，
也不能因表值一样就把不同身份或端点自动视为相同。`coveringHistory` 的精确 targetId 约束保留。
`VerifiedSliceRecovery.verifyWhole` 已提供完整来源重新拉取及逐键物理回读能力，
但只接受已完成运行，而且要求原冻结请求与目标身份完全匹配；PARTIAL 不可伪装为完整来源。
正式接线应分别处理完整复用与部分来源补跑，仍需按 owner 提供适配。

## daily / daily_basic 完整来源复核入口

新增 `revalidate-daily-source --dataset daily|daily_basic --run RUN_ID`。
DailyJobService 补齐完整复核入口；daily_basic 原有入口支持无父组调用并改用只读账本。
`FrozenRunRequest.restoreCompleted` 只允许 VERIFIED / VERIFIED_EMPTY，
原 resume 仍只允许 FAILED / CANCELLED / PARTIAL，恢复与完整复核的状态准入保持分离。
复核使用原冻结请求、各 owner 的准确目标身份，重新取源并逐键回读，不发送数据库写入。
它会生成新的本地来源/回读证据文件。

本地实际调用两个原完成批次，均成功退出：
- daily-eb0f3768-a2a9-46ee-a104-0618724e497b：11,116 行，2 页、2 组回读。
- daily-basic-5cd0c251-e933-42f4-986a-b06a8a83320e：11,116 行，2 页、2 组回读。
覆盖原 10 月 8—9 日数据区间，当前接口来源与正式表完整匹配；databaseRowsWritten=0。
证据摘要：`daily-source-revalidation-summary.json`，包含两个实际 receipt 路径与 SHA。
编译日志：`daily-revalidation-final-compile.log`。
这只完成两个来源的复核能力，未宣称所有 DataReady 来源或通用 post_close 已就绪。

## ETF 行情 / ETF 技术指标 / 涨跌停完整复核

新增 CompletedSourceRevalidation 共享辅助器，按 owner 的目标身份与适配器重新取源并逐键回读；
只恢复 VERIFIED / VERIFIED_EMPTY 原冻结请求，使用只读账本，复核过程反复检查目标身份。
EtfDailyJobService、EtfFactorJobService、StockLimitJobService 已提供 revalidate(runId)。
新增命令 `revalidate-factor-source --dataset daily|daily_basic|etf_daily|etf_factor|stk_limit --run RUN_ID`。
CLI 默认验收表策略保留：正式 ETF/涨跌停复核需要显式 APP_SYNC_ETF_DAILY_TABLE、
APP_SYNC_ETF_FACTOR_TABLE、APP_SYNC_STK_LIMIT_TABLE 绑定正式表，并使用原端点身份。

实际复核：ETF daily 4,316 行（包括恢复过的批次）、ETF factor 4,316 行、stk_limit 11,303 行，
均来源重取和正式表回读通过，未写数据库。汇总 `etf-limit-source-revalidation-summary.json`。
编译 `etf-source-revalidation-compile.log` 通过。
首次 ETF daily 未绑定正式表时正确拒绝；首次 stk_limit 与 ETF factor 跨进程并发时，
凭据预算独占锁拒绝请求，任务终止。确认 ETF 结束后串行重跑 stk_limit 成功；未绕过凭据锁。
盘后组合应串行调度这些取源操作，不能把同凭据来源进程当作可独立并行的任务。
此处仍只是五个来源的复核入口与部分日期实际验收；通用 DataReady 与 post_close 接线未完成。

## 统一正式来源就绪检查入口

新增应用服务 `FormalSourceReadinessInspection` 与组合模块 CLI：
`inspect-formal-source-readiness --date DATE [--revalidate true|false]`。
必需集合直接使用 Batch `DataReadiness.REQUIRED_SOURCES`（15 项），避免单独维护缩减清单。
默认只读，按 owner 的真实目标身份和冻结数据区间选候选；只有明确 revalidate=true 才串行重取来源。
当前接入 daily、daily_basic、etf_daily、etf_factor、stk_limit；未接入项逐项输出 ADAPTER_NOT_CONNECTED。
结果始终明确 dataReady=false / certificateIssued=false，此入口是诊断和复核，不冒充完整阶段证书。

实际 2026-10-09 的只读检查与串行复核均成功退出：5 项 SOURCE_REVALIDATED、10 项未接入，
数据库写入 0 行，没有凭据锁冲突。结果保留各实际复核 receipt。
证据 `formal-readiness-inspection.json`、`formal-readiness-revalidation.json`；
编译 `formal-readiness-inspection-final-compile.log` 通过。
CLI 正式目标仍需要显式对应表配置；只有 Web 默认绑定正式表。
后续把剩余来源 adapter 接齐、生成可重新核查的完整阶段证书，再接入 post_close 调度。

## ETF 复权与持仓适配、正式复权补跑

EtfAdjJobService / EtfPortfolioJobService 新增 completed-source revalidate，
并接入统一正式来源检查，已注册适配器从 5 项增至 7 项。
编译 `formal-readiness-etf-extended-compile.log` 通过；只读实跑识别两者原先均为 NO_COVERING_RUN，
而非未接入。结果 `formal-readiness-etf-extended.json`。

随后明确绑定 etf_adj 正式表，通过 BACKFILL 计划并运行 2026-10-08 至 2026-10-09，
逻辑日 2026-10-11；`etf-adj-08e2d3e3-d368-4aac-978a-573fc97234ce` 正常结束，
来源 4,367 行、验证 4,367 行、VERIFIED。日志 `etf-adj-formal-plan.log` / `etf-adj-formal-run.log`。
这是实际正式同步（非只读复核），没有将 sourceRows 当作新增行数；未声明整个历史表未变化。
持仓仍缺对应日运行证据，新增两个 adapter 的 revalidate 分支尚未实际执行。

ST 日表不能直接套用通用辅助器：StockStDailySyncAdapter.fetch 包含 staging prepare 和整表发布逻辑，
并要求发布上下文。必须提供单独无数据库发布副作用的来源取数适配器，再接入完整回读，
不能给它补一个普通 revalidate 方法就声称只读。这项保持待办。


## ST 专用无发布副作用复核适配

新增 StockStDailyRevalidationAdapter：重取年度 namechange 来源、重开原始证据逐字段比较、
冻结交易日校验、已完成分片回读、全日期窗口指纹比较（含空日期与多余键）和物理表代际前后检查。
不调用 staging、publication 或 send；通用 CompletedSourceRevalidation 的工厂契约明确禁止 fetch 改库。
StockStDailyJobService 已暴露 revalidate，统一入口已接入该 owner，注册数增至 8/15。
编译 st-source-revalidation-compile.log 通过。实际串行复核仍以终态结果为准，不能以编译或运行中证据认定完成。

实际统一入口运行已正常终止：ST 556 行、2 页来源/物理回读和完整窗口匹配通过。
同时新接入 ETF 复权的 revalidate 分支通过。共 7 项 SOURCE_REVALIDATED，
ETF 持仓为 NO_COVERING_RUN，另 7 项未接入；仍不签发 DataReady。
ST 年度来源 2010—2026 持续按凭据配额串行取得，等待期间未启动第二个任务。
证据 `st-source-revalidation-result.json`、`st-source-revalidation-live.log`；
前者引用 ST 实际回读 receipt。此次统一复核数据库写入 0 行，本地新建取源/复核证据文件。

## 普通资金流与融资融券就绪适配

MoneyflowJobService 和 MarginDetailJobService 新增 completed-source revalidate，并接入统一检查，
已注册 10/15 来源。复用各自无发布副作用的取源/回读适配器；margin 的来源完成度判断保持原样，
完整复核不接收 PARTIAL。增量补齐仍走原 run/resume，不被此检查替代。
编译 `flow-margin-readiness-compile.log` 通过。

只读实际检查 2026-10-09：margin_detail=NO_COMPLETED_CANDIDATE（保留部分来源候选），
moneyflow=NO_COVERING_RUN。证据 `flow-margin-readiness-result.json`。
两项新增完整 revalidate 分支尚无已完成覆盖批次可用于本轮运行验收；不宣称验证通过。
剩余未接入为 cn_bond_yield_curve、exchange_calendar、moneyflow_hsgt、stk_factor、stk_suspend。
其中停牌和沪深港通适配器也包含发布流程，需专门拆出只读复核路径，不能直接套用通用辅助器。

## 股票技术指标来源复核接入

StockFactorJobService 新增 completed-source revalidate，复用无数据库发布副作用的
StockFactorSyncAdapter，注册到统一检查，现已接入 11/15 来源。
定向编译通过，证据 `stk-factor-revalidation-compile.log`。
实际执行 2026-10-09 只读检查并正常退出，证据 `stk-factor-readiness-result.log`；
本轮未执行该来源的重新取源复核，不把候选批次识别等同于复核通过。
剩余未接入：cn_bond_yield_curve、exchange_calendar、moneyflow_hsgt、stk_suspend。

## 国债收益率与交易日历复核入口

CnBondYieldCurveJobService 与 ExchangeCalendarJobService 新增 completed-source revalidate，
统一来源检查已注册 13/15 项，剩余 moneyflow_hsgt、stk_suspend。
国债复核保持原冻结日历与交易日列表；以当前窗口摘要进行并发变化预检，
随后重新取源并按原 checkpoint 与当前完整日期键值回读。不能使用原写入前摘要复核已完成结果。
复用的 fetch 不发送数据库写入，只有原作业 runner 的消费端才写库；复核消费端只读。

两次定向编译通过，证据 `bond-revalidation-compile.log`、`calendar-revalidation-compile.log`。
两次实际只读检查正常退出：2026-10-09 国债与日历均为 NO_COVERING_RUN，
证据 `bond-readiness-result.log`、`calendar-readiness-result.log`。
这表示当前物理目标没有覆盖该日的作业凭证，不表示表内无数据；新增取源复核分支尚未实际运行。
本轮检查只绑定对应来源，其他来源可能使用 CLI 默认验收表，不能据此覆盖之前的正式表检查结论。
定向 git diff --check 通过；未运行广泛测试，未写入数据库。

## 沪深港通只读复核

新增 MoneyflowHsgtRevalidationAdapter，复用原来源分页和交易日覆盖规则，
读取当前正式表的物理身份，在每个窗口前后检查身份，比较窗口全部键值，包括空源下的残余目标数据。
原冻结物理身份是发布前的表，复核绑定当前正式表；逻辑目标仍严格匹配原冻结请求。
不创建 stage、不调用 useStage、send 或 publication；通用恢复消费端只做原 checkpoint 回读。
MoneyflowHsgtJobService.revalidate 接入统一来源检查，现已注册 14/15 项，仅停牌未接入。

定向编译 `hsgt-revalidation-compile.log` 通过，定向 diff --check 通过。
实际只读检查 2026-10-09，moneyflow_hsgt 为 NO_COVERING_RUN，进程正常退出：
`hsgt-readiness-result.log`。没有可覆盖的完成批次，因此新增取源复核分支尚未实际验收。
其他来源的默认 CLI 表绑定不用于替代此前正式表检查结论。本轮无数据库写入。

## 全部来源接入与正式表实测

新增 StockSuspendRevalidationAdapter，仅重新取源并回读当前物理代际，比较完整窗口
（含空日期残余行），不创建临时表、不追加、不发布。来源复核入口现已接齐 15/15。
`suspend-revalidation-compile.log` 定向编译通过；定向 diff --check 通过。

全部对应 CLI 目标显式绑定正式表后，串行执行 2026-10-09 的来源复核，进程正常退出。
证据 `all-formal-readiness-revalidation.json` / `.log`：
- 9 项 SOURCE_REVALIDATED：daily、daily_basic、etf_adj、etf_daily、etf_factor、stk_factor、stk_limit、stk_st_daily、stk_suspend。
- 5 项 NO_COVERING_RUN：cn_bond_yield_curve、etf_portfolio、exchange_calendar、moneyflow、moneyflow_hsgt。
- margin_detail 为 NO_COMPLETED_CANDIDATE，仍保留部分数据候选。
停牌新增路径实际复核 2 页、29 行通过；股票技术指标新增路径亦实际通过。
本轮数据库写入 0 行，不签发 DataReady。缺少覆盖凭证不等于表内无数据。
下一阶段须补齐来源窗口证据，以及将正式来源和 Java 因子服务接入通用 post_close；
其现有 DataReady 仍要求 jdb_test 目标，FactorReady 仍由外部执行器承接，尚未完成正式链路。

## 交易日历增量写入与正式凭证补齐

ExchangeCalendarWritePort 在发送前读取本批次完整键值，拒绝重复目标键，仅发送变化行；
相同数据直接返回，后续 runner 仍验证完整来源行。CODEC 的 ObjectMapper 改为私有共享实例。
`calendar-delta-compile.log` 定向编译通过。
正式执行 SSE/SZSE、2026-10-08 至 2026-10-09 BACKFILL：
`calendar-4724ea3f-f7d9-4cd8-bf55-be0d3e79c0f6` VERIFIED，sourceRows=verifiedRows=4。
证据 `calendar-formal-run.log`。独立 HTTP 窗口前后均为 4 行且全部字段一致，
见 `calendar-window-before.json`、`calendar-window-after.json`、`calendar-window-comparison.json`。
4 是来源及回读行数，不是新增写入行数；未观测底层发送计数，不以此宣称实测零发送。
该日期的日历完成凭证已补齐，尚未重跑全部来源复核。资金流来源凭证仍待补齐。

## 普通资金流增量写入与补数

MoneyflowWritePort 改为发送前按完整键值比较，只提交缺失或变化行；重复物理键拒绝继续。
无差异时直接返回并标记无活动 sender，后续完整回读仍执行。CODEC 的 ObjectMapper、
无状态 MoneyflowMapper、金额字段列表共享，避免逐行重建。
`moneyflow-delta-compile.log` 编译通过，定向 diff --check 通过。

正式 BACKFILL 2026-10-08 至 2026-10-09：
`moneyflow-21c76bc1-e4d3-43cb-89a3-cd2cd5c6581f` VERIFIED，来源及回读 11145 行，
证据 `moneyflow-formal-run.log`。独立 HTTP 查询确认运行前两日为 0 行，运行后共 11145 行；
完整快照 `moneyflow-window-before.json` / `moneyflow-window-after.json`，按日数量 `moneyflow-window-counts.json`。
本次实际补入数据，不能称只读；未单独运行全重叠重试，差异跳过分支尚缺运行验收。
两日资金流完成凭证现已补齐。因子输入因此变化，后续必须按新来源指纹重新判断缓存是否可复用。

## 资金流依赖核对与情绪表刷新

核对 Python `research/adapters/market_sentiment_daily.py` 与 Java MarketSentimentDailyStorage/JobService：
普通资金流直接用于净流入及大单净流入成交额比例，再参与情绪评分。
Python 756 日滚动窗口、120 日最小样本、1400 天预热与 Java 对应。
修正上一节范围：当前 market_state 因子直接使用行情面板，不直接依赖普通资金流；
不能仅因 moneyflow 补数而认定所有股票/市场因子缓存失效。

实际重算并发布 2026-10-08 至 2026-10-09 情绪表：
`market-sentiment-ee24ffbc-ed3a-4e06-b2ab-71d4e27b82fb` VERIFIED，2 行，
读取 927 个历史日期、5115583 行预热输入；保留窗口外数据及原表备份。
证据 `sentiment-after-moneyflow-run.log` 与对应 sync-evidence/source.json、publication.json。
独立物理查询 `sentiment-after-moneyflow-physical.json`：两日 moneyflow_score 分别
39.85769855582844、59.91150091460869，质量标记 enhanced、partial_enhanced。
10 月 9 日融资融券仍不完整，不能把情绪表 VERIFIED 理解为全部上游完整。
本轮核对了公式和依赖，尚未对这两行进行 Python 完整数值重跑对照。

## 情绪表原 Python 完整数值对照：未通过

执行 `sentiment_python_parity.py`，直接调用原 Python build_market_sentiment_daily，
write_to_db=False，并把查询绑定本地 PGWire SELECT。未修改 Python 实现、未写库。
证据 `sentiment-python-parity.json`、`sentiment-python-values.json`、`.log`。
比较 2 天共 104 个业务单元格（只排除 updated_at），rtol=1e-8、atol=1e-10：25 项不一致。
Python 此次总耗时约 120.42 秒，包含取数与比较，不作为受控性能结论。

主要差异：Python 对 10 月 9 日部分融资融券直接聚合，产生 leverage_score=15.0740，
并标记 enhanced；Java 该分量留空、partial_enhanced。相应总评分、状态、PCA 不一致。
此外 growth_value_ret、pct_distance_to_ma20_lt_minus_5 及部分衍生评分不一致，仍需定位。
不能把差异都归因于浮点误差，不能宣称情绪数值迁移已通过。
前次 VERIFIED 仅证明 Java 计算产物完整发布和回读，不代表 Python 语义一致或全部上游完整。

## qcut 分组边界修复

定位 bucketDifference 与 pandas 2.3.3 / numpy 2.2.6 的边界换算差异。
Python Series.quantile 经百分位乘 100 再除 100，引入边界前的浮点舍入；
旧 Java 直接除以 3，在部分整数边界上分组不同。现按同样换算比较一基排名。
`sentiment_qcut_boundary_audit.py` 对样本数 3..6000 共 5998 种核对：
旧公式 1999 种分组不同，新边界 0 种不同，见同名 JSON。
`sentiment-qcut-compile.log` 编译通过，定向 diff --check 通过。
这是边界公式证据，尚未重跑 Java 全量情绪产物；均线阈值与融资融券规则差异仍未解决。
本轮未重新发布数据，数据库仍保留上轮已发布版本。

## 均线阈值差异定位

`sentiment_ma_boundary_audit.py` 先用 354985 行近期数据定位 6 个阈值附近候选，
再读取这 6 只股票完整 1400 天范围内 4440 行，并严格模拟 Java 环形数组逐项 double 累加。
注意 Python 新版内置 sum 具有不同精度，诊断程序显式用逐项 +=，避免错误模拟。
结果 `sentiment-ma-boundary-audit.json`：002652.SZ / 2026-10-09 的距离，
pandas=-0.04999999999999993，Java 累加模拟=-0.050000000000000044，跨越严格 -0.05 比较。
这解释了当日低于负 5% 比例多计一只股票的差异。下一步应对齐 pandas 滚动累加算法，
而非给业务阈值添加容差。当前仅完成根因定位，生产均线实现尚未修改，未写库。

## pandas 滚动均线移植与实际 Java 对照

核对 pandas v2.3.3 官方 aggregations.pyx 的 roll_mean/add_mean/remove_mean/calc_mean，
保存来源快照 `pandas-2.3.3-aggregations.pyx`。
StockHistory 改为独立加/减 Kahan 补偿、先移除后加入，保留连续相同值及符号修正规则。
每次更新 O(1)，继续复用 20 个价格的环形缓冲区；未改变 0、正负 5% 业务阈值。
定向编译 `sentiment-rolling-compile.log` 通过。
用原 pandas 对六只边界候选股票完整预热历史生成固定输入 `sentiment-rolling-fixture.json`，
实际执行 Java StockHistory（`SentimentRollingParity.java`），4440 个观测逐位一致，
最大绝对误差 0、阈值分类差异 0，证据 `sentiment-rolling-parity.json`。
范围仅为六只股票，尚未重跑全部情绪评分/发布；融资融券规则差异依然存在。本轮未写库。

## 修复后情绪表实际发布与对照

重新计算并发布 2026-10-08/09：`market-sentiment-0335aa59-ff82-4a8d-af5e-1f4caa2ab925`
VERIFIED，2 行，927 个历史日期、5115583 行输入。证据 `sentiment-numerical-fix-run.log`。
`sentiment_compare_saved_python.py` 将当前库与上次原 Python 输出比较，
检查日期键并排除 updated_at，102 个字段单元格中剩余 17 项差异（原为 25 项）。
分组收益、均线比例、核心评分等原有差异消失；仍有杠杆评分、综合评分、状态、PCA、质量标记差异。
见 `sentiment-after-fixes-parity.json`，仍未通过整体数值对齐。

两次 Java 请求的物理来源 source_pin 相同，见 `sentiment-fix-source-stability.json`，
包括表身份、事务与 WAL 边界；计算内 sourceFingerprint 不同，其原因尚未单独定位。
因此不能宣称逐字节来源快照已证明一致，也不能仅凭字段类别断言所有剩余差异均由融资融券规则解释。
本轮实际更新正式情绪表并保留旧表备份，未重跑原 Python 构建函数。

## 来源证据嵌套 JsonNode 的哈希稳定性修复

两次保存的 dailyOnlyMissingFactor 与 marginAvailability 语义相等，字段顺序不同。
定位到上市日探测 listingResponse 中嵌套 JsonNode：canonicalMapper 的 Map 排序不排序节点内部字段。
MarketSentimentDailyJobService.canonicalBytes 现先转换为普通 Object/Map/List，再排序序列化。
使用真实两次 listingResponse 构造同样的嵌套 Map，并反射调用生产 canonicalBytes：
旧方式字节不同，修复方式字节相同。见 `SentimentEvidenceOrderAudit.java`、
`sentiment-evidence-order-audit.log`。编译与定向 diff --check 通过。
这证明一条实际指纹不稳定路径已修复；未重新运行完整源哈希计算，不能排除其他差异来源。
本轮未更新数据库，17 项情绪评分差异仍待处理。

## 剩余 17 项差异的因果诊断

`sentiment_python_parity.py --match-java-margin-mask` 仅在诊断进程包装原 Python
_build_margin_metrics，将末日 2026-10-09 的三个融资指标置空，与当前 Java 规则一致；
原 Python 文件、其他计算步骤及正式表均未修改。因为被置空的是最后一天，
没有后续 pct_change 窗口受该干预影响。保留原始未干预失败报告，不覆盖它。

实际完整重跑读取 5115583 行面板，约 117.97 秒：104 个业务单元格全部在
rtol=1e-8 / atol=1e-10 内一致，包含综合评分、状态、PCA 和质量标记。
证据 `sentiment-python-margin-mask-parity.json` / `sentiment-python-margin-mask.log`。
因此本次两日样本剩余 17 项差异可由融资完整性规则解释，不需要继续修改评分数学公式。
这不是原 Python 无干预一致性通过，也不能证明全部历史日期。Java 当前保留部分数据但
对不完整融资分量留空；若要求其数值也参与计算，须明确保留部分完成语义，不能据此签发 DataReady。

## 计算修复后的版本失效边界

检查 MainStrategyDailyPipeline.nativeRun：同一实例内可复用已验证产物，校验来源 clocks、
物理表摘要、证据 SHA 与作业定义 SHA；未另绑定计算类字节码。
因此情绪作业定义由 v3 提升至 v4，覆盖 qcut、滚动均线与嵌套 JSON 指纹修复，
防止旧 v3 产物被当作新算法产物复用。模型名称仍保留原 Python 公式标识。
旧冻结实例需要新计划，不能静默改变旧请求定义；本次没有删除旧凭证。
`sentiment-v4-compile.log` 编译通过，定向 diff --check 通过。
实际只读加载上轮 v3 完成批次，调用生产 FrozenRunRequest.restoreCompleted 与 v4 定义，
确认拒绝复用，证据 `sentiment-v4-admission.json` / `SentimentVersionAdmissionAudit.java`。
本轮未写库，尚未发布 v4 批次；跨实例复用和通用 post_close 接线仍未完成。

## ETF 持仓公告与国债收益率来源缺口补齐

正式 ETF 持仓 BACKFILL 2026-10-08..09：
`etf-portfolio-150af86b-818e-4a94-8bd5-8f7ddac7ca69` VERIFIED_EMPTY，2 个公告日均取源为空，
适配器逐日回读正式表确认也为空。证据 `etf-portfolio-formal-run.log` 与对应完整 source receipts。
这证明该公告窗口为空，不证明历史持仓覆盖全部齐备。

新增 `plan-cn-bond-yield-curve-job` / `run-cn-bond-yield-curve-job`，复用盘后 ChinaBond owner，
要求显式 from/to/logical-date，保持原 <=31 日界限。编译 `bond-cli-compile.log` 通过。
实际执行同两日正式同步：`cn-bond-yield-d47112bf-0d29-4630-a249-9229e382db10`
VERIFIED，46 个来源曲线点全部回读。证据 `bond-formal-run.log`，独立计数
`bond-formal-physical-counts.json`。本次发生正式写入，46 是来源/验证行数，不宣称全部新增。
整体来源复核尚未重跑，沪深港通覆盖凭证与融资融券完整性仍待处理。

## 沪深港通正式同步与补数后清单

正式 BACKFILL 2026-10-08..09：`moneyflow-hsgt-be38b281-15ec-4958-986a-49ee16c35c3e`
VERIFIED，2 行来源/回读，证据 `hsgt-formal-run.log`。独立物理查询六个资金字段均非空，
见 `hsgt-formal-physical.json`。此 owner 使用暂存发布，发生正式表变更，非只读操作。

全部 CLI 来源显式绑定正式表，补数后只读检查正常退出：
`formal-readiness-after-backfills.json` / `.log`：14/15 项已有覆盖完成候选，
状态 REVALIDATION_REQUIRED；margin_detail 为 NO_COMPLETED_CANDIDATE。
本次没有重新取源全部 14 项，不把候选计数当作 DataReady。缺覆盖完成候选已仅剩融资融券。
新沪深港通与国债输入已改变，后续应刷新 regime_features_monitor_daily 并对照 Python。

## 市场状态监控刷新与 qcut 修正

先实际刷新两日，`regime-monitor-8b25f45a-64b1-4ff2-aba4-ae5e75f961f9` VERIFIED。
随后检查发现 RegimeFeaturesMonitorDailyCalculation.bucketMeans 同样使用旧直接除三边界；
原 Python data/derived/regime/features_monitor_daily.py 使用 pandas qcut。
按已核对的百分位换算修复，作业版本 v3 -> v4，阻止旧计算凭证复用；
`regime-qcut-compile.log` 编译通过。
实际重新发布 v4：`regime-monitor-ac82996f-e65b-4081-9d16-ed6ac324647c` VERIFIED，2 行，
10 个历史日期、55564 行输入，证据 `regime-v4-run.log`。两次输入 sourceFingerprint 一致。
原表备份保留；这仅证明 Java 发布/回读，尚未做本次完整 Python 数值对照。
注意原 Python builder 默认无 write_to_db 开关，会直接调用替换函数；后续只读对照必须显式
拦截该写入入口或调用纯计算步骤，不能直接运行 builder 导致覆盖 Java 正式表。

## 市场状态监控原 Python 对照

`regime_python_parity.py` 调用原 Python builder，替换 _fetch_df 为本地 SELECT，
明确拦截 _replace_regime_features_monitor_daily；实际拦截 2 行写入，数据库写入 0。
原数学流程未改，2 天 40 个业务单元格（排除 updated_at）：39 项在
rtol=1e-8、atol=1e-10 内一致。唯一差异是 2026-10-09 margin_balance_change_mtd，
Python=-0.4925276934000957，Java=NULL。
来源证据确认该日深市/北交所整体缺失，Java 完整性策略排除；原 Python 直接使用部分余额。
分组风格、广度、沪深港通与估值字段本次均一致。
证据 `regime-python-parity.json`、`regime-python-values.json`、`regime-python-parity.log`。
此次约 13.08 秒含取数，不是受控性能对比。原 Python 无干预一致性仍未通过。
额外发现预热范围内 2026-09-30 也仅沪市融资行，后续补数应包含此日期，不能只检查最新日。
MainStrategyDailyPipeline 的 REGIME_REQUIRED 不包含可缺失融资字段，仍须保留其独立完整性说明。

## 通用盘后因子阶段与 Python 工作流的接线审计

本次重新读取 batch-app RuntimeConfiguration.stageExecutor / DataReadiness：
默认 DataReady 只接受 jdb_test_<namespace>_<dataset>_<instance> 隔离目标，
FactorReady/FactorMetrics 仍转交 ExternalComputation；正式 owner 的完成批次不能直接替代该证书。
main-strategy-runtime 的 MainStrategyDailyChildConfiguration 只注册 mainStrategyDaily，
不能将这条已原生化的专用流程当作通用 post_close 已接线。

Python 参照文件：scripts/pipeline/run_post_close_factor_data.ps1、run_factor_sync.ps1、
src/quant_platform/workflows/data/factor_sync.py、scripts/pipeline/run_post_close_factor_monitor.ps1、
src/quant_platform/research/application/factor_performance_freshness.py。

接线必须覆盖的契约：
1. FactorReady 组合股票和 ETF 发布，随后检查 QuestDB 物理可见性；Python 默认 incremental。
   股票已发布而 ETF 失败，或发布后可见性等待失败时，允许复用已发布产物并重新检查可见性。
   Java 已有各自的 PipelineService，但通用阶段尚未组合；不能以单个子流程完成冒充整阶段。
2. FactorMetrics 不仅发布 canonical factor_daily_metric，还检查新鲜度并刷新 factor 页面快照。
   原因子监控表已退役，不能迁回旧 factor_monitor_daily / factor_monitor_summary。
3. 新鲜度按配置声明的 universe_id/universe_version/factor_id/definition_version 检查完整键集，
   不能从已有事实行反推应有集合。缺失、重复、非 ready 单独计数；有充分证据的常量因子
   允许 not_applicable。成熟 fwd1 标签最多落后 2 个交易日；交易日历覆盖缺失不能判断 fresh。
4. 现有 Java DailyMetricPipelineService 已负责计算与发布，尚无上述完整新鲜度与页面快照接线。

下一步顺序：正式来源复核证据 -> 正式 DataReady 适配 -> 股票/ETF 组合阶段及恢复 ->
成熟日期指标与新鲜度检查 -> 页面消费链检查 -> 实际 post_close 运行与端到端性能记录。
不改变部分源数据先保存、缺口可重跑的规则；发布部分事实与完整就绪分别表达。

## 补数后正式来源再次取源复核

2026-10-09 全部 15 项正式来源串行复核进程正常退出（exit 0），结果必须逐项判断：
13 SOURCE_REVALIDATED；margin_detail NO_COMPLETED_CANDIDATE（3 个 PARTIAL）；
cn_bond_yield_curve INSPECTION_FAILED / WebClientRequestException。
13 项包括此次补齐的 moneyflow、moneyflow_hsgt、exchange_calendar、etf_portfolio。
国债上次 VERIFIED 批次仍存在，本次连接异常不能推导数据丢失或重新验证通过。
证据 post-backfill-source-revalidation.json / .log 与每项 receipt。
数据库写入 0，生成了新的本地取源核验凭证；dataReady=false，certificateIssued=false。
该轮约 4 分钟包含启动、取源和正式表核对，不是因子计算性能测量。

## Java 指标新鲜度规则与检查入口

新增 DailyMetricFreshness，对齐 Python 正式显式矩阵路径：四字段版本键、共享最新日期、
重复/缺失/非 ready 独立计数；复用 DailyMetricPublication 的常量证据数值检查。
质量状态按 Python 小写归一化；完整常量结果可 not_applicable，但不计入 ready。
成熟标签新鲜度阈值为 2 个交易日，日期落后时要求 calendarAsOf 覆盖 asOf。
调用者必须提供受治理的完整 openDates；该纯策略不自行证明日历来源或数据库可见性。

新增 inspect-factor-daily-metric-freshness --input FILE，输入 JSON 包含 rows（Java 行结构）、
expected（universeId/universeVersion/factorId/definitionVersion）、asOf、windowEnd、
calendarAsOf、openDates。输出包含输入 SHA、assessment，并明确 physicalVisibilityVerified=false、
factorReady=false。这为后续正式读取适配器提供同一规则，不把离线文件当作发布证书。
两次 compileJava 通过（metric-freshness-compile.log、metric-freshness-cli-compile.log）。
本轮未运行测试；尚未完成新规则 Python 场景对照、正式数据库读取适配和 post_close 接线。

## 正式指标新鲜度读取适配器

新增 QuestDbDailyMetricFreshness，读取正式 factor_daily_metric 指定 signalDate，
按 labelDefinition、horizon=1、evaluationProfile=base_ic 限定计算产品，
沿用有界读取与重复业务键拒绝；读取 exchange_calendar 的 SSE 逐日 0/1 状态，
验证 signalDate..asOf 连续覆盖（最大 366 天），缺日历时不给出覆盖到 asOf 的依据。
两张表读取前后比较 table/WAL 状态，期间变化即拒绝本次判断。
显式 expected 四字段键集仍来自调用方，绝不由现存数据推导应有集合。

入口 inspect-formal-factor-daily-metric-freshness --input FILE：
输入 signalDate、asOf、labelDefinition、expected；行和日历直接读正式库。
输出 inspection.assessment、calendarComplete、physicalRows、sourceState，数据库写入 0。
它只检查指定信号日，不负责自动选择最近成熟信号日，也不签发 FactorReady。
formal-metric-freshness-compile.log 编译通过。本轮未运行测试或实际调用新入口；
正式读取运行验证、Python 对照、自动成熟日期选择及 post_close 调用仍待完成。

## 正式新鲜度入口实际运行

新入口两次实际只读运行正常退出：先检查冻结声明中 1 因子 x 4 池，再检查完整 66 x 4 = 264 键。
完整矩阵来自 four-pool-all-stock-computation-r2-path.txt 指向的既有声明/计算产物，
确认 expected=rows=264、missingKeys=0 后提取版本身份；未用当前数据库行反推应有集合。
正式表 2026-10-08、asOf=2026-10-09：256 ready、8 constant/not_applicable，缺失和重复均 0；
SSE 日历连续覆盖完整，lagTradingDays=1，status=fresh、coverageStatus=partial。
这只是显式 66 个非 L2 股票因子范围，不代表所有 L2/ETF 因子或整个 FactorReady。
新鲜度遵循 Python data_quality_status 规则，不另外要求 pure_rank_ic；
因此不能与 requireEffective=true 的 publicationReport 中 ready 数量直接等同。
正式运行证据 formal-freshness-full-matrix-input.json / .log，数据库写入 0。
随后调用原 Python assess_factor_performance_freshness，读取相同正式范围和声明键；
HTTP 时间字段先标准化为 UTC 无时区日期，避免原函数 naive/aware 比较错误。
formal-freshness-python-result.json 的全部 11 项结果与 Java 完整矩阵输出一致。
仅针对本次日期/矩阵的真实数据对照，尚未覆盖所有边界场景与自动调度。

## 自动成熟信号日选择

核对原 Python workflows/data/factor_daily_metrics.py：省略 start 时使用 asOf-30 天窗口，
mature_pairs 后仅保留最后一对；显式 start 为全部成熟对的回补。
新增 QuestDbDailyMetricPlan 复用 DailyMetricCalendar，连续日历覆盖、重复日期与 WAL 状态均检查，
返回信号日/成熟日及日历状态。正式新鲜度 Request.signalDate 可省略，自动选择后检查正式表，
并在返回前再次核对计划日历没有变化；显式矩阵仍由调用方负责声明。

实际运行 asOf=2026-10-09、省略 signalDate：成功自动选择 2026-10-08，
264 个键的结果与前轮显式日期一致（256 ready、8 not_applicable、无缺失/重复），零数据库写入。
证据 formal-freshness-auto-date-input.json / .log；该运行发生在增加结果 signalDate 字段之前，
随后仅增加结果中的选定日期字段，metric-maturity-plan-final-compile.log 编译通过。
保留 signalDate 输出使目标日没有任何事实行时也能明确报告检查范围。
该能力目前接入正式新鲜度检查；自动驱动指标计算/补数及通用 post_close 仍待完成。

## 自动成熟日期驱动指标计算与发布

DailyMetricPipelineService 新增 runLatest，CLI run-factor-daily-metric-pipeline 的 --date 改为可选。
省略时读取成熟计划，持久化 maturityPlan，并在计算前/发布前/发布后核对日历状态。
显式 --date 保持原行为；冻结池输入仍必须覆盖选定日期，不自动把其他日期输入套用过来。
metric-auto-pipeline-compile.log 编译通过。

实际使用已有 1 因子 x 4 池冻结输入运行：选择 signal=2026-10-08、maturity=2026-10-09；
publicationRunId=factor-daily-metric-881d46c1-4083-45f9-a1c7-5acbdbecf1e6，
sourceRows=4、verifiedRows=4，state=PARTIAL、errorCode=IllegalStateException。
sourceComplete=false、factorReady=false，CLI 按原完整性规则返回退出码 3；
这是部分发布回读结果，不是整体流水线通过。未证明零增量写入，不作零写入声明。
证据 auto-date-metric-pipeline.log 与 auto-date-metric-pipeline 下的 pipeline.json/computation.json。
正式阶段仍需处理 partial 与完整就绪的区别，并接入自动选源、完整矩阵和 post_close。

## 自动指标运行 PARTIAL 的根因与结构化摘要

只读查询 sync-ledger.sqlite3 的 sync_entries RUN 记录确认上一轮异常位置：
SyncJobRunner.java:122（SourceCompletion 不完整），不是推测数据库写入失败。
DailyMetricEvidence.complete() 要求 sourceComplete && reusableCheckpoint；
当前 DailyMetricComputation 输出 sourceComplete=false，故全量回读后仍保留 PARTIAL。
这意味着正式来源认证尚未传入计算/发布链，不能靠重跑消除该状态。

流水线现增加 publicationReport、expectedRows、incompleteReasons、publishedRowsVerified，
明确区分 SOURCE_NOT_CERTIFIED_COMPLETE、METRIC_MATRIX_NOT_REUSABLE 与已发布行数回读。
这些字段仅解释状态，不改变来源认证和 FactorReady 规则。
metric-completion-diagnostics-compile.log 编译通过；没有再次运行发布，也未运行测试。
下一步需要把正式来源核验证据接入计算证据，而非把 sourceComplete 常量改成 true。

## 来源复核凭证显式日期范围与冻结身份

VerifiedRunRecovery 使用共享 JSON mapper，并在取源前校验原账本 targetId 与冻结请求指纹。
新凭证 schema=java.source_revalidation.v2，追加 jobId/jobVersion、from/to、logicalDate、
requestFingerprint；保留原 sourceEvidence、sliceReadbacks、行数和 passed 字段。
这为因子历史窗口覆盖检查提供显式范围，不以单日通过代表整个预热窗口。

编译 source-revalidation-scope-compile.log 通过；实际 revalidate-daily-source 正常退出：
新 receipt daily-recheck-1faa34b2-ec95-4739-b6e5-4a436c59baec/
revalidated-343338cb-ebe3-4456-bba4-d2afb06a249f.json，from=2026-10-08、to=2026-10-09，
expectedRows=actualRows=11116、pages=2、passed=true、数据库写入 0。
日志 source-revalidation-scope-live.log。历史窗口范围覆盖与正式 FactorReady 接线仍未完成。

## 因子 460 日历史窗口审计及 stk_limit 实际缺口

factor_history_coverage.py 对 2025-07-06..2026-10-09 做本地只读按日计数：
SSE 日历无缺日期、305 个交易日，读取前后来源状态一致。
v_backtest_daily/daily_basic/daily/stk_factor/stk_st_daily/stk_suspend 均无整日空缺，
但这不是证券业务键或字段值完整性证明；事件表无记录也不能直接解释成缺数据。
stk_limit 缺 2026-06-01、2026-06-02，补数前报告 factor-history-coverage-before-limit.json。

实际正式 BACKFILL 两日，run=stk-limit-45bf97eb-efe5-4277-a8d5-9b8ba596fcd3。
第 3 批回读超时，750 行提交、500 行在当时已验证，批次 IN_DOUBT，CLI exit 3。
随后只读审计已见新增 750 行，六月一日不再整日空白，但不能据此声称该日已补齐；
六月二日仍无记录。证据 limit-history-gap-backfill.log、factor-history-coverage.json。
原批次 --resume-from 尝试 exit 1：FrozenRunRequest.restore 不接纳 IN_DOUBT，
日志 limit-history-gap-resume.log。未修改状态、未释放区间锁、未重新建批次重放。

下一步必须实现 stk_limit 已提交前缀的逐键逐值对账恢复，参照已有 owner 恢复模式；
不能仅放宽 FrozenRunRequest 的状态集合。仍有可实施的恢复代码工作，目标不标 blocked/complete。

## stk_limit 不确定写入恢复与两日缺口完成

新增 StockLimitSubmittedPrefix / StockLimitRunRecovery 与 reconcile-stk-limit-submitted-page：
验证原写入日志/PID 已停止、冻结目标/定义、保留区间锁、提交批次摘要/字节数，逐键逐值对账；
已提交键缺失或值不同则拒绝，只补未提交且当前缺失的键。支持前页已验证、单页 IN_DOUBT，
恢复后将原批次置 PARTIAL，再走普通续跑重新取源验证整个区间。
StockLimitWritePort 回读按日期分组 IN，保留完整日期/代码组合及原有范围约束。
编译日志 limit-recovery-compile.log、limit-grouped-readback-compile.log、limit-multipage-recovery-compile.log。

第一日原 750 行物理值与冻结来源完全一致（limit-uncertain-value-audit.json）；
补 4854 行后共 5604 行验证，恢复日志 limit-history-reconcile.log。
续跑复用第一日 5604 行，第二日再遇回读超时进入 IN_DOUBT；扩展多页恢复后，
limit-history-second-reconcile-r2.log 成功恢复第二日。日志格式识别首次拒绝发生于写入前。
最终普通续跑 stk-limit-af34934f-c354-4b0c-9b67-f96f24e4d218 VERIFIED：
sourceRows=verifiedRows=reusedRows=11208，日志 limit-history-final-resume.log，exit 0。

重新只读按日审计：305 个交易日所查七表均无整日空缺，stk_limit 总行数 2238612。
仅证明整日空缺已修复，未证明全历史逐证券完整性，也未刷新任何可能已物化的历史衍生结果。
后续需检查 v_backtest_daily 与这些历史涨跌停价格的关联/刷新语义，继续历史覆盖及 post_close 接线。

## 涨跌停缺口向 backtest 衍生表传播（运行中）

已查明 v_backtest_daily 是 manual 物化视图，基表 backtest_daily；不直接依赖 stk_limit。
补数后视图六月一日/二日各 5529 行，up_limit/down_limit 均全空，证据 backtest-limit-gap-before.json。
原生 owner 两日非 bootstrap 计划通过，实际运行 run-backtest-daily-job：
run=backtest-materialize-f6b4fc29-aa0e-4f02-ad78-e3703512117d，日志 backtest-limit-gap-run.log。
发布账本由 PREPARED 推进到 PUBLISHED，物化视图刷新开始于 2026-10-11T15:42:11.328383Z。
最新实际查询仍 refreshing、finish=null；执行 session_id=46627 仍活跃，未取消/重启。
下轮必须继续轮询同一 session，不能重新启动。结束后再核验两日视图的键和值与 stk_limit 一致，
并检查原生发布证据；目前尚不能声明修复已传播到因子读取层。

## 继续等待原生刷新与增量性能问题

继续轮询同一执行 session_id=46627，仍活跃；未重启。只读校验脚本
 audit_backtest_limit_repair.py 已新增，刷新未完成时报告 WAITING_FOR_NATIVE_REFRESH，
不会对刷新中的部分视图作通过结论。待完成后逐键比较 view/base 全字段及正式 stk_limit 两字段。

代码定位到 BacktestDailyMaterializationJobService.installAndVerifyNative 调用：
bootstrap || oldMonths.rowCount>0 时传 fullRefresh=true；BacktestDailyMaterializationPort 执行 FULL。
因此本次只有两日变更却刷新整个原生视图，属于实际增量性能缺口。
当前不能在刷新过程中改动或另启刷新；完成后研究范围刷新/更合理物化策略，
不能把等待算作 Java 因子计算性能，也不能只凭两日输入声称端到端是增量。

## 范围刷新官方语义与运行进度

查阅 https://questdb.com/docs/query/sql/refresh-mat-view/：支持 RANGE FROM ... TO ...，
范围刷新只重算指定区间，但不推进 incremental checkpoint；FULL 用于恢复失效视图。
因此不能仅替换 SQL 为 RANGE，同时继续要求 refresh_base_table_txn=base_table_txn；
需要设计范围证明及检查点推进/有效状态的验收，后续在隔离对象验证后再用于正式 owner。
当前本地 select build() 为 QuestDB 10.0.1（commit 7a391566ac827e0d8c269b91f2415c16b0a32a84）。

原任务 session 46627 仍活跃；table_partitions 显示视图已构建 3824579 行，最大日期推进到
2021-09-27，证明存在实际进展，但不是完整数据验收。不能因为刷新时间长而重复提交。
正式修复仍待该任务完成及 audit_backtest_limit_repair.py 通过。

## 原刷新任务继续观察

本轮继续轮询 session 46627：仍活跃，最新 materialized_views 状态 refreshing、finish=null、
refresh_base_table_txn=-1、base_table_txn=15，保存 backtest-refresh-latest-state.json。
可见分区仍约 3824579 行至 2021-09-27；不能从暂时不增长推出任务终止。
服务配置确认 QuestDB 使用 F:/db/questdb-10.0.1-data，当前 rolling 日志最新文件为空，
旧日志停留于本次刷新之前，未获得可证明刷新失败/重试的日志。未重启或取消任何写入任务。
停止了本轮无关的全目录日志文件搜索进程（session 31356），与原物化写入进程无关。

加强 audit_backtest_limit_repair.py：三张表的读取前后状态/WAL 全部比较，要求两日均非空，
避免空集相等误报通过；原生刷新未完成时仍拒绝执行最终值验收。
下轮继续原 session 46627，不另起物化任务；完整目标仍未完成。

## 两日 backtest 修复最终验收完成

原 session 46627 正常 exit 0，未重启。run backtest-materialize-f6b4fc29-aa0e-4f02-ad78-e3703512117d
VERIFIED，sourceBusinessRows=11058，baseRows=materializedRows=10411575。
原生 FULL 开始 15:42:11.328383Z，结束 15:55:26.687677Z，约 13 分 15 秒；
整个 CLI 含计划/备份/发布/验证约 17 分 51 秒。此为物化维护耗时，不是因子计算耗时。

独立 audit_backtest_limit_repair.py：两日 view/base 各 11058 行，键集和全部视图字段一致，
有匹配来源的涨跌停字段逐值一致，三表状态稳定、WAL settled。
初版要求每条视图记录都有 stk_limit 来源而失败；检查 canonical LEFT JOIN 后修正为
来源不存在时必须保留 null，同时独立列出 sourceMissingKeys，绝不把缺失抹除。
4 只证券（600355.SH、603056.SH、300379.SZ、002231.SZ）两天共 8 条来源缺失，
均 is_suspended=1；allLimitFieldsPresent=false。传播一致性 passed=true 不代表这些字段有值。
证据 backtest-limit-repair-audit.json、backtest-limit-gap-run.log。
当前没有活跃物化进程；可以继续后续代码编译和正式因子/盘后接线。

## 重复窗口零物理写入路径实际通过

BacktestDailyMaterializationPort.reuseExisting 验证来源窗口与正式 BASE/MV 的完整摘要一致，
且 nativeState、base identity 前后稳定；仅此情况下 send 不创建 stage、不写业务表，
readback 仍读取正式基表，run 完成前再次核对 sourcePin 与 nativeState。
不变窗口跳过备份/发布/刷新；基表一致但原生视图失效或值不一致仍走原修复路径。
编译 backtest-noop-compile.log 通过。

实际重复 2026-06-01..02：backtest-materialize-b5a0e828-2365-4130-a178-dff5cee7e6d8 VERIFIED，
source evidence physicalPublicationReused=true。两层物理 identity 和事务号不变（BASE15、MV45），
证据 backtest-noop-state-comparison.json、backtest-limit-gap-noop.log。
开始时记录状态与上一轮最终验收一致；本次无正式业务表写入/原生刷新，但有本地运行账本和凭证写入。
总耗时约 2 分 14 秒，主要仍为来源/现有数据核验；此前修复含全量刷新约 17 分 51 秒，
两者工作内容不同，不能作为受控加速比。来源实际变化时的范围刷新优化仍未完成。
当前无本次活跃写入进程，可继续正式盘后接线。

## 股票与 ETF 原生盘后组合入口

重新对照 Python workflows/data/factor_sync.py：默认股票 incremental、ETF 独立历史窗口，
两个发布完成后还有联合物理可见性检查。Java 新增 PostCloseFactorPipelineService 与命令：
`run-post-close-factor-pipeline --date DATE --etf-history-from DATE --factors ID,ID --pools ID,ID --root DIRECTORY`。

组合服务串行调用现有股票/ETF 服务，共享发布适配器；子目录稳定，继续采用现有来源指纹计算复用
和逐键增量发布。组合目录持有文件锁，记录子收据、失败阶段、股票/ETF/最终可见性/总耗时。
两边发布后按冻结产物摘要、目标日期、行数逐页回读完整键和值；要求整个联合回读期间
factor_observation 物理身份、事务号不变且 WAL settled。读取的是正式表，不依赖旧成功状态跳过。
来源未认证完整时仍输出 PARTIAL、sourceComplete=false、factorReady=false，CLI exit 3。
子写入异常保留原有 owner 锁和证据，不自动处理 IN_DOUBT。

main-strategy-runtime:compileJava 通过，日志 post-close-factor-composition-compile.log。
尚未运行新组合入口；因此未证明组合运行/恢复成功，也未把它接入自动 StageExecutor。
仍需完整来源证明、组合实际运行及恢复验收、FactorMetrics/页面消费链和自动盘后接线。

## 组合入口首次实际运行与输出修复

首次 session 63045 exit 2：参数误取旧 ETF 失败收据的 historyFrom=2025-07-05，超过
当前读取器 460 天上限一天。股票 299574 行已验证、deltaRows=0，ETF 计算前拒绝。
已在组合 Request 和 ETF Request 前置相同窗口检查，避免无效 ETF 参数先触发股票运行。
Python 默认 550 天与现有读取器上限的差异仍未解决，不能宣称默认窗口完全兼容。

第二次采用此前成功窗口 2025-07-06..2026-10-09，session 95190 已终止 exit 2。
组合服务收据 post-close-factor-live/2026-10-09-1fac98b2-2708-40bf-bafd-37d1bce28b14/pipeline.json：
PARTIAL，stockRows=299574、etfRows=52541、physicalVisibilityVerified=true，来源完整性仍 false。
所有发布分片日志 deltaRows=0；最终联合逐键逐值回读通过且物理状态稳定。
股票 71.60 秒、ETF 37.52 秒、联合回读 16.41 秒、服务总计 125.67 秒，不含 JVM 启动。
证据 post-close-factor-live-r2.log。退出错误发生在服务完成后的 CLI PLAIN mapper 打印 LocalDate；
已改用 JOB_DEFINITION mapper，post-close-output-compile.log 编译通过，输出修复尚未重新运行。
当前两个会话均终止，无本次活跃写入进程。后续继续输出验收、550 天兼容及正式盘后接线。

## ETF 默认 550 天窗口接入（实际发布运行中）

读取 Python factor_sync_adapters.refresh_etf 与 topic build_catalog._resolved_start_date，确认默认
为 as_of - 550 天（lookback 参数至少 370 天；显式 start_date 可覆盖）。Java 新增
EtfHistoryWindow 统一 550 天默认值和当前支持上限，ETF 输入读取、CSI 股票投影读取及
两个 pipeline Request 同步接纳；原 ETF 100 万、股票每源 500 万行上限保留。
组合命令 --etf-history-from 改为可选，省略时使用该默认值。编译 etf-550-history-compile.log 通过。

实际运行 session_id=98443，日志 post-close-factor-550-live.log，组合执行目录
post-close-factor-live/2026-10-09-e16d82f9-801b-4e5b-8eb3-48f3e56ce5d7。
股票发布通过且 deltaRows=0；ETF 扩大历史窗口后出现变化值并已进入正式增量发布，
子执行目录 etf/2026-10-09-6f8024ee-8faa-4d12-a5fc-9e4568b1c550。
最新同一 session 轮询仍 live。日志有单批 WAL visibility timeout，随后仍有批次回读进展；
不得据此认定终止/重新提交，也不得编译改变运行期间代码指纹。
下一轮继续 poll session 98443 至终态，再核对联合回读和 CLI 输出修复；尚无最终通过结论。

## 550 天组合运行终态与差异审计

同一 session 98443 已终止 exit 3。组合收据 phase=PARTIAL、physicalVisibilityVerified=true，
stockRows=299574、etfRows=52541，sourceComplete/factorReady 仍 false；CLI 日期输出已实际通过，
exit 3 是 IncompleteCommandException 的业务未就绪退出，不是上一轮序列化错误。
ETF 起点 2025-04-07，股票 110.78 秒、ETF 143.78 秒、联合回读 14.15 秒、总 268.91 秒。
本轮无活跃 writer，可继续编辑/编译。

新增只读 compare_etf_history_windows.py，比较前轮 460 天与本轮 550 天冻结 ETF 产物，
记录路径与 SHA，结果 etf-history-window-differences.json。两者键集完全相同，5159 个值变化：
flow_intensity、flow_persistence、share_change 各 26 个由 null 变为有值（共 78 个）；
其余 5081 个为微小数值差异，最大相对差约 3.03e-11。不是 Python 数值对照。
日志 deltaRows=4674 仅累计 store.send 正常返回的批次；另 485 行曾出现可见性超时，
随后执行器回读通过，故该计数不能代表实际变更总数。最终联合回读与全部冻结目标一致。
后续需修正发送计数口径、追踪长历史补齐份额的来源，并继续正式 DataReady/指标/页面接线。

## 发送计数口径修复及窗口来源状态复核

GlobalObservationWriteSession 移除有歧义的 deltaRows 日志字段，改为 attemptedDeltaRows、
sendReturnedDeltaRows、sendExceptionDeltaRows。调用发送前记录尝试数，正常返回和异常分别累计；
这些是发送尝试计数，重复尝试可重复计数，不声称物理唯一写入数，最终验证仍由回读/账本负责。
observation-send-counters-compile.log 编译通过，尚未制造发送超时或重跑实际发布验证新字段。

窗口差异脚本新增两份 panel 的全部 *SourceState 对比，实际 allRecordedSourceStatesEqual=true。
因此已记录来源表状态没有在两次计算间变化；该证据支持继续从历史窗口/滚动与份额前向填充
分析差异，仍不是 Python 数值一致性证明，也不是全历史完整性证书。

## 日指标发布与正式新鲜度检查组合

run-factor-daily-metric-pipeline 新增可选 --freshness-plan PATH，接受既有正式新鲜度
Request JSON（signalDate 可空、asOf、labelDefinition、expected 四字段键矩阵）。
执行发布前读取有限大小的计划，校验日期、标签及键唯一性；发布回读通过后调用
DailyMetricPipelineService.inspectPublished，按实际发布信号日读取正式 metric/calendar 检查。
同一 pipeline 收据记录声明矩阵、freshness 结果、阶段失败及 pageSnapshotRefreshed=false。
非 fresh 拒绝通过；不改变 sourceComplete/factorReady，部分来源发布仍保持 PARTIAL。
metric-freshness-composition-compile.log 编译通过，尚未实际运行此组合分支。
页面快照刷新、正式来源完整性及自动 StageExecutor 接线仍待完成。

## 日指标与新鲜度组合实际运行通过物理检查

运行 run-factor-daily-metric-pipeline，省略 --date，由成熟日规划选择 2026-10-08，
asOf=2026-10-09；使用 four-pool-all-stock-bundle、four-pool-all-stock-values.json 和事前已有
formal-freshness-full-matrix-input.json。session 70042 正常终止 exit 3，无活跃进程。
run=factor-daily-metric-9ccb6a63-6c5f-48f5-a2df-f4694978ebf9，264 行全部发布回读通过，
receipt=metric-freshness-composed-live/2026-10-08-379e9c4b-3036-4528-ad59-a12f26f821fb/pipeline.json。
freshnessStatus=fresh、lagTradingDays=1，声明 264 键中 256 ready、8 constant/not_applicable，
missing=duplicate=0。coverageStatus=partial、sourceComplete=false、整体 PARTIAL，页面未刷新。
日志 metric-freshness-composed-live.log；仅此组合分支实际通过，不是完整盘后就绪。

重新读取 Python src/api/jobs/refresh_page_read_models.py：factor_builder.build_all 生成全部选择项，
PageSnapshotStore.refresh 发布可重建页面快照，unavailable 时拒绝并保留上一成功结果。
后续迁移需对齐该消费结构和保留旧快照语义，不能以 freshness JSON 替代页面快照。

## 页面历史统计基础计算迁移

读取 Python research_factor_performance.py、web/contracts/factor_performance.py、
factor_platform/factor_performance.py 和 page_snapshot_store.py。页面要求 5/20/60/252 日
窗口、原始及 pure 指标、选择项、overview 与诊断；SQLite publications/payloads 原子发布，
失败保留旧 payload。Java 当前尚无对应历史聚合及页面 projection。

新增 FactorPerformanceStatistics 数值基础：有效样本数、均值、ddof=1 标准差、ICIR、
Newey-West t（Python 相同 lag/Bartlett 权重）、正值比例及累计值；NaN 缺失，空序列累计为 0，
不足 3 样本不提供 HAC，零方差不提供 ICIR。无数据库副作用，尚未接入页面/窗口分组。
factor-performance-statistics-compile.log 编译通过；尚未与 Python 实际数值对照。
仍需历史读取与成熟过滤、版本分组和尾窗口、完整页面模型映射及快照发布/消费者接线。

## 历史尾窗口聚合代码

新增 FactorPerformanceWindows：正式日指标按池/池版本/因子/定义版本/标签/期限/评价配置
分组，每组排序一次，投影最近 5/20/60/252 条日期记录。剔除未来信号与尚未成熟记录，
重复日期业务键拒绝。方向显式按因子与定义版本提供，仅调整原始 rankIC；pure 无回退。
已计算均值、ICIR/HAC、正值比例、累计 IC/多空价差、覆盖、样本量、自相关与换手代理。
不足窗口保留 completeWindow=false，与 Python group.tail 的记录窗口口径一致。
factor-performance-windows-compile.log 编译通过；尚未执行 Python 数值对照。
十组分位收益、成员日期/控制版本等元数据、正式历史读取、页面模型及快照发布仍未接入。

## 历史窗口字段补齐与首次离线执行

FactorPerformanceWindows 补充十组分位收益均值、窗口内最后非空 controlDefinitionVersion、
整个成熟分组的最大 membershipAsOf、版本化方向。取值范围对应 Python _window_row /
build_factor_performance_windows。新增 compute-factor-performance-windows 离线命令，读取有界
rows JSON，使用当前目录版本化方向，输出带输入 SHA 和目录 SHA 的新文件，不写正式表或页面。

编译 factor-window-command-compile.log 通过。实际 session 82757 exit 0，264 条冻结单日日指标
产生 1056 个窗口记录（每组四窗口），输出 factor-performance-windows-live.json，日志同名 .log。
仅验证本地冻结数据的执行与输出链路，不证明 5/20/60/252 日窗口已有足够历史，也未证明 Python
数值一致性。正式历史读取、页面投影/快照、自动盘后接线仍未完成。

## 正式指标历史读取实际结果

新增 QuestDbDailyMetrics.readRange（日期/行数有界、重复键检查、读前后表身份/WAL稳定）和
compute-formal-factor-performance-windows。第一次正式读取因旧期限指标混入被一日键校验拒绝；
已增加带标签的 SQL 选择，在读取前限定 horizon=1、base_ic 和声明标签，保留原单日读取接口。
编译 formal-performance-history-compile.log 通过。
第二次 session 84579 exit 0，窗口 2025-01-01..2026-10-09、标签 close_hfq-fwd1-suspend-last-v2，
正式表只读返回 266 行，生成 1056 窗口，completeWindows=0。
证据 formal-performance-history-live.json、formal-performance-history-live-r2.log。
因此该标签的正式历史不足以支持最短 5 条完整窗口；不能将这些结果宣传为已有完整历史评价。

用户已明确说明主策略 promoted_recipe/accepted-sha256/正式计划库尚未迁移，要求先忽略。
这三项暂不作为当前因子工作阻塞或本轮验收要求，继续因子历史与页面链路。

## 历史窗口与原 Python 单日真实数据对照

新增 factor_window_python_parity.py：以已冻结 264 条 computation.rows 为共同输入，校验 Java
输出记录的输入 SHA；调用原 Python build_factor_performance_windows，按原 reader 口径提供
方向调整 IC、原 IC 和多空价差。逐项比较窗口统计、元数据和十组分位收益。
首次诊断脚本 decile1 驼峰映射漏下划线导致分位列为空；修正比较适配后原 Python 未改动。
实际 exit 0，1056 窗口、40128 字段无差异（数值容差 atol=1e-12、rtol=1e-10）。
结果 factor-window-python-parity.json 包含输入/输出 SHA 和验证范围。
输入为单日真实分组，不能证明多日标准差、ICIR/HAC 的数值一致性或完整历史就绪。
仍需多日对照、历史补算与页面发布/自动盘后接线。

## 正式旧标签多日历史与 Python 窗口统计对照

本地只读标签汇总：close_hfq-fwd1-v1 有 52395 行，2026-01-05..09-30；新 suspend-last-v2
仅 266 行，09-30..10-08；另存在 open_to_open_unadjusted 多期限历史。标签不可直接互换。
整个旧标签聚合遇当前 Java 目录未声明的 L2 定义而明确拒绝（原 session 23175 exit 2），
没有为未知定义推断方向。正式窗口命令新增显式 --factors 和 --rows-output，输出声明范围、
排除行数和共同冻结行 SHA，支持对同一真实多日输入调用原 Python。

reversal_1d 旧标签仅 2 行（session 68103 exit 0），不够多日验证；实际改用有历史的 reversal_20d：
session 58173 exit 0，743 条、169 个信号日、72 窗口，其中 30 个完整窗口。
legacy-reversal20-history-rows.json / windows.json 与原 Python build_factor_performance_windows
2736 个字段对照一致，legacy-reversal20-window-parity.json passed=true（数值 atol1e-12/rtol1e-10）。
覆盖实际多日 ICIR/HAC 与缺失 pure 分支，不代表全部因子/新标签历史已补齐；当前因子链仍不完整。

## 多信号日共享面板与按日池子凭证

FactorPanelComputation 新增 --pool-output-directory，与 --pool-output 二选一。一次共享来源读取、
时序计算和多池投影后，按信号日拆出合法 FactorPoolEvidence 文件，各自保持 128MiB/200万行
限制；每片通过 decode，再写 manifest 的日期/文件/SHA。总内存及观测预算仍有界。
factor-date-shards-compile.log 编译通过。

实际 session 30416 exit 0：2026-09-29..30、66 因子、四池，history-two-day-compute.json。
共享读 1699560 源行/5608 证券，read19.36秒、时序计算2.12秒、多池5.92秒；
396 个可用池子因子 section，投影74次构建/232次命中。
日凭证 09-29 32496032 字节、09-30 67699673 字节，manifest 与 observations bundle 已生成。
部分池子不具备目标日 PIT 成员会保留 unavailable 声明；总 section 不是两个日期全部四池均完整。
当前仅完成历史观察/池子产物计算，尚未执行两日新标签日指标评价发布，未认证来源完整。

## 历史补算先发布正式原始因子（运行中）

09-29 日指标尝试 session 53673 exit 1，DailyMetricInputGuard 在指标写入前拒绝：
Formal observations differ from calculation inputs。两日新产物须先进入正式观察表，再评价发布。
history-new-label-sep29.log 留档；没有绕过该校验。

本地 index_weight 只读汇总确认 000906(CSI800) 与 000985(CSI全指) 只有 2026-09-30 成员，
故不能用未来成员补 09-29；000300 历史至 08-31 可按 PIT 使用。
两日 observations bundle 共 599214 行/6片。启动串行 run-factor-observations INCREMENTAL，
当前 session_id=3698 仍 live（同一 write_stdin 轮询确认），首片有持续写入/可见性回读。
日志 history-publish-observations-000.log 等，未重启、未编译改变运行代码。
下一轮继续轮询同一 session；终止后逐片核对 run state/expected/verified 行数，
最终完整物理集合验证通过后再运行两日日指标。当前没有完成结论。

## 历史观察发布首片验证及新计数实际验收

继续同一 session 3698，已输出 finished observations-000.json，仍 live 执行第二片。
首片 run=factor-observation-63a1bf2a-d205-4d31-a58f-910cabcbf29c，PARTIAL，
sourceRows=verifiedRows=100000，errorCode=IllegalStateException 为来源不完整的完成状态。
新计数实际 attemptedDeltaRows=56932、sendReturnedDeltaRows=55804、sendExceptionDeltaRows=1128；
发送异常行由执行器后续回读确认，整个首片验证完成。计数代表尝试，不是全库唯一变更统计。
首片服务耗时172.30秒；正式表回读尚需覆盖后续全部分片。
当前 session 3698 仍 live，不重启、不编译，下一轮继续同一 handle。
## 当前范围与历史观察发布续跑

用户明确正式 promoted_recipe.json、accepted-sha256.json 和计划库尚未迁移，先忽略这些材料；
不作为当前因子迁移工作的阻断项，也不据此宣布主策略正式验收完成。
继续原 session 3698，前三片已完成（000、001、002），每片 sourceRows=verifiedRows=100000。
001 run=factor-observation-a8e8cd13-45a2-452a-b91e-aa28830641a1，PARTIAL 表示来源不完整。
新建只读 audit_history_observations.py，待所有写入终止后核对冻结文件 SHA、全业务键和值、
重复/缺失及前后物理代际/WAL 状态；目前尚未运行全量物理审计。
当前主要发布耗时为逐批 WAL 可见性等待，不能用计算内核加速倍数代表端到端加速。
## 两日补算审计与发布脚本

audit_history_publication_receipts.py 检查六片的终态、sourceRows/verifiedRows 对应 manifest 期望行数。
audit_history_observations.py 独立查询 132 个日期/因子/版本 section，核对全部 599214 个键和值。
publish_history_two_day_metrics.ps1 在观察发布及物理审计通过后依次处理 09-29、09-30；
每日日指标要求 publishedRowsVerified=true，拒绝 IN_DOUBT。
audit_history_daily_metrics.py 在两个发布终止后核对所有 39 字段及重复/缺失业务键，
保留非本次请求行，检查前后物理状态相同且 WAL 已落地。
截至本段记录，session 3698 已完成 000..004，每片100000行全回读，正在最后99214行片；
尚未启动上面三项审计及日指标发布脚本，不能把脚本存在视为运行通过。
## 两日历史观察正式全量核对通过

原串行 writer session 3698 已 exit 0，六片全部完成；
audit_history_publication_receipts.py exit 0：expectedRows=verifiedRows=599214。
audit_history_observations.py session 8231 exit 0：132 section、599214 全键值，passed=true、
sourceStable=true；缺失/重复/值差异为零，核对前后物理状态一致且 WAL 已落地。
已启动 publish_history_two_day_metrics.ps1，session 13482，先09-29再09-30；
仍按来源未认证的 PARTIAL 处理，不产生完整 FactorReady。当前日指标发布尚在运行。
## 两日日指标补算与新标签三日窗口对照完成

publish_history_two_day_metrics.ps1 session 13482 exit 0，两日日指标均 publishedRowsVerified=true：
09-29 run factor-daily-metric-52501d79-6ff8-4a30-b8d6-03dd31b362c1，132行全回读；
09-30 264行全回读，两个业务发布仍 PARTIAL/CLI exit3，表示来源/质量矩阵未完整。
audit_history_daily_metrics.py exit0：两日132/264行、全39字段无差异，无缺失/重复/额外请求行，
核对前后 factor_daily_metric 物理状态一致、table_txn=wal_txn=26、pending0/suspendedfalse。
09-29 四池矩阵264：ready126，缺两个池132，constant5，missing_effective1；
09-30 矩阵264：ready250，constant8，missing_effective6。不得将全部行写入误报成全部可评价。

compute-formal-factor-performance-windows session51239 exit0：新标签正式历史2026-09-29..10-09，
660行、3个信号日，1056窗口，completeWindows=0；最短窗口需要5个记录，历史不足。
冻结 new-label-three-day-rows.json / windows.json 后调用原 Python build_factor_performance_windows：
factor_window_python_parity.py exit0，1056窗口/40128字段通过，输入SHA
6d22b26e390de686d6fb2cd47071b74ff36d96632b072237721e712dff4ec35e；
报告 new-label-three-day-window-parity.json。验证当前冻结三日范围，不认证来源/标签等价或长期就绪。

当前本轮历史分片与两日日指标补算已完成，没有活跃 writer；尚待更长历史覆盖、
来源完整性凭证、页面快照和通用 post_close/scheduler 接线。
正式主策略配方/批准摘要/计划库按用户要求继续忽略。未提交或push。
