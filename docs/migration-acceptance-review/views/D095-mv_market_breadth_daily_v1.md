# D095 · mv_market_breadth_daily_v1 物化视图验收

优先级：P4；v0.2 待 Review；以下公式依据已导出 SQL，不代表已经执行验收。

[返回索引](../datasets.md)

## 1. 实际依赖与输出

- SQL直接依赖：`stk_factor`。
- 输出列：`trade_date`、`stock_count`、`up_count`、`down_count`、`flat_count`、`avg_pct_change`、`total_amount_yi`。
- 普通View验证实时查询语义；MV验证基表→刷新→查询。不能套用表的直接写入/UPSERT验收。

## 2. 专项用例：输入与精确预期

| 用例 | 隔离输入/动作 | 预期结果 |
|---|---|---|
| D095-V01 | 同一日stk_factor四行pct_change=[2,-1,0,null]、amount=[100000,200000,300000,400000] | stock_count=4、up=1/down=1/flat=1、avg_pct_change=1/3、total_amount_yi=10；null涨幅不属于平盘，三类和可以小于总数。 |
| D095-V02 | 另一个交易日一条pct_change=5、amount=500000 | 独立日桶stock_count=1、up=1、avg=5、total_amount_yi=5；不能跨日汇总。 |
| D095-V03 | 在V01的原始四行基础上，将第一行pct_change由2修订为-2、amount改为200000 | 刷新后up=0/down=2/flat=1、stock_count=4、avg=-1、total_amount_yi=11；原聚合被纠正不叠加。 |
| D095-V04 | 隔离基表提交后MV尚未刷新或刷新失败 | 不能凭基表ACK报告通过；等待有界刷新，保留有效状态/进度及全部七列比对；超时为失败或blocked。 |

## 3. 每列对照方法

| 输出列 | 独立期望值来源 |
|---|---|
| `trade_date` | `trade_date`，按SQL designated timestamp 的1日 ALIGN TO CALENDAR桶对齐。 |
| `stock_count` | `count()`，按上述隔离算例独立复算。 |
| `up_count` | `sum(CASE WHEN pct_change > 0 THEN 1 ELSE 0 END)`，按上述隔离算例独立复算。 |
| `down_count` | `sum(CASE WHEN pct_change < 0 THEN 1 ELSE 0 END)`，按上述隔离算例独立复算。 |
| `flat_count` | `sum(CASE WHEN pct_change = 0 THEN 1 ELSE 0 END)`，按上述隔离算例独立复算。 |
| `avg_pct_change` | `avg(pct_change)`，按上述隔离算例独立复算。 |
| `total_amount_yi` | `sum(amount) / 100000.0`，按上述隔离算例独立复算。 |

## 4. 当前 SQL 基准

来源：[导出SQL](../../../schema-export/questdb-qdb-2026-09-28/materialized-views.sql)。执行前与目标 SHOW CREATE 结果核对；任何公式差异都必须逐项 review。

```sql
CREATE MATERIALIZED VIEW 'mv_market_breadth_daily_v1' WITH BASE 'stk_factor' REFRESH EVERY 1m START '2026-09-18T15:32:22.866888Z' AS (
SELECT
    trade_date,
    count() AS stock_count,
    sum(CASE WHEN pct_change > 0 THEN 1 ELSE 0 END) AS up_count,
    sum(CASE WHEN pct_change < 0 THEN 1 ELSE 0 END) AS down_count,
    sum(CASE WHEN pct_change = 0 THEN 1 ELSE 0 END) AS flat_count,
    avg(pct_change) AS avg_pct_change,
    sum(amount) / 100000.0 AS total_amount_yi
FROM stk_factor

SAMPLE BY 1d ALIGN TO CALENDAR
) PARTITION BY MONTH;
```

## 5. 通过条件与证据

- 保存 `D095-fixture-input.json`、`D095-expected.json`、`D095-actual.json`、逐列diff及SQL/参数；上述fixture仅验证计算逻辑，另外必须在有界真实来源窗口进行全列对照。
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
