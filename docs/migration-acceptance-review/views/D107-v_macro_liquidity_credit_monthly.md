# D107 · v_macro_liquidity_credit_monthly 普通 View验收

优先级：P4；v0.2 待 Review；以下公式依据已导出 SQL，不代表已经执行验收。

[返回索引](../datasets.md)

## 1. 实际依赖与输出

- SQL直接依赖：`macro_liquidity_credit_monthly`。
- 输出列：`month`、`rmb_index_month_end`、`rmb_index_ret_1m`、`shibor_on_month_avg`、`shibor_1w_month_avg`、`shibor_3m_month_avg`、`shibor_1y_month_avg`、`lpr_1y_month_end`、`lpr_5y_month_end`、`shibor_slope_1w_3m_month_avg`、`shibor_slope_3m_1y_month_avg`、`gov_3y_month_end`、`gov_5y_month_end`、`gov_7y_month_end`、`gov_10y_month_end`、`aaa_mtn_3y_month_end`、`aaa_mtn_5y_month_end`、`aaa_mtn_7y_month_end`、`aaa_mtn_10y_month_end`、`aaa_bank_3y_month_end`、`aaa_bank_5y_month_end`、`aaa_bank_7y_month_end`、`aaa_bank_10y_month_end`、`credit_spread_aaa_gov_3y`、`credit_spread_aaa_gov_5y`、`credit_spread_aaa_gov_7y`、`credit_spread_aaa_gov_10y`、`spread_aaa_bank_gov_3y`、`spread_aaa_bank_gov_5y`、`spread_aaa_bank_gov_7y`、`spread_aaa_bank_gov_10y`、`spread_bank_mtn_3y`、`spread_bank_mtn_5y`、`spread_bank_mtn_7y`、`spread_bank_mtn_10y`、`term_spread`。
- 普通View验证实时查询语义；MV验证基表→刷新→查询。不能套用表的直接写入/UPSERT验收。

## 2. 专项用例：输入与精确预期

| 用例 | 隔离输入/动作 | 预期结果 |
|---|---|---|
| D107-V01 | 在隔离源 `macro_liquidity_credit_monthly` 准备两个不同 `month`，重点列 gov_10y_month_end、aaa_mtn_10y_month_end、credit_spread_aaa_gov_10y、term_spread 分别使用不同值、合法空值 | 对视图和 `macro_liquidity_credit_monthly` 使用同一范围/排序查询，全部列名、类型、行数、重复行重数及每个值完全一致；不能额外聚合、去重、补0或变换单位。 |
| D107-V02 | 在源 `macro_liquidity_credit_monthly` 更正第一日期重点列，再清空隔离源或查询不存在范围 | 普通视图下一次读取反映源当前可见更正；空源/空范围返回0行，不能回退另一旧cache。 |
| D107-V03 | 检查 `v_macro_liquidity_credit_monthly` 的依赖定义并尝试通过应用通用写接口提交行 | 依赖必须指向 `macro_liquidity_credit_monthly`；写入被应用拒绝。SELECT * 上游加减列时必须报契约漂移或走已批准版本迁移，不能静默改变DTO。 |

## 3. 每列对照方法

