# D093 · v_backtest_daily 普通 View验收

优先级：P4；v0.2 待 Review；以下公式依据已导出 SQL，不代表已经执行验收。

[返回索引](../datasets.md)

## 1. 实际依赖与输出

- SQL直接依赖：`stk_factor`、`stk_limit`、`stk_st_daily`、`stk_suspend`。
- 输出列：`trade_date`、`ts_code`、`open`、`high`、`low`、`close`、`vol`、`amount`、`adj_factor`、`up_limit`、`down_limit`、`is_suspended`、`is_st`。
- 普通View验证实时查询语义；MV验证基表→刷新→查询。不能套用表的直接写入/UPSERT验收。

## 2. 专项用例：输入与精确预期

| 用例 | 隔离输入/动作 | 预期结果 |
|---|---|---|
| D093-V01 | stk_factor 放 A 日T价格 open=10/high=12/low=9/close=11、vol=100、amount=1100、adj_factor=2；stk_limit 为12/8；当日无停牌和ST行 | 输出A/T恰一行；价格/量额/因子保持原值，up/down_limit=12/8，is_suspended=0、is_st=0。 |
| D093-V02 | T+1 A无行情、存在停牌标记；T+1有B行情保证fd交易日存在；A最近历史行情为T的close=11、adj_factor=2 | A/T+1补一行，OHLC全为11，vol=amount=0，adj_factor=2，is_suspended为当天max；不能拿未来close。 |
| D093-V03 | A/T+2当日已有行情，同时有停牌事件 | ASOF得到当日行情时 f.trade_date < s.trade_date 不成立，补行分支不能再添第二行；检查基表其他join是否制造重复。 |
| D093-V04 | C当天停牌但此前没有任何stk_factor；另一停牌日期全市场无stk_factor交易日 | 按当前SQL两类均不补行：历史值不存在或fd中无该日期。不得编造昨收。 |
| D093-V05 | 同证券日期重复停牌行0、1；stk_limit或stk_st_daily放重复键 | 停牌子查询max只输出一行；后两表重复可能放大LEFT JOIN，验收必须发现并拒绝ready，不能仅比较总行数。 |

## 3. 每列对照方法

| 输出列 | 独立期望值来源 |
|---|---|
| `trade_date` | 正常b.trade_date；补停牌s.trade_date |
| `ts_code` | 正常b.ts_code；补停牌s.ts_code |
| `open` | 正常b.open；补停牌f.close |
| `high` | 正常b.high；补停牌f.close |
| `low` | 正常b.low；补停牌f.close |
| `close` | 正常b.close；补停牌f.close |
| `vol` | 正常b.vol；补停牌0.0 |
| `amount` | 正常b.amount；补停牌0.0 |
| `adj_factor` | 正常b.adj_factor；补停牌f.adj_factor |
| `up_limit` | l.up_limit，可空 |
| `down_limit` | l.down_limit，可空 |
| `is_suspended` | 正常coalesce(max(s.is_suspended),0)；补停牌s.is_suspended |
| `is_st` | 两分支coalesce(st.is_st,0) |

## 4. 当前 SQL 基准

来源：[导出SQL](../../../schema-export/questdb-qdb-2026-09-28/views.sql)。执行前与目标 SHOW CREATE 结果核对；任何公式差异都必须逐项 review。

```sql
CREATE VIEW 'v_backtest_daily' AS ( 
SELECT b.trade_date, b.ts_code, b.open, b.high, b.low, b.close,
       b.vol, b.amount, b.adj_factor, l.up_limit, l.down_limit,
       coalesce(s.is_suspended, 0) is_suspended, coalesce(st.is_st, 0) is_st
FROM stk_factor b
LEFT JOIN stk_limit l ON (b.trade_date = l.trade_date AND b.ts_code = l.ts_code)
LEFT JOIN (SELECT timestamp, ts_code, max(is_suspended) is_suspended
           FROM stk_suspend GROUP BY timestamp, ts_code) s ON (b.trade_date = s.timestamp AND b.ts_code = s.ts_code)
LEFT JOIN stk_st_daily st ON (b.trade_date = st.timestamp AND b.ts_code = st.ts_code)
UNION ALL
SELECT s.trade_date, s.ts_code, f.close AS open, f.close AS high,
       f.close AS low, f.close AS close, 0.0 AS vol, 0.0 AS amount,
       f.adj_factor, l.up_limit, l.down_limit, s.is_suspended,
       coalesce(st.is_st, 0) is_st
FROM ((SELECT timestamp AS trade_date, ts_code, max(is_suspended) is_suspended
      FROM stk_suspend GROUP BY timestamp, ts_code ORDER BY trade_date) TIMESTAMP(trade_date)) s
ASOF JOIN stk_factor f ON (ts_code)
LEFT JOIN stk_limit l ON (s.trade_date = l.trade_date AND s.ts_code = l.ts_code)
LEFT JOIN stk_st_daily st ON (s.trade_date = st.timestamp AND s.ts_code = st.ts_code)
JOIN (SELECT DISTINCT trade_date FROM stk_factor) fd ON s.trade_date = fd.trade_date
WHERE f.trade_date < s.trade_date
);
```

## 5. 通过条件与证据

- 保存 `D093-fixture-input.json`、`D093-expected.json`、`D093-actual.json`、逐列diff及SQL/参数；上述fixture仅验证计算逻辑，另外必须在有界真实来源窗口进行全列对照。
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
