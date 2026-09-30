# D099 · v_retail_sentiment_daily 普通 View验收

优先级：P4；v0.2 待 Review；以下公式依据已导出 SQL，不代表已经执行验收。

[返回索引](../datasets.md)

## 1. 实际依赖与输出

- SQL直接依赖：`mv_retail_sentiment_daily_v1`。
- 输出列：`trade_date`、`avg_retail_ratio`、`avg_retail_entropy`、`total_retail_amount_yi`、`total_retail_net_inflow_yi`、`avg_rel_aggro`、`total_q1`、`total_q3`、`avg_wash_trade_ratio`、`total_spoof_count`、`total_manipulation_count`、`avg_mfi_score`、`total_main_net_yi`。
- 普通View验证实时查询语义；MV验证基表→刷新→查询。不能套用表的直接写入/UPSERT验收。

## 2. 专项用例：输入与精确预期

| 用例 | 隔离输入/动作 | 预期结果 |
|---|---|---|
| D099-V01 | 在隔离源 `mv_retail_sentiment_daily_v1` 准备两个不同 `trade_date`，重点列 total_retail_net_inflow_yi、total_manipulation_count、avg_mfi_score、total_main_net_yi 分别使用不同值、合法空值 | 对视图和 `mv_retail_sentiment_daily_v1` 使用同一范围/排序查询，全部列名、类型、行数、重复行重数及每个值完全一致；不能额外聚合、去重、补0或变换单位。 |
| D099-V02 | 在源 `mv_retail_sentiment_daily_v1` 更正第一日期重点列，再清空隔离源或查询不存在范围 | 普通视图下一次读取反映源当前可见更正；空源/空范围返回0行，不能回退另一旧cache。 |
| D099-V03 | 检查 `v_retail_sentiment_daily` 的依赖定义并尝试通过应用通用写接口提交行 | 依赖必须指向 `mv_retail_sentiment_daily_v1`；写入被应用拒绝。SELECT * 上游加减列时必须报契约漂移或走已批准版本迁移，不能静默改变DTO。 |
| D099-V04 | 基表写入但底层MV尚未刷新 | 该普通视图只是透传MV旧值，不能绕过底层MV有效性/覆盖检查宣称最新；先通过对应MV的刷新用例。 |

## 3. 每列对照方法

| 输出列 | 独立期望值来源 |
|---|---|
| `trade_date` | `mv_retail_sentiment_daily_v1.trade_date`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_retail_ratio` | `mv_retail_sentiment_daily_v1.avg_retail_ratio`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_retail_entropy` | `mv_retail_sentiment_daily_v1.avg_retail_entropy`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `total_retail_amount_yi` | `mv_retail_sentiment_daily_v1.total_retail_amount_yi`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `total_retail_net_inflow_yi` | `mv_retail_sentiment_daily_v1.total_retail_net_inflow_yi`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_rel_aggro` | `mv_retail_sentiment_daily_v1.avg_rel_aggro`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `total_q1` | `mv_retail_sentiment_daily_v1.total_q1`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `total_q3` | `mv_retail_sentiment_daily_v1.total_q3`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_wash_trade_ratio` | `mv_retail_sentiment_daily_v1.avg_wash_trade_ratio`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `total_spoof_count` | `mv_retail_sentiment_daily_v1.total_spoof_count`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `total_manipulation_count` | `mv_retail_sentiment_daily_v1.total_manipulation_count`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `avg_mfi_score` | `mv_retail_sentiment_daily_v1.avg_mfi_score`，保留原值/空值/类型；同范围全量行多重集比较。 |
| `total_main_net_yi` | `mv_retail_sentiment_daily_v1.total_main_net_yi`，保留原值/空值/类型；同范围全量行多重集比较。 |

## 4. 当前 SQL 基准

来源：[导出SQL](../../../schema-export/questdb-qdb-2026-09-28/views.sql)。执行前与目标 SHOW CREATE 结果核对；任何公式差异都必须逐项 review。

```sql
CREATE VIEW 'v_retail_sentiment_daily' AS ( 
SELECT * FROM mv_retail_sentiment_daily_v1
);
```

## 5. 通过条件与证据

- 保存 `D099-fixture-input.json`、`D099-expected.json`、`D099-actual.json`、逐列diff及SQL/参数；上述fixture仅验证计算逻辑，另外必须在有界真实来源窗口进行全列对照。
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