| 输出列 | 独立期望值来源 |
|---|---|
| `month` | `macro_liquidity_credit_monthly.month`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `rmb_index_month_end` | `macro_liquidity_credit_monthly.rmb_index_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `rmb_index_ret_1m` | `macro_liquidity_credit_monthly.rmb_index_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_on_month_avg` | `macro_liquidity_credit_monthly.shibor_on_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_1w_month_avg` | `macro_liquidity_credit_monthly.shibor_1w_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_3m_month_avg` | `macro_liquidity_credit_monthly.shibor_3m_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_1y_month_avg` | `macro_liquidity_credit_monthly.shibor_1y_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `lpr_1y_month_end` | `macro_liquidity_credit_monthly.lpr_1y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `lpr_5y_month_end` | `macro_liquidity_credit_monthly.lpr_5y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_slope_1w_3m_month_avg` | `macro_liquidity_credit_monthly.shibor_slope_1w_3m_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_slope_3m_1y_month_avg` | `macro_liquidity_credit_monthly.shibor_slope_3m_1y_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gov_3y_month_end` | `macro_liquidity_credit_monthly.gov_3y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gov_5y_month_end` | `macro_liquidity_credit_monthly.gov_5y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gov_7y_month_end` | `macro_liquidity_credit_monthly.gov_7y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gov_10y_month_end` | `macro_liquidity_credit_monthly.gov_10y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_mtn_3y_month_end` | `macro_liquidity_credit_monthly.aaa_mtn_3y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_mtn_5y_month_end` | `macro_liquidity_credit_monthly.aaa_mtn_5y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_mtn_7y_month_end` | `macro_liquidity_credit_monthly.aaa_mtn_7y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_mtn_10y_month_end` | `macro_liquidity_credit_monthly.aaa_mtn_10y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_bank_3y_month_end` | `macro_liquidity_credit_monthly.aaa_bank_3y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_bank_5y_month_end` | `macro_liquidity_credit_monthly.aaa_bank_5y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_bank_7y_month_end` | `macro_liquidity_credit_monthly.aaa_bank_7y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_bank_10y_month_end` | `macro_liquidity_credit_monthly.aaa_bank_10y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `credit_spread_aaa_gov_3y` | `macro_liquidity_credit_monthly.credit_spread_aaa_gov_3y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `credit_spread_aaa_gov_5y` | `macro_liquidity_credit_monthly.credit_spread_aaa_gov_5y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `credit_spread_aaa_gov_7y` | `macro_liquidity_credit_monthly.credit_spread_aaa_gov_7y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `credit_spread_aaa_gov_10y` | `macro_liquidity_credit_monthly.credit_spread_aaa_gov_10y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_aaa_bank_gov_3y` | `macro_liquidity_credit_monthly.spread_aaa_bank_gov_3y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_aaa_bank_gov_5y` | `macro_liquidity_credit_monthly.spread_aaa_bank_gov_5y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_aaa_bank_gov_7y` | `macro_liquidity_credit_monthly.spread_aaa_bank_gov_7y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_aaa_bank_gov_10y` | `macro_liquidity_credit_monthly.spread_aaa_bank_gov_10y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_bank_mtn_3y` | `macro_liquidity_credit_monthly.spread_bank_mtn_3y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_bank_mtn_5y` | `macro_liquidity_credit_monthly.spread_bank_mtn_5y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_bank_mtn_7y` | `macro_liquidity_credit_monthly.spread_bank_mtn_7y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_bank_mtn_10y` | `macro_liquidity_credit_monthly.spread_bank_mtn_10y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `term_spread` | `macro_liquidity_credit_monthly.term_spread`，保留原值/空值/类型；同范围全量行多重集比较。 |

## 4. 当前 SQL 基准

来源：[导出SQL](../../../schema-export/questdb-qdb-2026-09-28/views.sql)。执行前与目标 SHOW CREATE 结果核对；任何公式差异都必须逐项 review。

```sql
CREATE VIEW 'v_macro_liquidity_credit_monthly' AS ( 
SELECT * FROM macro_liquidity_credit_monthly
);
```

## 5. 通过条件与证据

- 保存 `D107-fixture-input.json`、`D107-expected.json`、`D107-actual.json`、逐列diff及SQL/参数；上述fixture仅验证计算逻辑，另外必须在有界真实来源窗口进行全列对照。
- 比较完整行多重集，不能去重后掩盖JOIN膨胀；空源、跨日/跨月和依赖缺失都要实际记录。
- MV另交付刷新状态/时间/源进度与超时结果；普通View不伪造独立刷新成功标记。
- 独立期望不得通过再次查询同一View自证；计算所用输入需冻结，不能用已修订后的源与旧结果错时比较。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
