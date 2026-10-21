# A 股正式同步接管清单

核对日期：2026-10-19。依据当前代码和公开默认配置，不代表运行进程的实际配置，也不代表生产数据已经验收。首批范围是 15 条核心来源和 5 条财务来源。

## 先处理的三个问题

1. Batch 的 `source_*` 仍是隔离链路。`SourceProductRunner` 固定生成 `jdb_test_*` 目标，`DurableWriter` 拒绝其他名称；`RuntimeConfiguration` 遇到 `jdb.production-enabled=true` 会直接拒绝启动。它不能通过打开开关接管正式表。
2. 正式表已有 `data.*` owner，但 15 条核心来源中，10 条在数据服务的代码默认值里仍指向隔离表。主策略检查正式绑定，不一致会阻断。常驻入口 `CnDataConfiguration.application(..., true, ...)` 已为其中十条设置正式绑定；有限 CLI 保留隔离默认值。仍须逐源确认进程实际绑定，再做有限范围验收，不能把 CLI 默认值当作生产配置。
3. `MainStrategyDailyPipeline` 已编排正式来源并消费其账本结果。生产接管应复用这些 owner 和写入协议。Batch 隔离结果不能充当正式表凭证，也不要新增第二套正式 writer。

## 核心来源

正式任务 ID 为 `data.<数据集>`；“默认正式”只说明绑定名称，不代表表存在、来源完整或已有成功运行。以下默认值来自各业务 `Configuration`，本机覆盖和启动参数可能改变它们。

| 数据集／正式表 | 来源 | 当前代码默认目标 | 首要验收点 |
| --- | --- | --- | --- |
| `exchange_calendar` | Tushare `trade_cal` | 正式表 | SSE/SZSE、闭市日、版本和未来交易日覆盖 |
| `daily` | Tushare `daily` | 正式表 | 按日期的预期证券范围、完整分页、修订回读 |
| `daily_basic` | Tushare `daily_basic` | 正式表 | 与批准证券范围对应，不凭当日有行判断完整 |
| `stk_factor` | Tushare 股票因子来源 | 正式表 | 当前注册字段契约、来源版本和完整键值 |
| `stk_suspend` | Tushare `suspend_d` | `stk_suspend_d011_isolated` | 稀疏事件及合法空源，正式绑定与恢复 |
| `stk_st_daily` | Tushare `namechange`＋日历 | `java_d012_stk_st_daily_acceptance` | 生效区间展开、历史变更、完整空源和换表恢复 |
| `stk_limit` | Tushare `stk_limit` | `java_d010_stk_limit_acceptance` | 上游先完成，最后提交以触发回测 MV；验证关联结果 |
| `moneyflow` | Tushare `moneyflow` | `java_d024_moneyflow_acceptance` | 完整日期范围、证券覆盖和分页上限 |
| `moneyflow_hsgt` | 现有 Tushare 北向资金契约 | `java_d027_moneyflow_hsgt_acceptance` | 当前接口可用范围、合法空源、历史修订；旧官网字段说明待核实 |
| `margin_detail` | Tushare `margin_detail` | `java_d029_margin_detail_acceptance` | 来源可用日期上限、证券范围；主策略允许的限制不能冒充全源完整 |
| `etf_daily` | Tushare `fund_daily` | `java_d014_etf_daily_acceptance` | ETF 范围、完整日期、写入中断及原响应恢复 |
| `etf_adj` | Tushare `fund_adj` | `java_d015_etf_adj_acceptance` | 复权修订、完整键值与重跑一致性 |
| `etf_factor` | Tushare `fund_factor_pro` | `java_d017_etf_factor_acceptance` | 账号权限、证券范围、日期与来源字段版本 |
| `etf_portfolio` | Tushare `fund_portfolio` | `java_d018_etf_portfolio_acceptance` | 按公告日采集；保留报告期和成分维度，验证晚到披露 |
| `cn_bond_yield_curve` | ChinaBond | 正式表 | 31 日有界窗口、曲线与期限键、真实源映射及回读 |

证据入口：`FormalSourceReadinessCommands` 登记上述 15 个正式 owner；`FormalSourceReadinessInspection` 检查目标绑定、覆盖运行及可选来源重验。它只清点和复核，不签发 DataReady，也不负责补历史。代码在 `markets/cn/main-strategy-runtime/src/main/java/com/zoutrankil/data/cli/` 和同模块的 `service/`。

