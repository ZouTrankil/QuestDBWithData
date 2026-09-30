# D113 · v_regime_features_monthly 普通 View验收

优先级：P4；v0.2 待 Review；以下公式依据已导出 SQL，不代表已经执行验收。

[返回索引](../datasets.md)

## 1. 实际依赖与输出

- SQL直接依赖：`regime_features_monthly`。
- 输出列：`month`、`hs300_ret_1m`、`zz500_ret_1m`、`all_a_ret_1m`、`cs1000_ret_1m`、`small_large_ret_1m`、`mid_large_ret_1m`、`growth_ret_1m`、`value_ret_1m`、`growth_value_ret_1m`、`energy_ret_1m`、`materials_ret_1m`、`industrials_ret_1m`、`consumer_discretionary_ret_1m`、`consumer_staples_ret_1m`、`healthcare_ret_1m`、`financials_ret_1m`、`it_ret_1m`、`telecom_ret_1m`、`utilities_ret_1m`、`energy_vs_all_a_1m`、`materials_vs_all_a_1m`、`industrials_vs_all_a_1m`、`consumer_discretionary_vs_all_a_1m`、`consumer_staples_vs_all_a_1m`、`healthcare_vs_all_a_1m`、`financials_vs_all_a_1m`、`it_vs_all_a_1m`、`telecom_vs_all_a_1m`、`utilities_vs_all_a_1m`、`avg_up_count`、`avg_down_count`、`avg_flat_count`、`avg_up_down_ratio`、`avg_limit_up_count`、`avg_limit_down_count`、`month_total_amount`、`avg_turnover_rate`、`median_turnover_rate`、`avg_pct_positive_ratio`、`avg_pct_negative_ratio`、`tradable_stock_count_month_end`、`usdcnh_month_end`、`usdcnh_ret_1m`、`shibor_on_month_avg`、`shibor_1w_month_avg`、`shibor_3m_month_avg`、`shibor_1y_month_avg`、`lpr_1y_month_end`、`lpr_5y_month_end`、`shibor_slope_1w_3m_month_avg`、`shibor_slope_3m_1y_month_avg`、`gov_3y_month_end`、`gov_5y_month_end`、`gov_7y_month_end`、`gov_10y_month_end`、`aaa_mtn_3y_month_end`、`aaa_mtn_5y_month_end`、`aaa_mtn_7y_month_end`、`aaa_mtn_10y_month_end`、`aaa_bank_3y_month_end`、`aaa_bank_5y_month_end`、`aaa_bank_7y_month_end`、`aaa_bank_10y_month_end`、`credit_spread_aaa_gov_3y`、`credit_spread_aaa_gov_5y`、`credit_spread_aaa_gov_7y`、`credit_spread_aaa_gov_10y`、`spread_aaa_bank_gov_3y`、`spread_aaa_bank_gov_5y`、`spread_aaa_bank_gov_7y`、`spread_aaa_bank_gov_10y`、`spread_bank_mtn_3y`、`spread_bank_mtn_5y`、`spread_bank_mtn_7y`、`spread_bank_mtn_10y`、`cpi_yoy`、`ppi_yoy`、`pmi_mfg`、`gdp_yoy`、`m2_yoy`、`social_financing_stock`、`new_rmb_loan`、`rmb_index_month_end`、`rmb_index_ret_1m`、`social_financing_yoy`、`term_spread`、`northbound_net_buy_1m`、`margin_balance_month_end`、`margin_balance_change_1m`、`all_a_pe_ttm_median`、`all_a_pb_median`、`all_a_pe_ttm_percentile`、`all_a_pb_percentile`、`erp`。
- 普通View验证实时查询语义；MV验证基表→刷新→查询。不能套用表的直接写入/UPSERT验收。

## 2. 专项用例：输入与精确预期

| 用例 | 隔离输入/动作 | 预期结果 |
|---|---|---|
| D113-V01 | 在隔离源 `regime_features_monthly` 准备两个不同 `month`，重点列 usdcnh_month_end、rmb_index_month_end、social_financing_yoy、erp 分别使用不同值、合法空值 | 对视图和 `regime_features_monthly` 使用同一范围/排序查询，全部列名、类型、行数、重复行重数及每个值完全一致；不能额外聚合、去重、补0或变换单位。 |
| D113-V02 | 在源 `regime_features_monthly` 更正第一日期重点列，再清空隔离源或查询不存在范围 | 普通视图下一次读取反映源当前可见更正；空源/空范围返回0行，不能回退另一旧cache。 |
| D113-V03 | 检查 `v_regime_features_monthly` 的依赖定义并尝试通过应用通用写接口提交行 | 依赖必须指向 `regime_features_monthly`；写入被应用拒绝。SELECT * 上游加减列时必须报契约漂移或走已批准版本迁移，不能静默改变DTO。 |

