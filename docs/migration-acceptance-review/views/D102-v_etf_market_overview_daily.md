# D102 · v_etf_market_overview_daily 普通 View验收

优先级：P4；v0.2 待 Review；以下公式依据已导出 SQL，不代表已经执行验收。

[返回索引](../datasets.md)

## 1. 实际依赖与输出

- SQL直接依赖：`etf_daily`、`etf_share`。
- 输出列：`trade_date`、`etf_count`、`total_share`、`total_size_yi`。
- 普通View验证实时查询语义；MV验证基表→刷新→查询。不能套用表的直接写入/UPSERT验收。

## 2. 专项用例：输入与精确预期

| 用例 | 隔离输入/动作 | 预期结果 |
|---|---|---|
| D102-V01 | 同日etf_share A=10000、B=20000；etf_daily A.close=2、B.close=3 | 当日etf_count=2、total_share=30000、total_size_yi=(10000*2+20000*3)/10000=8。 |
| D102-V02 | 另加C份额但无同日价格；A再加下一日价格但无下一日份额 | INNER JOIN按代码+同timestamp连接，C及错日行不进入汇总；不能跨日ASOF补价格。 |
| D102-V03 | 同股票日期价格重复两行或份额重复 | count_distinct可能仍不变但sum被放大，必须检查输入唯一性并拒绝ready，不能只看etf_count。 |
| D102-V04 | 更正B同日close为4；两相邻日数据 | 该日total_size_yi变10，另一日不变；核对1d ALIGN TO CALENDAR分桶。 |

## 3. 每列对照方法

| 输出列 | 独立期望值来源 |
|---|---|
| `trade_date` | `s.timestamp`，按上述隔离算例独立复算。 |
| `etf_count` | `count_distinct(s.ts_code)`，按上述隔离算例独立复算。 |
| `total_share` | `sum(s.fd_share)`，按上述隔离算例独立复算。 |
| `total_size_yi` | `sum(s.fd_share * d.close) / 10000.0`，按上述隔离算例独立复算。 |

## 4. 当前 SQL 基准

来源：[导出SQL](../../../schema-export/questdb-qdb-2026-09-28/views.sql)。执行前与目标 SHOW CREATE 结果核对；任何公式差异都必须逐项 review。

```sql
CREATE VIEW 'v_etf_market_overview_daily' AS ( 
SELECT
    s.timestamp AS trade_date,
    count_distinct(s.ts_code) AS etf_count,
    sum(s.fd_share) AS total_share,
    sum(s.fd_share * d.close) / 10000.0 AS total_size_yi
FROM etf_share s
JOIN etf_daily d ON s.ts_code = d.ts_code AND s.timestamp = d.timestamp

SAMPLE BY 1d ALIGN TO CALENDAR
);
```

## 5. 通过条件与证据

- 保存 `D102-fixture-input.json`、`D102-expected.json`、`D102-actual.json`、逐列diff及SQL/参数；上述fixture仅验证计算逻辑，另外必须在有界真实来源窗口进行全列对照。
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