历史启动不能仅靠已有表最大日期。当前主策略对没有基线的核心表要求显式历史初始化；完整历史范围、供应商可用起点和账号权限要逐源确定。

## 五条财务来源

统一由 `FinancialSyncOwner`、`FinancialWritePort` 写入同名正式表，任务为 `data.<数据集>`，共享数据同步账本。已实现的行为和命令见[财务同步](financial-sync.md)。

| 数据集／正式表 | 已有处理 | 生产验收缺口 |
| --- | --- | --- |
| `cashflow` | 单证券单季度；限定 `report_type=1`；修订择优 | 批准证券范围、历史报告期、新增/补披露/修订/恢复 |
| `income` | 同上 | 同上 |
| `express` | 公告日与报告期分别处理；完整键值核验 | 同上 |
| `forecast` | 原始响应冻结、冲突阻断和未决恢复 | 同上 |
| `fina_indicator` | 11 个无来源映射的历史列保留旧值，新行 NULL | 同上，并确认这些列的消费者允许缺值 |

当前证据只支持真实接口及表结构的只读核验、本地回归，不支持全市场正式接管结论。日批范围文件还需证券集合、有效期和批准摘要；历史起点按实际需求确定。

## 调度归属和消费者

| 入口 | 当前公开配置／行为 | 接管要求 |
| --- | --- | --- |
| A 股数据服务托管 Batch | `jdb.enabled=false`、`jdb.scheduling-enabled=false`；生产模式不可启用 | 用于现有隔离任务；不要当作正式表日批开关 |
| 数据服务财务日批 | `app.sync.financial.enabled=false`；默认 19:30 上海时间 | 配齐有效证券范围，完成正式写入和恢复验收后启用 |
| 独立主策略进程 | `main-strategy` profile 的日批启用；默认 20:35，启动补跑开启 | 启动前确认实际绑定、基线、凭证和批准文件；不能假定默认不运行 |
| 主策略数据 CLI | 有限命令，不启动常驻调度 | 先预检，再按明确范围执行 |

核心来源的主要消费者是回测 MV、情绪和状态特征、股票/ETF 因子及评分。`stk_limit` 提交顺序和 JOIN 右侧历史刷新需要单独核验。

首批 20 条也不是全部策略输入。下一批必须补 `stock_detail_info`、`etf_basic`、`etf_share`、L2 正式特征，以及它们依赖的历史资料和市场输入包。覆盖清单自动组装已经实现，仍需独立批准范围、真实上游运行和历史时点原件；见[来源核验](strategy-source-coverage.md)。

## 接管执行顺序

1. **确认实际状态**：导出进程实际目标绑定、表结构、账本覆盖运行和未决锁；分别记录代码能力与当前环境状态。旧运行成功或目标表有数据不能代替新范围的核验。
2. **固定范围**：每源登记证券集合及版本、历史起点、验收日期、来源权限和唯一正式 writer。与仍运行的其他 Java/Python 写入任务核对归属，消除重复写入。
3. **先验核心链**：日历与基础资料 → 股票行情/每日指标/因子 → 停牌/ST → 涨跌停和回测 MV；ETF、资金流、两融、债券按各自契约验收。先做有代表性的有限范围，再扩大到批准范围。
4. **逐源验证恢复**：正常写入、同范围重跑、晚到修订、中断后重启、未决写入处理。完整业务键和值、WAL 和来源/目标稳定版本都通过，才推进检查点。
5. **验收消费链**：真实来源凭证 → 因子认证 → 覆盖清单 → 评分/输入包 → 正式计划。缺证券、凭证变化或历史原件缺失时必须阻断。
6. **最后启用调度**：先完成连续交易日运行与恢复核验，再记录启用时间、调度所属进程、告警和停用方式。历史回补与日更分别固定范围，不靠无限重试补齐。

本轮完成代码与公开配置清点，没有改变开关、目标绑定或运行账本，没有执行真实采集与数据库写入。

## 本次逐表扩展

财务五表已补跨日分片，见[财务同步](financial-sync.md)。六张宏观统计表已注册正式 owner 和默认关闭的日批，见[宏观同步](macro-observation-sync.md)。这些是代码能力；原部署账本、真实补数和连续运行尚未验收。

三张利率表已补同一 v2 布局的正式 owner 和默认关闭的日批。实际缺键与 LPR 来源限制见[利率同步](interest-observation-sync.md)，不能把接口空响应当作已补齐报价。
