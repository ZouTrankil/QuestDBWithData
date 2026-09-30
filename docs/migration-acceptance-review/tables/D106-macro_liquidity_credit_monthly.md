# D106 · macro_liquidity_credit_monthly 表验收

优先级：P4。本文件仅验收 `macro_liquidity_credit_monthly`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/11-derived/D106-macro_liquidity_credit_monthly.md)

## 1. 已知结构与来源

- 旧快照：主时间 `month`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`month`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/macro/macro_liquidity_credit_monthly.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/macro/macro_liquidity_credit_monthly.py)（模型入口；不自动视为生产者）
- [src/quant_platform/data/derived/workflows.py:42](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/derived/workflows.py:42)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/derived/regime/market_monthly.py:203](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/derived/regime/market_monthly.py:203)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/derived/regime/features_monthly.py:18](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/derived/regime/features_monthly.py:18)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D106-01 | 某月利率每日样本与月末最后有效报价不同 | shibor_*_month_avg按实际样本均值，lpr/gov/AAA_*_month_end按冻结取末规则 |
| D106-02 | 3/5/7/10年国债、中票、银行债不同 | credit_spread/spread_bank_mtn/term_spread逐列写明被减数/减数，缺一腿不填0 |
| D106-03 | 将真实非空批次规范化后保存；先确认 `month` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `macro_liquidity_credit_monthly`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D106-04 | 对键 `month` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D106-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `macro_liquidity_credit_monthly` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D106-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `month` | `TIMESTAMP` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.month`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `rmb_index_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.rmb_index_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rmb_index_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.rmb_index_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_on_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.shibor_on_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_1w_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.shibor_1w_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_3m_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.shibor_3m_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_1y_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.shibor_1y_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lpr_1y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.lpr_1y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lpr_5y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.lpr_5y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_slope_1w_3m_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.shibor_slope_1w_3m_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_slope_3m_1y_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.shibor_slope_3m_1y_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gov_3y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.gov_3y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gov_5y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.gov_5y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gov_7y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.gov_7y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gov_10y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.gov_10y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_mtn_3y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.aaa_mtn_3y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_mtn_5y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.aaa_mtn_5y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_mtn_7y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.aaa_mtn_7y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_mtn_10y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.aaa_mtn_10y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_bank_3y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.aaa_bank_3y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_bank_5y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.aaa_bank_5y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_bank_7y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.aaa_bank_7y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_bank_10y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.aaa_bank_10y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_spread_aaa_gov_3y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.credit_spread_aaa_gov_3y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_spread_aaa_gov_5y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.credit_spread_aaa_gov_5y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_spread_aaa_gov_7y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.credit_spread_aaa_gov_7y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_spread_aaa_gov_10y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.credit_spread_aaa_gov_10y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_aaa_bank_gov_3y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.spread_aaa_bank_gov_3y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_aaa_bank_gov_5y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.spread_aaa_bank_gov_5y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_aaa_bank_gov_7y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.spread_aaa_bank_gov_7y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_aaa_bank_gov_10y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.spread_aaa_bank_gov_10y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bank_mtn_3y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.spread_bank_mtn_3y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bank_mtn_5y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.spread_bank_mtn_5y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bank_mtn_7y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.spread_bank_mtn_7y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bank_mtn_10y` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.spread_bank_mtn_10y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `term_spread` | `DOUBLE` | 逐行比较源映射结果与 `macro_liquidity_credit_monthly.term_spread`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `month` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `month` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "month", "rmb_index_month_end", "rmb_index_ret_1m", "shibor_on_month_avg", "shibor_1w_month_avg", "shibor_3m_month_avg", "shibor_1y_month_avg", "lpr_1y_month_end", "lpr_5y_month_end", "shibor_slope_1w_3m_month_avg", "shibor_slope_3m_1y_month_avg", "gov_3y_month_end", "gov_5y_month_end", "gov_7y_month_end", "gov_10y_month_end", "aaa_mtn_3y_month_end", "aaa_mtn_5y_month_end", "aaa_mtn_7y_month_end", "aaa_mtn_10y_month_end", "aaa_bank_3y_month_end", "aaa_bank_5y_month_end", "aaa_bank_7y_month_end", "aaa_bank_10y_month_end", "credit_spread_aaa_gov_3y", "credit_spread_aaa_gov_5y", "credit_spread_aaa_gov_7y", "credit_spread_aaa_gov_10y", "spread_aaa_bank_gov_3y", "spread_aaa_bank_gov_5y", "spread_aaa_bank_gov_7y", "spread_aaa_bank_gov_10y", "spread_bank_mtn_3y", "spread_bank_mtn_5y", "spread_bank_mtn_7y", "spread_bank_mtn_10y", "term_spread"
FROM "macro_liquidity_credit_monthly"
WHERE "month" >= :window_start
  AND "month" < :window_end
ORDER BY "month"
LIMIT :page_size;
```

审计输出：`D106-source-normalized.jsonl`、`D106-actual.jsonl`、`D106-key-diff.json`、`D106-field-diff.json`、`D106-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
