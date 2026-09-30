# D017 · etf_factor 表验收

优先级：P1。本文件仅验收 `etf_factor`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/04-etf/D017-etf_factor.md)

## 1. 已知结构与来源

- 旧快照：主时间 `trade_date`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`ts_code`, `trade_date`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/etf/market_data.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/etf/market_data.py)（模型入口；不自动视为生产者）
- [src/api/_terminal_market_data.py:127](/Users/Apple/zq/fun/back-monitor/src/api/_terminal_market_data.py:127)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/factor_source_discovery.py:257](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/factor_source_discovery.py:257)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/panels/etf_components.py:168](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/panels/etf_components.py:168)（既有审计调用引用，实施时复核）

**已登记来源约束：** fund_factor_pro按交易日，Python调用限流30/60s；逐日有界写入并检查截断；与原始行情/复权因子独立任务。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D017-01 | 长历史 ETF 完整指标和新 ETF 不足250日 | ema_bfq_250/ma_bfq_250 等缺值不填0；全部 bfq 列保留未复权口径 |
| D017-02 | 上/下涨天数与不同 RSI/KDJ 指标 | updays/downdays/lowdays/topdays 与各指标源列逐列核对，不复制A股字段名 |
| D017-03 | 将真实非空批次规范化后保存；先确认 `ts_code, trade_date` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `etf_factor`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D017-04 | 对键 `ts_code, trade_date` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D017-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `etf_factor` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D017-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts_code` | `SYMBOL` | 逐行比较源映射结果与 `etf_factor.ts_code`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `trade_date` | `TIMESTAMP` | 逐行比较源映射结果与 `etf_factor.trade_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `open` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.open`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `high` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.high`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `low` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.low`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `close` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.close`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pre_close` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.pre_close`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `change` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.change`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pct_change` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.pct_change`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vol` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.vol`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `amount` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `asi_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.asi_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `asit_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.asit_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bbi_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.bbi_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bias1_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.bias1_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bias2_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.bias2_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bias3_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.bias3_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `brar_ar_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.brar_ar_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `brar_br_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.brar_br_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cr_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.cr_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dfma_dif_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.dfma_dif_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dfma_difma_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.dfma_difma_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dpo_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.dpo_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `madpo_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.madpo_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ema_bfq_5` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ema_bfq_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ema_bfq_10` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ema_bfq_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ema_bfq_20` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ema_bfq_20`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ema_bfq_30` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ema_bfq_30`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ema_bfq_60` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ema_bfq_60`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ema_bfq_90` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ema_bfq_90`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ema_bfq_250` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ema_bfq_250`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `emv_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.emv_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `maemv_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.maemv_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `expma_12_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.expma_12_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `expma_50_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.expma_50_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ktn_down_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ktn_down_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ktn_mid_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ktn_mid_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ktn_upper_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ktn_upper_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ma_bfq_5` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ma_bfq_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ma_bfq_10` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ma_bfq_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ma_bfq_20` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ma_bfq_20`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ma_bfq_30` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ma_bfq_30`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ma_bfq_60` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ma_bfq_60`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ma_bfq_90` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ma_bfq_90`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ma_bfq_250` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ma_bfq_250`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `macd_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.macd_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `macd_dif_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.macd_dif_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `macd_dea_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.macd_dea_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `kdj_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.kdj_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `kdj_k_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.kdj_k_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `kdj_d_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.kdj_d_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rsi_bfq_6` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.rsi_bfq_6`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rsi_bfq_12` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.rsi_bfq_12`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rsi_bfq_24` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.rsi_bfq_24`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `boll_upper_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.boll_upper_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `boll_mid_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.boll_mid_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `boll_lower_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.boll_lower_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `atr_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.atr_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cci_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.cci_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dmi_pdi_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.dmi_pdi_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dmi_mdi_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.dmi_mdi_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dmi_adx_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.dmi_adx_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `dmi_adxr_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.dmi_adxr_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mass_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.mass_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ma_mass_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.ma_mass_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mfi_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.mfi_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mtm_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.mtm_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mtmma_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.mtmma_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obv_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.obv_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `psy_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.psy_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `psyma_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.psyma_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `roc_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.roc_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `maroc_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.maroc_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `taq_down_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.taq_down_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `taq_mid_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.taq_mid_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `taq_up_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.taq_up_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `trix_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.trix_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `trma_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.trma_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vr_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.vr_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `wr_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.wr_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `wr1_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.wr1_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `xsii_td1_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.xsii_td1_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `xsii_td2_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.xsii_td2_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `xsii_td3_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.xsii_td3_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `xsii_td4_bfq` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.xsii_td4_bfq`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `updays` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.updays`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `downdays` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.downdays`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lowdays` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.lowdays`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `topdays` | `DOUBLE` | 逐行比较源映射结果与 `etf_factor.topdays`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `trade_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `trade_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_change", "vol", "amount", "asi_bfq", "asit_bfq", "bbi_bfq", "bias1_bfq", "bias2_bfq", "bias3_bfq", "brar_ar_bfq", "brar_br_bfq", "cr_bfq", "dfma_dif_bfq", "dfma_difma_bfq", "dpo_bfq", "madpo_bfq", "ema_bfq_5", "ema_bfq_10", "ema_bfq_20", "ema_bfq_30", "ema_bfq_60", "ema_bfq_90", "ema_bfq_250", "emv_bfq", "maemv_bfq", "expma_12_bfq", "expma_50_bfq", "ktn_down_bfq", "ktn_mid_bfq", "ktn_upper_bfq", "ma_bfq_5", "ma_bfq_10", "ma_bfq_20", "ma_bfq_30", "ma_bfq_60", "ma_bfq_90", "ma_bfq_250", "macd_bfq", "macd_dif_bfq", "macd_dea_bfq", "kdj_bfq", "kdj_k_bfq", "kdj_d_bfq", "rsi_bfq_6", "rsi_bfq_12", "rsi_bfq_24", "boll_upper_bfq", "boll_mid_bfq", "boll_lower_bfq", "atr_bfq", "cci_bfq", "dmi_pdi_bfq", "dmi_mdi_bfq", "dmi_adx_bfq", "dmi_adxr_bfq", "mass_bfq", "ma_mass_bfq", "mfi_bfq", "mtm_bfq", "mtmma_bfq", "obv_bfq", "psy_bfq", "psyma_bfq", "roc_bfq", "maroc_bfq", "taq_down_bfq", "taq_mid_bfq", "taq_up_bfq", "trix_bfq", "trma_bfq", "vr_bfq", "wr_bfq", "wr1_bfq", "xsii_td1_bfq", "xsii_td2_bfq", "xsii_td3_bfq", "xsii_td4_bfq", "updays", "downdays", "lowdays", "topdays"
FROM "etf_factor"
WHERE "trade_date" >= :window_start
  AND "trade_date" < :window_end
ORDER BY "ts_code", "trade_date"
LIMIT :page_size;
```

审计输出：`D017-source-normalized.jsonl`、`D017-actual.jsonl`、`D017-key-diff.json`、`D017-field-diff.json`、`D017-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
