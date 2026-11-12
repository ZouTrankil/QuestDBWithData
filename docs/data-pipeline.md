# 同步和因子计算

目前同步主要在 Java 做：A 股的 `data` 子项目有按业务分组的同步任务，`batch` 子项目也有自己的来源编排和产品任务。两边的任务、目标和运行记录不同，不能因为数据集同名就互换。

## 从哪里看代码

| 要看什么 | 入口 |
| --- | --- |
| 当前有哪些数据同步任务 | `./gradlew run --args='show-sync-job-definitions'` |
| 股票、ETF、指数等同步流程 | `markets/cn/data` 下对应业务的 `application` |
| 通用运行记录、锁和恢复 | `SyncRunExecution`、`SyncJobRunner` 及相关端口 |
| Batch 来源采集 | `SourceCollector`、`SourceStrategies` |
| Batch 来源写入 | `SourceProductRunner`、`DurableWriter` |
| 因子面板和按池评价 | `FactorPanelComputation`、`DailyMetricPipelineService` |
| 因子分片发布和复用 | `FactorPipelineService`、`FactorObservationPublicationService` |
| ETF 因子计算 | `EtfFactorPanelComputation` |
| 主策略日批 | [主策略说明](main-strategy-java-data.md) |

## 一次同步怎样才算完成

1. 固定本次日期、证券范围、来源版本和请求预算，保留原始响应。
2. 检查分页是否完整结束、有没有重复页、缺日期或缺证券。
3. 在运行记录里保存写入意图，再发送数据。
4. 按本批完整业务键和值读回，检查 WAL 已应用，来源和目标没有在核验中变化。
5. 核验通过后，才写成功状态和检查点。

“请求成功”“发送成功”“数量一样”“最大日期更新了”都不能单独证明完成。来源确实允许为空时，也要有完整请求的证据，不能把接口报错当成空数据。

美股和 BTC 共用写入端口会区分 WAL 追赶、挂起、反向水位和状态不可用。发送后先等 WAL，再回读完整键值；等待逐步退避，期限内追平后继续原批次，不重发。超时仍保留原未决和锁；恢复原进程重启后的未决运行仍需现有显式恢复入口，不能把本次等待当成自动跨进程恢复。其他通用端口暂只报告 WAL 是否就绪。

已经存在的物理行不自动成为同步凭证。历史修订、补披露和晚到数据，要按各来源约定回看和核验。

## 因子计算怎样复用

同一交易日先生成共享的原始因子，再按池成员选择样本。池内去极值、标准化、中性化和评价仍要分别算，不能把一个池的结果直接给另一个池。

只有日期、来源版本、计算代码、因子定义和池计划等都相同，才复用保存的计算结果；复用前重新检查文件摘要和来源状态。输入变化后重算受影响的请求。当前不能据此宣称已经实现逐因子精确失效或跨日滚动状态持久化。

发布时比较完整记录，只写缺失或变化的行；没变化也要读回确认。大结果拆成分片，每片保留自己的运行记录和校验摘要。

部分结果可以按现有协议保存，但 `sourceComplete=false`、`factorReady=false` 不能因为写入成功就改成 true。通用盘后任务的 `FactorReady` 不等于这套链路已经完整接通；是否可用要看本次完整来源和下游验收。

## 回测输入来自哪里

当前主策略读取原生 MV `mv_backtest_daily`，直接关联行情、停牌、ST 和涨跌停来源。采集先完成其他来源，最后提交 `stk_limit`，由它触发刷新。

旧 Java 回测结果表发布流程已退役。JOIN 右侧单独修订不会自动变成“四表全部增量刷新”，需要按对应维护流程补齐和核验。

## 写到一半怎么办

保留原运行、来源文件、日志、锁和物理目标身份，先确认原写入进程是否已经结束，再按该任务的恢复入口处理。等待超时不等于进程已退出；结果不确定时不要另开任务重发。

ETF 的特定恢复方式见 [ETF 行情恢复](etf-daily-recovery.md)。Batch 的状态和恢复见[批处理说明](batch.md)。五条财务源已补同步及恢复入口，生产接管验收见[维护说明](questdb-maintenance.md#五条财务增量接管)。

## 已退役来源

`margin_zrz`、`moneyflow_dc`、`qmt_1m_bars` 的本地代码入口已移除。正式数据清理状态和操作条件见[退役记录](operations/cn-retirement/README.md)。其他两融、资金流、QMT 原生交易和实时 L2 不在删除范围。


BOOLEAN 未知值的断点看 `DatasetWritePreparation.prepareRows`、`VerifiedBatchExecutor.Codec.validateForWrite` 和两个 L2 Rows 的 `requireWritable`。BOOLEAN 不能持久保存 null，应在提交意图或 Sender 借用前失败，不能当成发送未决重试。正式摄取在 `boundedBatches` 先验完整缺口；旧不可保真数据仍按原件核对，见[准入决定](decisions/2026-10-21-QuestDB布尔空值写入准入.md)。

资料与公告的有限入口见[契约表同步](contract-table-sync.md)，`balance` 沿用财务队列。来源正向样本、键冲突阻断与正式接管分别记录，不把 CLI 代码接通当作日批已运行。

回购和大宗采用[显式v2范围快照](snapshot-event-sync.md)，保留重复记录；旧表迁移和日批启用分别验收，本轮没有正式写入。

指数日线正式表的trade_date布局已接[显式v2执行](index-daily-market-sync.md)，默认仍保留旧隔离值。只读来源核查不替代历史凭证，非空旧表缺原来源证据时仍拒绝接管。

ETF目录支持[精确同名正式目标及全目录回读](etf-basic-sync.md)，默认不切换。完整来源与保留旧行分别认证，周期更新及消费者仍待正式验收。

同花顺资金流支持[显式正式有界补数](moneyflow-ths-sync.md)，默认仍隔离。旧行不提供增量覆盖证据；整日回读失败保留未决锁，已验证日期可沿原请求恢复。

融资融券标的名单用[正式作业v2按日期窗补数](margin-secs-sync.md)，避免单日补数扫描整张历史表。原隔离v1、默认配置和旧请求恢复保持；正式窗口不认证范围外历史。

融资融券汇总支持[正式v2非去重范围发布](margin-all-sync.md)，默认不切换。范围外多重集合原样保留；开休市来源、代际读取和物理恢复已验证，已发布分支的原运行最终认证已接通，未发布stage已支持显式取消及按原冻结请求整窗重采，正式范围队列与调度仍待验收。

汇总表已有[显式原运行收尾](margin-all-run-recovery.md)，不会重发数据；凭证落盘故障留锁，最终核验和原账本认证后才解锁。私有原生实例已验证后续更新，正式生产范围仍待接管。
