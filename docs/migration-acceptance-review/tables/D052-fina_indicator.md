# D052 · fina_indicator 表验收

优先级：P2。本文件仅验收 `fina_indicator`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/07-financial/D052-fina_indicator.md)

## 1. 已知结构与来源

- 旧快照：主时间 `ann_date`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`ts_code`, `ann_date`, `end_date`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/fina/fina_indicator.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/fina_indicator.py)（模型入口；不自动视为生产者）
- [src/quant_platform/research/factors/style_factors.py:383](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/factors/style_factors.py:383)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/factor_source_discovery.py:29](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/factor_source_discovery.py:29)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/industry_valuation/engine.py:396](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/industry_valuation/engine.py:396)（既有审计调用引用，实施时复核）

**已登记来源约束：** 普通接口按证券代码×公告日期窗，VIP路径按报告期；核查实际入口、权限、分页/触顶检测与修订。Python普通路径装饰器限流不能视为VIP路径同样受限；关联period_meta只通过其独立适配器更新。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D052-01 | TTM、季度 q_*、年度 yoy_* 同时有不同值 | 各指标对应独立源列，禁止把年值复制到季度值或同义扩展列 |
| D052-02 | 负利润、零分母、rd_exp 缺失 | 负值保留；比率不可自行生成无穷值；扩展 yoy_* 字段无源时须明确派生公式 |
| D052-03 | 将真实非空批次规范化后保存；先确认 `ts_code, ann_date, end_date` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `fina_indicator`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D052-04 | 对键 `ts_code, ann_date, end_date` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D052-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `fina_indicator` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D052-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts_code` | `SYMBOL` | 逐行比较源映射结果与 `fina_indicator.ts_code`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `ann_date` | `TIMESTAMP` | 逐行比较源映射结果与 `fina_indicator.ann_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `end_date` | `STRING` | 逐行比较源映射结果与 `fina_indicator.end_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `eps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dt_eps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.dt_eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_revenue_ps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.total_revenue_ps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `revenue_ps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.revenue_ps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `capital_rese_ps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.capital_rese_ps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `surplus_rese_ps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.surplus_rese_ps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `undist_profit_ps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.undist_profit_ps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `extra_item` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.extra_item`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `profit_dedt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.profit_dedt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gross_margin` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.gross_margin`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `current_ratio` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.current_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `quick_ratio` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.quick_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cash_ratio` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.cash_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `invturn_days` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.invturn_days`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `arturn_days` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.arturn_days`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `inv_turn` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.inv_turn`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ar_turn` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ar_turn`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ca_turn` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ca_turn`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fa_turn` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.fa_turn`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `assets_turn` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.assets_turn`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `op_income` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.op_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `valuechange_income` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.valuechange_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `interst_income` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.interst_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `daa` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.daa`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebit` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ebit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebitda` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ebitda`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fcff` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.fcff`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fcfe` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.fcfe`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `current_exint` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.current_exint`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `noncurrent_exint` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.noncurrent_exint`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `interestdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.interestdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `netdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.netdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tangible_asset` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.tangible_asset`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `working_capital` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.working_capital`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `networking_capital` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.networking_capital`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `invest_capital` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.invest_capital`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `retained_earnings` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.retained_earnings`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `diluted2_eps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.diluted2_eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocfps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocfps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `retainedps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.retainedps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cfps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.cfps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebit_ps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ebit_ps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fcff_ps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.fcff_ps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fcfe_ps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.fcfe_ps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `netprofit_margin` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.netprofit_margin`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `grossprofit_margin` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.grossprofit_margin`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cogs_of_sales` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.cogs_of_sales`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `expense_of_sales` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.expense_of_sales`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `profit_to_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.profit_to_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `saleexp_to_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.saleexp_to_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `adminexp_of_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.adminexp_of_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `finaexp_of_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.finaexp_of_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `impai_ttm` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.impai_ttm`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gc_of_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.gc_of_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `op_of_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.op_of_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebit_of_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ebit_of_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roe` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roe`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roe_waa` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roe_waa`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roe_dt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roe_dt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roa` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roa`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `npta` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.npta`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roic` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roic`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roe_yearly` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roe_yearly`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roa2_yearly` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roa2_yearly`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roe_avg` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roe_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `opincome_of_ebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.opincome_of_ebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `investincome_of_ebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.investincome_of_ebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_op_profit_of_ebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.n_op_profit_of_ebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tax_to_ebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.tax_to_ebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dtprofit_to_profit` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.dtprofit_to_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `salescash_to_or` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.salescash_to_or`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocf_to_or` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocf_to_or`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocf_to_opincome` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocf_to_opincome`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `capitalized_to_da` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.capitalized_to_da`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `debt_to_assets` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.debt_to_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `assets_to_eqt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.assets_to_eqt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dp_assets_to_eqt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.dp_assets_to_eqt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ca_to_assets` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ca_to_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `nca_to_assets` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.nca_to_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tbassets_to_totalassets` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.tbassets_to_totalassets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `int_to_talcap` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.int_to_talcap`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `eqt_to_talcapital` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.eqt_to_talcapital`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `currentdebt_to_debt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.currentdebt_to_debt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `longdeb_to_debt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.longdeb_to_debt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocf_to_shortdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocf_to_shortdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `debt_to_eqt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.debt_to_eqt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `eqt_to_debt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.eqt_to_debt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `eqt_to_interestdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.eqt_to_interestdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tangibleasset_to_debt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.tangibleasset_to_debt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tangasset_to_intdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.tangasset_to_intdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tangibleasset_to_netdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.tangibleasset_to_netdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocf_to_debt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocf_to_debt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocf_to_interestdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocf_to_interestdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocf_to_netdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocf_to_netdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebit_to_interest` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ebit_to_interest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `longdebt_to_workingcapital` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.longdebt_to_workingcapital`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebitda_to_debt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ebitda_to_debt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `turn_days` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.turn_days`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roa_yearly` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roa_yearly`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roa_dp` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roa_dp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fixed_assets` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.fixed_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `profit_prefin_exp` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.profit_prefin_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `non_op_profit` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.non_op_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `op_to_ebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.op_to_ebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `nop_to_ebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.nop_to_ebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocf_to_profit` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocf_to_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cash_to_liqdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.cash_to_liqdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cash_to_liqdebt_withinterest` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.cash_to_liqdebt_withinterest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `op_to_liqdebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.op_to_liqdebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `op_to_debt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.op_to_debt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roic_yearly` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roic_yearly`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_fa_trun` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.total_fa_trun`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `profit_to_op` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.profit_to_op`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_opincome` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_opincome`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_investincome` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_investincome`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_dtprofit` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_dtprofit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_eps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_netprofit_margin` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_netprofit_margin`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_gsprofit_margin` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_gsprofit_margin`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_exp_to_sales` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_exp_to_sales`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_profit_to_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_profit_to_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_saleexp_to_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_saleexp_to_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_adminexp_to_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_adminexp_to_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_finaexp_to_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_finaexp_to_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_impair_to_gr_ttm` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_impair_to_gr_ttm`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_gc_to_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_gc_to_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_op_to_gr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_op_to_gr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_roe` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_roe`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_dt_roe` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_dt_roe`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_npta` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_npta`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_opincome_to_ebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_opincome_to_ebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_investincome_to_ebt` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_investincome_to_ebt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_dtprofit_to_profit` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_dtprofit_to_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_salescash_to_or` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_salescash_to_or`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_ocf_to_sales` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_ocf_to_sales`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_ocf_to_or` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_ocf_to_or`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `basic_eps_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.basic_eps_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dt_eps_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.dt_eps_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cfps_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.cfps_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `op_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.op_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebt_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ebt_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `netprofit_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.netprofit_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dt_netprofit_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.dt_netprofit_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ocf_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.ocf_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roe_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.roe_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bps_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.bps_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `assets_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.assets_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `eqt_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.eqt_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tr_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.tr_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `or_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.or_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_gr_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_gr_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_gr_qoq` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_gr_qoq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_sales_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_sales_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_sales_qoq` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_sales_qoq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_op_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_op_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_op_qoq` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_op_qoq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_profit_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_profit_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_profit_qoq` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_profit_qoq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_netprofit_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_netprofit_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q_netprofit_qoq` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.q_netprofit_qoq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `equity_yoy` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.equity_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rd_exp` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.rd_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `update_flag` | `STRING` | 逐行比较源映射结果与 `fina_indicator.update_flag`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `yoy_equity` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_equity`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `growth_assets` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.growth_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_bps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `growth_profit` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.growth_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_eps` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_roe` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_roe`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_netprofit_margin` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_netprofit_margin`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_netprofit` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_netprofit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_assets` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_tr` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_tr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_or` | `DOUBLE` | 逐行比较源映射结果与 `fina_indicator.yoy_or`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `ann_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `ann_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts_code", "ann_date", "end_date", "eps", "dt_eps", "total_revenue_ps", "revenue_ps", "capital_rese_ps", "surplus_rese_ps", "undist_profit_ps", "extra_item", "profit_dedt", "gross_margin", "current_ratio", "quick_ratio", "cash_ratio", "invturn_days", "arturn_days", "inv_turn", "ar_turn", "ca_turn", "fa_turn", "assets_turn", "op_income", "valuechange_income", "interst_income", "daa", "ebit", "ebitda", "fcff", "fcfe", "current_exint", "noncurrent_exint", "interestdebt", "netdebt", "tangible_asset", "working_capital", "networking_capital", "invest_capital", "retained_earnings", "diluted2_eps", "bps", "ocfps", "retainedps", "cfps", "ebit_ps", "fcff_ps", "fcfe_ps", "netprofit_margin", "grossprofit_margin", "cogs_of_sales", "expense_of_sales", "profit_to_gr", "saleexp_to_gr", "adminexp_of_gr", "finaexp_of_gr", "impai_ttm", "gc_of_gr", "op_of_gr", "ebit_of_gr", "roe", "roe_waa", "roe_dt", "roa", "npta", "roic", "roe_yearly", "roa2_yearly", "roe_avg", "opincome_of_ebt", "investincome_of_ebt", "n_op_profit_of_ebt", "tax_to_ebt", "dtprofit_to_profit", "salescash_to_or", "ocf_to_or", "ocf_to_opincome", "capitalized_to_da", "debt_to_assets", "assets_to_eqt", "dp_assets_to_eqt", "ca_to_assets", "nca_to_assets", "tbassets_to_totalassets", "int_to_talcap", "eqt_to_talcapital", "currentdebt_to_debt", "longdeb_to_debt", "ocf_to_shortdebt", "debt_to_eqt", "eqt_to_debt", "eqt_to_interestdebt", "tangibleasset_to_debt", "tangasset_to_intdebt", "tangibleasset_to_netdebt", "ocf_to_debt", "ocf_to_interestdebt", "ocf_to_netdebt", "ebit_to_interest", "longdebt_to_workingcapital", "ebitda_to_debt", "turn_days", "roa_yearly", "roa_dp", "fixed_assets", "profit_prefin_exp", "non_op_profit", "op_to_ebt", "nop_to_ebt", "ocf_to_profit", "cash_to_liqdebt", "cash_to_liqdebt_withinterest", "op_to_liqdebt", "op_to_debt", "roic_yearly", "total_fa_trun", "profit_to_op", "q_opincome", "q_investincome", "q_dtprofit", "q_eps", "q_netprofit_margin", "q_gsprofit_margin", "q_exp_to_sales", "q_profit_to_gr", "q_saleexp_to_gr", "q_adminexp_to_gr", "q_finaexp_to_gr", "q_impair_to_gr_ttm", "q_gc_to_gr", "q_op_to_gr", "q_roe", "q_dt_roe", "q_npta", "q_opincome_to_ebt", "q_investincome_to_ebt", "q_dtprofit_to_profit", "q_salescash_to_or", "q_ocf_to_sales", "q_ocf_to_or", "basic_eps_yoy", "dt_eps_yoy", "cfps_yoy", "op_yoy", "ebt_yoy", "netprofit_yoy", "dt_netprofit_yoy", "ocf_yoy", "roe_yoy", "bps_yoy", "assets_yoy", "eqt_yoy", "tr_yoy", "or_yoy", "q_gr_yoy", "q_gr_qoq", "q_sales_yoy", "q_sales_qoq", "q_op_yoy", "q_op_qoq", "q_profit_yoy", "q_profit_qoq", "q_netprofit_yoy", "q_netprofit_qoq", "equity_yoy", "rd_exp", "update_flag", "yoy_equity", "growth_assets", "yoy_bps", "growth_profit", "yoy_eps", "yoy_roe", "yoy_netprofit_margin", "yoy_netprofit", "yoy_assets", "yoy_tr", "yoy_or"
FROM "fina_indicator"
WHERE "ann_date" >= :window_start
  AND "ann_date" < :window_end
ORDER BY "ts_code", "ann_date", "end_date"
LIMIT :page_size;
```

审计输出：`D052-source-normalized.jsonl`、`D052-actual.jsonl`、`D052-key-diff.json`、`D052-field-diff.json`、`D052-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
