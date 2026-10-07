# Java 主策略源数据独立验收（截至 2026-09-30）

结论：本次范围内五张正式源表通过；最新均为 2026-09-30。7 个 Java run VERIFIED，30 份按日源凭据 SHA256/日期/代码/唯一键校验通过，146,823 行来源记录与当前正式表按日行数一致。审计仅使用本地 SQLite 和 QuestDB 9000 只读 SQL，没有调用 provider、写库或运行测试。

## 六个交易日的正式表行数

| 日期 | stk_factor | daily_basic | stk_limit | moneyflow | etf_factor | v_backtest_daily |
|---|---:|---:|---:|---:|---:|---:|
| 20260922 | 5554 | 5554 | 5646 | 5554 | 2148 | 5567 |
| 20260923 | 5556 | 5556 | 5647 | 5556 | 2134 | 5568 |
| 20260924 | 5557 | 5557 | 5648 | 5569 | 2134 | 5569 |
| 20260928 | 5557 | 5557 | 5648 | 5569 | 2163 | 5569 |
| 20260929 | 5559 | 5559 | 5650 | 5571 | 2136 | 5571 |
| 20260930 | 5560 | 5561 | 5651 | 5572 | 2140 | 5571 |

上述五表各日期的行数等于唯一代码数；窗口内 `(ts_code,trade_date)` 重复组为 0，业务代码空值为 0；全表 trade_date 空值为 0。关键空值检查：factor/ETF 的 close、pre_close、amount、vol；daily_basic 的 close、turnover_rate、circ_mv、total_mv；stk_limit 的 up_limit、down_limit；moneyflow 的八个买卖量和 net_mf_vol、net_mf_amount；结果均为 0。未将全部可选技术指标要求为非空。

物理列数依次为 35 / 18 / 4 / 20 / 89，列名与 Java 源契约一致。所有表均 YEAR 分区、WAL、DEDUP，指定时间为 trade_date，upsert keys 为 ts_code+trade_date；审计时均无 WAL suspension，writerTxn=sequencerTxn，bufferedTxnSize=0。stk_factor 使用正式旧 API stk_factor 的 35 字段源契约，不是 stk_factor_pro 的 261 字段。

## Java VERIFIED 凭据

| run ID | 范围 | 已逐键全字段读回行数 |
|---|---|---:|
| `daily-basic-5bdc9a21-9fbc-4dc2-a143-4528d5d2b691` | 2026-09-22～2026-09-30 | 33344 |
| `etf-factor-d6e3e5ff-198c-4cc9-9304-e6dbf50ce5f7` | 2026-09-22～2026-09-30 | 12855 |
| `moneyflow-464eefa5-18b7-49f5-bbbd-d1ce9eb98a3a` | 2026-09-22～2026-09-24 | 16679 |
| `moneyflow-ae452f65-7f01-401c-8b6c-d666b3a9906b` | 2026-09-28～2026-09-30 | 16712 |
| `stk-factor-068f2f38-d20d-4b47-b7f1-5fd134d22518` | 2026-09-22～2026-09-24 | 16667 |
| `stk-factor-eba6ccdf-3b72-418e-b584-64b074e3e468` | 2026-09-28～2026-09-30 | 16676 |
| `stk-limit-ecde9425-a828-4c4e-a419-51995e2535df` | 2026-09-22～2026-09-30 | 33890 |

各 VERIFIED run 和每个日 slice 的 ledger verification 均 passed=true、expectedRows=matchedRows=actualRows，missingKeys/duplicateKeys/mismatchedRows 均为 0；终态与对应日志一致。JSON 保留原始 sourceFingerprint、源文件 SHA256、slice ID、table ID/WAL frontier、全物理类型及日志 SHA256。daily_basic 的 sourceFingerprint 按正式 canonical body 重新计算，另保留 source 文件和 raw response 文件 SHA256。

## 初始失败与恢复

- daily_basic 原 run `daily-basic-71780938-93af-4629-8203-66f52163a37d` 仍保留 FAILED / Incomplete / sourceRows=0；其关联 resume run `daily-basic-5bdc9a21-9fbc-4dc2-a143-4528d5d2b691` 为 VERIFIED。
- stk_limit 原 run `stk-limit-5e26ff2f-3f99-4bf3-b063-dad034b7755d` 仍保留 FAILED / Incomplete / sourceRows=0；其关联 resume run `stk-limit-ecde9425-a828-4c4e-a419-51995e2535df` 为 VERIFIED。
- 两次恢复均保留 parentRunId，相同正式 target、from/to 和明确 trade_dates。旧错误没有改成成功。原日志抑制了底层异常，不能单凭 Incomplete 证明确切锁异常；零源响应、当时已有凭证占用进程、现有 SharedRequestBudget 整进程独占锁路径和串行后成功共同支持凭证 lease 冲突诊断。

## 回测视图、IPO 差集与 ETF

数据库实际 SHOW CREATE VIEW 显示 v_backtest_daily 依赖 stk_factor、stk_limit、stk_suspend、stk_st_daily；四者 latest 均为 2026-09-30。视图以因子为基础并补停牌记录，无单独同步任务；六日行数=唯一代码数，close/up_limit/down_limit 空值为 0。停牌及 ST 表六日均有记录，完整 DDL 与按日行数保存在 JSON。

0930 daily/daily_basic 比 stk_factor 多的唯一代码是 001246.SZ。已发布 Java market source 的记录证明 listing_date=20260930，按精确代码+日期调用 stk_factor 返回 0 行；源文件 SHA256 与 VERIFIED publication scope 一致。按来源真实缺失接受，不补造 IPO 因子。stk_limit 与 moneyflow 的源范围还包含其它记录，因此不能强制它们行数等于 factor。

512770.SH、589330.SH 六日 ETF 因子记录全部存在；0930 close 分别为 2.374 / 0.858。fund_factor_pro 的正式 89 字段源也包含 OF 后缀记录，审计按该来源命名保留；不能把该源的全部记录均当作 SH/SZ 上市 ETF。

本地 SSE calendar 显示 9/25、26、27 休市，本窗口应验收六个交易日。

## 写入来源与验收边界

正式库已有此前 Python 同步产生的记录。本轮 Java 的 WebClient 源响应、原生 owner run/slice 和正式表完整键/全字段读回是独立新凭据；sourceRows/verifiedRows 统计提交并读回的源记录，不代表首次新增插入数，也不将全部历史物理行归属为 Java 新生成。此前 Python artifacts 位于 D:/work/fund_2/back-monitor/artifacts/main-strategy-refresh/20261007，本轮 Java artifacts 单独保存。

本报告覆盖五张源表及回测视图窗口；市场情绪发布、持仓披露时效、margin、Level2 全量验收由协调任务分别核对。本次未重新请求 provider，也未重复全量字段 SQL 对比；全字段正确性依据正式 Java owner 已持久化的逐键完整值读回回执，独立复核当前库行数/键/关键空值/结构与保留源凭据。

## 文件

- java-sync-data-audit.json：本报告结构化明细。
- java-sync-ledger-readonly.json：只读 ledger run/entry/event 原始证据。
- java-sync-source-receipts-readonly.json：30 份保留源凭据 SHA256、日期/代码/键检查。
- questdb-readonly.json、view-rows-readonly.json、view-dependencies-readonly.json、extra-coverage-readonly.json：原始只读 SQL 结果。
- market-sentiment-ledger.json：IPO 来源证明关联的正式发布账本原始证据。
