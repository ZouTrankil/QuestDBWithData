# D098 · mv_retail_sentiment_daily_v1 物化视图验收

优先级：P4；v0.2 待 Review；以下公式依据已导出 SQL，不代表已经执行验收。

[返回索引](../datasets.md)

## 1. 实际依赖与输出

- SQL直接依赖：`l2_daily_features`。
- 输出列：`trade_date`、`avg_retail_ratio`、`avg_retail_entropy`、`total_retail_amount_yi`、`total_retail_net_inflow_yi`、`avg_rel_aggro`、`total_q1`、`total_q3`、`avg_wash_trade_ratio`、`total_spoof_count`、`total_manipulation_count`、`avg_mfi_score`、`total_main_net_yi`。
- 普通View验证实时查询语义；MV验证基表→刷新→查询。不能套用表的直接写入/UPSERT验收。

## 2. 专项用例：输入与精确预期

| 用例 | 隔离输入/动作 | 预期结果 |
|---|---|---|
| D098-V01 | 同日L2两行：gmm_retail_ratio=.2/.4、entropy=1/3、retail_total_amount=1e8/2e8、retail_funds_net_inflow=-1e8/3e8、mean_rel_aggro=2/4 | avg_retail_ratio=.3、avg_retail_entropy=2、total_retail_amount_yi=3、total_retail_net_inflow_yi=2、avg_rel_aggro=3。 |
| D098-V02 | 同两行q1=1/2、q3=3/4、wash_trade_ratio=.1/.3、spoof=2/5、fake_support=1/3、fake_pressure=2/4、mfi=10/20、main_net=-1e8/2e8 | total_q1=3、total_q3=7、avg_wash=.2、total_spoof=7、total_manipulation=10、avg_mfi=15、total_main_net_yi=1。 |
| D098-V03 | 一行fake_support_count=null而fake_pressure_count非空 | 当前SQL是sum(fake_support_count + fake_pressure_count)，不是两列分别sum；独立查询验证null算术后聚合结果。如业务要补0须先修改契约，不能偷偷改映射。 |
| D098-V04 | 更正一日L2行并另写次日；暂停MV刷新 | 更正日结果在刷新后替换，次日独立；刷新暂停时不能把旧缓存标ready；所有13输出列分别比较。 |

## 3. 每列对照方法

| 输出列 | 独立期望值来源 |
|---|---|
| `trade_date` | `ts`，按上述隔离算例独立复算。 |
| `avg_retail_ratio` | `avg(gmm_retail_ratio)`，按上述隔离算例独立复算。 |
| `avg_retail_entropy` | `avg(mean_retail_entropy)`，按上述隔离算例独立复算。 |
| `total_retail_amount_yi` | `sum(retail_total_amount) / 100000000.0`，按上述隔离算例独立复算。 |
| `total_retail_net_inflow_yi` | `sum(retail_funds_net_inflow) / 100000000.0`，按上述隔离算例独立复算。 |
| `avg_rel_aggro` | `avg(mean_rel_aggro)`，按上述隔离算例独立复算。 |
| `total_q1` | `sum(q1_count)`，按上述隔离算例独立复算。 |
| `total_q3` | `sum(q3_count)`，按上述隔离算例独立复算。 |
| `avg_wash_trade_ratio` | `avg(wash_trade_ratio)`，按上述隔离算例独立复算。 |
| `total_spoof_count` | `sum(spoof_count)`，按上述隔离算例独立复算。 |
| `total_manipulation_count` | `sum(fake_support_count + fake_pressure_count)`，按上述隔离算例独立复算。 |
| `avg_mfi_score` | `avg(mfi_score)`，按上述隔离算例独立复算。 |
| `total_main_net_yi` | `sum(main_net_inflow) / 100000000.0`，按上述隔离算例独立复算。 |

## 4. 当前 SQL 基准

来源：[导出SQL](../../../schema-export/questdb-qdb-2026-09-28/materialized-views.sql)。执行前与目标 SHOW CREATE 结果核对；任何公式差异都必须逐项 review。

```sql
CREATE MATERIALIZED VIEW 'mv_retail_sentiment_daily_v1' WITH BASE 'l2_daily_features' REFRESH EVERY 1m START '2026-09-18T15:32:22.955712Z' AS (
SELECT
    ts AS trade_date,
    avg(gmm_retail_ratio) AS avg_retail_ratio,
    avg(mean_retail_entropy) AS avg_retail_entropy,
    sum(retail_total_amount) / 100000000.0 AS total_retail_amount_yi,
    sum(retail_funds_net_inflow) / 100000000.0 AS total_retail_net_inflow_yi,
    avg(mean_rel_aggro) AS avg_rel_aggro,
    sum(q1_count) AS total_q1,
    sum(q3_count) AS total_q3,
    avg(wash_trade_ratio) AS avg_wash_trade_ratio,
    sum(spoof_count) AS total_spoof_count,
    sum(fake_support_count + fake_pressure_count) AS total_manipulation_count,
    avg(mfi_score) AS avg_mfi_score,
    sum(main_net_inflow) / 100000000.0 AS total_main_net_yi
FROM l2_daily_features

SAMPLE BY 1d ALIGN TO CALENDAR
) PARTITION BY MONTH;
```

## 5. 通过条件与证据

- 保存 `D098-fixture-input.json`、`D098-expected.json`、`D098-actual.json`、逐列diff及SQL/参数；上述fixture仅验证计算逻辑，另外必须在有界真实来源窗口进行全列对照。
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
