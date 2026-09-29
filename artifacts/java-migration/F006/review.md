# F006 日期分片与分页

SlicePlanner 支持已覆盖范围的交易日历、代码×日期窗、月、季度和分类组合。窗口首尾均包含；月/季度的periodEnd是报告期标签，与裁剪窗口、公告日期分开。计划在发送请求前按maxSlices检查数量，不把全历史或笛卡尔积装入List。交易日历必须带覆盖区间和来源证据，不拿自然日冒充交易日。

PageContract 显式声明接口、字段、完整来源键、支持参数、NONE/OFFSET/CURSOR、完成判据、来源行上限和执行预算。PageExecutor 逐页校验并交给consumer，当前页消费结束才请求下一页；保留有上限的键摘要和页摘要，不聚合所有数据行。重复页、页内/跨页重复键、坏键、页数/行数耗尽、来源版本变化、游标缺失/不推进、源错误与consumer失败均返回Incomplete，不返回完成回执。正常0行不调用consumer。

NONE不自动加limit/offset。未分页结果达到上限时，先拒绝交付该页；AdaptiveSliceExecutor 可按明确声明的日期参数二分窗口，直到完整或最小窗口仍触顶。后者失败，不能把缩窗后的满页当完整。报告期片不能随意按日期二分。最大来源请求次数、总交付行数和最小窗口均有界。

TusharePageService 接入现有共享HTTP和F005预算，取消等待时取消实际source future；Tushare fields/items没有通用cursor/end marker，因此此适配器拒绝伪造cursor完成语义，其他来源可实现PageExecutor.Fetcher提供真实游标及终止标志。只有声明SHORT_PAGE适用的接口才能用短页结束。未知sourceVersion保持null，不声称跨页原子快照。

Python依据：`D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/etf/etf_daily_sync.py` 的按交易日2000行offset分页及键检查；`.../stock/reference_data/reference_data_sync.py` 的日期窗口及触顶缩窗。没有复制前者全页concat、隐式丢弃分页参数或后者最小窗触顶仍返回成功的风险。

## 验收

本轮显式运行 SlicePlannerTest、PageExecutorTest、PageExecutorBoundaryTest、TushareSliceAcceptanceTest 共15项通过，包含2个真实代码分片及future取消验证。随后整套 `gradlew test` 通过，同时覆盖新增 SlicePlannerBudgetTest 的计划超预算预检与LocalDate.MAX边界。首次旧测试期待“迭代后才报超预算”与更严格的前置拒绝不符，已按当前预检契约复核并通过。

失败测试覆盖重复页/重复键、游标停滞、页失败、版本变化、最小窗口仍满、页预算不足、取消发生在消费后、非法参数；缩窗样例跨闰日4天，7次有界请求仅交付4个完整单日叶子，截断父窗口没有交付行。

真实来源证据 `source-pages.json`：现有stock_basic分别过滤000001.SZ及600000.SH，串行两片各1页/1行；显式列映射、代码键和list_date严格解析通过。无limit/offset强加。stock_basic是当前快照，这里的计划日是逻辑执行日，不声明历史截面。2行保护上限来自单代码预期唯一身份，不代表接口全市场上限为2。实际多页offset/cursor来源尚未在此公共任务迁移；其失败语义由独立fixture测试证明。

复现：JAVA_HOME指向jdk-24.0.2，TUSHARE_PAGE_LIVE=1，执行 `.\gradlew.bat test --tests '*SlicePlannerTest' --tests '*PageExecutor*Test' --tests '*TushareSliceAcceptanceTest' --rerun-tasks --console=plain`。数据库写入/checkpoint对本分片功能N/A；consumer回执不是写入已验证，F008/F010/F011后续负责实际QuestDB写回读和checkpoint。人工复核 pending_review。

本轮观察到同目录出现其他写入产生的预算预检和边界测试，已保留、读取并纳入验证，未覆盖或撤销。已有用户build.gradle/application.yml/integration测试仍不纳入本任务修改。
