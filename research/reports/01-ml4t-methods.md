# ML4Trading：先学数据契约和研究流程

本次逐项阅读了目标书仓库、`ml4t-data` 与 `ml4t-diagnostic` 的 18 份文档及源文件。没有执行全部案例或审计全库。文件、blob SHA 和链接在[阅读索引](../data/ml4t-reading-index.json)；书仓库数据固定在 commit `91c903e71c1d0f1b9015f18551fadbb60bcad132`。以下是可落到当前项目的结论。

## 1. 数据统一接口值得学，正式写入协议不能照搬

[BaseProvider](https://github.com/ml4t/data/blob/main/src/ml4t/data/providers/base.py) 把请求、限速、重试、转换和验证组织成共同入口；具体来源实现抓取与转换。当前代码使用 mixin，不应仅依据旧架构介绍照抄类层次。请求层区分暂时性网络错误、429/5xx 与不可重试错误，必要时参考 Retry-After。

[验证实现](https://github.com/ml4t/data/blob/main/src/ml4t/data/providers/mixins/validation.py) 使用标准时间、证券和 OHLCV 字段，检查非有限值、空值及重复键，并保留额外字段。它支持 strict、drop、warn 等策略。对探索数据，报告问题有价值；对正式准入，静默丢行会改变研究对象，不能默认使用。

[全局限速实现](https://github.com/ml4t/data/blob/main/src/ml4t/data/utils/global_rate_limit.py) 的共享状态在进程内内存中，“global”不等于多个进程共享持久配额。当前项目已有账本、冻结分页、WAL 与逐字段回读，责任更重。适合借鉴接口和验证顺序，不适合用这个研究库替换正式来源执行与恢复协议。

本次落实：Java 有界下载，Python 离线解析；每份原件保存 URL、取得时间、字节数和摘要；缺失与错误分开；Notebook 只使用经 manifest 指定的派生文件。没有把整个库装进生产链路。

## 2. 下载时点与历史可见性是两件事

[XBRL 下载器](https://github.com/stefan-jansen/machine-learning-for-trading/blob/91c903e71c1d0f1b9015f18551fadbb60bcad132/data/equities/fundamentals/xbrl_download.py) 展示了 Frames 与提交信息的连接。它有助于学习概念标签和报告期组织，但当前比较值可能包含后来修订。把一行各指标的最晚 filing date 作为 announcement date，是一种保守对齐规则，不等于重建了每个历史版本。

学习时要区分：财政期末、首次公告、修订公告、下载时间。固定滞后六个月也不能替代真实披露记录。年度累计现金流还需按同一财政年、指标、单位和修订版本差分；本地 Notebook 03 用明确标注的合成示例讲解，拒绝缺前季和重复季度，不把这个示例当真实季度数据。

## 3. ETF 案例的参数不是通用标准

[实际 setup.yaml](https://github.com/stefan-jansen/machine-learning-for-trading/blob/91c903e71c1d0f1b9015f18551fadbb60bcad132/case_studies/etfs/config/setup.yaml) 比概述更适合核对实验契约：100 只 ETF、成交额资格条件、不同标签周期、整股、每股佣金及分档价差。静态回看选择的股票池仍存在选择偏差；加入历史流动性过滤不会自动消除这个偏差。

本项目原实验只有 SPY、QQQ、IWM、TLT、GLD，采用碎股及比例成本。它是自己的五资产基线，不是复刻上游 100 ETF 实验。Notebook 01 展示差别，并继续调用已有 `target_weights → rebalance → simulate → metrics`。

## 4. 两个案例的适用边界

[公司特征案例](https://github.com/stefan-jansen/machine-learning-for-trading/tree/91c903e71c1d0f1b9015f18551fadbb60bcad132/case_studies/us_firm_characteristics) 使用匿名公司月度面板、57 个特征和较长训练窗；末尾一年的月度观察仍然很少。它适合学习截面组织和验证，不足以解释某家公司的 AI 投资回报。相关大数据约 GB 级，本次未下载。四家公司三年年报也不能冒充它的替代样本。

[永续资金费案例](https://github.com/stefan-jansen/machine-learning-for-trading/tree/91c903e71c1d0f1b9015f18551fadbb60bcad132/case_studies/crypto_perps_funding) 研究多币种、价格与真实结算现金流。它启发我们分清费率、名义本金、两腿价格和保证金。Notebook 02 只总结已有真实费率事件，未声称做成完整套利回测；溢价指数也不能充当实际资金费。

## 5. Diagnostic 与 RAG 优先学边界

[时间序列交叉验证说明](https://github.com/ml4t/diagnostic/blob/main/docs/user-guide/cross-validation.md) 强调训练、验证的时间顺序及标签跨度。purge 解决训练标签越过边界的问题，不能解决未来修订的财务数、全样本标准化或事后选择证券池。Notebook 06 对真实 BTC 收益做分阶段与连续块抽样；它没有把区间估计包装成通过选择偏差校正的策略技能。

[第 22 章](https://github.com/stefan-jansen/machine-learning-for-trading/tree/91c903e71c1d0f1b9015f18551fadbb60bcad132/22_rag_financial_research) 的价值在于把检索、计算、引用和拒答分别检验。Notebook 08 先做实体/年度的结构化检索与来源回链，缺 NVDA 资料就拒答。没有下载向量模型，也没有假装已部署 Agent。

## 6. 文档说有数据，还要检查真实树

该 commit 的数据说明提到预生成快照，但实际 Git 树中没有本次尝试的 ETF/XBRL 快照路径，请求返回 404；不能按文档把它们列为已取得。实际取得的是 AlgoSeek S&P 500 教学日线：原件 8.6 MB、635,703 行、638 个显示代码。

常规 OHLC 诊断发现 11 行非有限价格、44,479 行越界。越界主要涉及开盘值：21,404 行 open 高于 high，20,941 行 open 低于 low；这些分类可能重叠，不能相加作为总行数。可能涉及不同报价/时段定义，也可能是数据问题，当前证据未确定原因。下一步应核对字段定义和具体日期，不直接删行后发布策略成绩。

**建议阅读顺序：**数据说明与 Provider → 原案例 setup → 财务时间口径 → Diagnostic → RAG。完整模型、回测系统迁移与实盘部署延后。本包已经把这条顺序转成可执行教材。