## 3. 每列对照方法

| 输出列 | 独立期望值来源 |
|---|---|
| `month` | `regime_features_monthly.month`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `hs300_ret_1m` | `regime_features_monthly.hs300_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `zz500_ret_1m` | `regime_features_monthly.zz500_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `all_a_ret_1m` | `regime_features_monthly.all_a_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `cs1000_ret_1m` | `regime_features_monthly.cs1000_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `small_large_ret_1m` | `regime_features_monthly.small_large_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `mid_large_ret_1m` | `regime_features_monthly.mid_large_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `growth_ret_1m` | `regime_features_monthly.growth_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `value_ret_1m` | `regime_features_monthly.value_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `growth_value_ret_1m` | `regime_features_monthly.growth_value_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `energy_ret_1m` | `regime_features_monthly.energy_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `materials_ret_1m` | `regime_features_monthly.materials_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `industrials_ret_1m` | `regime_features_monthly.industrials_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `consumer_discretionary_ret_1m` | `regime_features_monthly.consumer_discretionary_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `consumer_staples_ret_1m` | `regime_features_monthly.consumer_staples_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `healthcare_ret_1m` | `regime_features_monthly.healthcare_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `financials_ret_1m` | `regime_features_monthly.financials_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `it_ret_1m` | `regime_features_monthly.it_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `telecom_ret_1m` | `regime_features_monthly.telecom_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `utilities_ret_1m` | `regime_features_monthly.utilities_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `energy_vs_all_a_1m` | `regime_features_monthly.energy_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `materials_vs_all_a_1m` | `regime_features_monthly.materials_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `industrials_vs_all_a_1m` | `regime_features_monthly.industrials_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `consumer_discretionary_vs_all_a_1m` | `regime_features_monthly.consumer_discretionary_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `consumer_staples_vs_all_a_1m` | `regime_features_monthly.consumer_staples_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `healthcare_vs_all_a_1m` | `regime_features_monthly.healthcare_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `financials_vs_all_a_1m` | `regime_features_monthly.financials_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `it_vs_all_a_1m` | `regime_features_monthly.it_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `telecom_vs_all_a_1m` | `regime_features_monthly.telecom_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `utilities_vs_all_a_1m` | `regime_features_monthly.utilities_vs_all_a_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_up_count` | `regime_features_monthly.avg_up_count`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_down_count` | `regime_features_monthly.avg_down_count`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_flat_count` | `regime_features_monthly.avg_flat_count`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_up_down_ratio` | `regime_features_monthly.avg_up_down_ratio`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_limit_up_count` | `regime_features_monthly.avg_limit_up_count`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_limit_down_count` | `regime_features_monthly.avg_limit_down_count`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `month_total_amount` | `regime_features_monthly.month_total_amount`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_turnover_rate` | `regime_features_monthly.avg_turnover_rate`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `median_turnover_rate` | `regime_features_monthly.median_turnover_rate`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_pct_positive_ratio` | `regime_features_monthly.avg_pct_positive_ratio`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_pct_negative_ratio` | `regime_features_monthly.avg_pct_negative_ratio`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `tradable_stock_count_month_end` | `regime_features_monthly.tradable_stock_count_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `usdcnh_month_end` | `regime_features_monthly.usdcnh_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `usdcnh_ret_1m` | `regime_features_monthly.usdcnh_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_on_month_avg` | `regime_features_monthly.shibor_on_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_1w_month_avg` | `regime_features_monthly.shibor_1w_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_3m_month_avg` | `regime_features_monthly.shibor_3m_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_1y_month_avg` | `regime_features_monthly.shibor_1y_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `lpr_1y_month_end` | `regime_features_monthly.lpr_1y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `lpr_5y_month_end` | `regime_features_monthly.lpr_5y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_slope_1w_3m_month_avg` | `regime_features_monthly.shibor_slope_1w_3m_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `shibor_slope_3m_1y_month_avg` | `regime_features_monthly.shibor_slope_3m_1y_month_avg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gov_3y_month_end` | `regime_features_monthly.gov_3y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gov_5y_month_end` | `regime_features_monthly.gov_5y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gov_7y_month_end` | `regime_features_monthly.gov_7y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gov_10y_month_end` | `regime_features_monthly.gov_10y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_mtn_3y_month_end` | `regime_features_monthly.aaa_mtn_3y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_mtn_5y_month_end` | `regime_features_monthly.aaa_mtn_5y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_mtn_7y_month_end` | `regime_features_monthly.aaa_mtn_7y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_mtn_10y_month_end` | `regime_features_monthly.aaa_mtn_10y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_bank_3y_month_end` | `regime_features_monthly.aaa_bank_3y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_bank_5y_month_end` | `regime_features_monthly.aaa_bank_5y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_bank_7y_month_end` | `regime_features_monthly.aaa_bank_7y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `aaa_bank_10y_month_end` | `regime_features_monthly.aaa_bank_10y_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `credit_spread_aaa_gov_3y` | `regime_features_monthly.credit_spread_aaa_gov_3y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `credit_spread_aaa_gov_5y` | `regime_features_monthly.credit_spread_aaa_gov_5y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `credit_spread_aaa_gov_7y` | `regime_features_monthly.credit_spread_aaa_gov_7y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `credit_spread_aaa_gov_10y` | `regime_features_monthly.credit_spread_aaa_gov_10y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_aaa_bank_gov_3y` | `regime_features_monthly.spread_aaa_bank_gov_3y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_aaa_bank_gov_5y` | `regime_features_monthly.spread_aaa_bank_gov_5y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_aaa_bank_gov_7y` | `regime_features_monthly.spread_aaa_bank_gov_7y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_aaa_bank_gov_10y` | `regime_features_monthly.spread_aaa_bank_gov_10y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_bank_mtn_3y` | `regime_features_monthly.spread_bank_mtn_3y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_bank_mtn_5y` | `regime_features_monthly.spread_bank_mtn_5y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_bank_mtn_7y` | `regime_features_monthly.spread_bank_mtn_7y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `spread_bank_mtn_10y` | `regime_features_monthly.spread_bank_mtn_10y`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `cpi_yoy` | `regime_features_monthly.cpi_yoy`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `ppi_yoy` | `regime_features_monthly.ppi_yoy`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `pmi_mfg` | `regime_features_monthly.pmi_mfg`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `gdp_yoy` | `regime_features_monthly.gdp_yoy`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `m2_yoy` | `regime_features_monthly.m2_yoy`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `social_financing_stock` | `regime_features_monthly.social_financing_stock`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `new_rmb_loan` | `regime_features_monthly.new_rmb_loan`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `rmb_index_month_end` | `regime_features_monthly.rmb_index_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `rmb_index_ret_1m` | `regime_features_monthly.rmb_index_ret_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `social_financing_yoy` | `regime_features_monthly.social_financing_yoy`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `term_spread` | `regime_features_monthly.term_spread`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `northbound_net_buy_1m` | `regime_features_monthly.northbound_net_buy_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `margin_balance_month_end` | `regime_features_monthly.margin_balance_month_end`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `margin_balance_change_1m` | `regime_features_monthly.margin_balance_change_1m`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `all_a_pe_ttm_median` | `regime_features_monthly.all_a_pe_ttm_median`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `all_a_pb_median` | `regime_features_monthly.all_a_pb_median`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `all_a_pe_ttm_percentile` | `regime_features_monthly.all_a_pe_ttm_percentile`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `all_a_pb_percentile` | `regime_features_monthly.all_a_pb_percentile`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `erp` | `regime_features_monthly.erp`，保留原值/空值/类型；同范围全量行多重集比较。 |

## 4. 当前 SQL 基准

来源：[导出SQL](../../../schema-export/questdb-qdb-2026-09-28/views.sql)。执行前与目标 SHOW CREATE 结果核对；任何公式差异都必须逐项 review。

```sql
CREATE VIEW 'v_regime_features_monthly' AS ( 
SELECT * FROM regime_features_monthly
);
```

## 5. 通过条件与证据

- 保存 `D113-fixture-input.json`、`D113-expected.json`、`D113-actual.json`、逐列diff及SQL/参数；上述fixture仅验证计算逻辑，另外必须在有界真实来源窗口进行全列对照。
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
