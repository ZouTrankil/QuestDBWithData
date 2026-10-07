# 主策略源数据、ETF 与两融验收记录

目标日期：**2026-09-30**。本次只读核对本地 QuestDB `127.0.0.1:9000`，日数据检查窗口为 **2026-09-22–2026-09-30**，包含 9 月 22、23、24、28、29、30 日六个交易日。复用已完成 Java 同步回执、已执行的两融源诊断和既有 ETF 全量验收证据；本次未新增 provider 请求、未写库、未运行任务或测试。

## 1. 当前入库结果

以下唯一性为每日 `(ts_code, 日期)` 检查；六日内均无重复键、代码空值。`etf_daily` 的日期字段是 `timestamp`，其他日表是 `trade_date`。

| 表 | 截至目标日期的最新记录 | 9 月 30 日行数 | 关键字段检查 | 结论 |
| --- | --- | ---: | --- | --- |
| `stk_factor` | 2026-09-30 | 5560 | `close`、`adj_factor` 无空值，均为正 | 已补至目标日 |
| `daily_basic` | 2026-09-30 | 5561 | `close`、`total_mv`、`circ_mv` 无空值 | 已补至目标日 |
| `stk_limit` | 2026-09-30 | 5651 | 上下限无空值，无 `up_limit < down_limit` | 已补至目标日 |
| `moneyflow` | 2026-09-30 | 5572 | `buy_sm_amount`、`net_mf_amount` 无空值 | 已补至目标日 |
| `etf_daily` | 2026-09-30 | 2140 | 收盘价、成交量、成交额无空值；收盘价为正 | 已补至目标日 |
| `etf_factor` | 2026-09-30 | 2140 | 收盘价、成交量、成交额无空值；收盘价为正 | 已补至目标日 |
| `margin_detail` | 2026-09-30，**部分** | 2001 | `rzye`、`rzmre` 无空值或负数，但只有 SH | **9 月 30 日全市场未通过** |

各源表返回范围不同，不要求行数相同。以上质量检查针对列出的关键字段，不把可空技术指标或可空两融字段当作必填字段。两融 `rqye` / `rzrqye` 在六日分别有 3、2、2、2、3、1 个空值，原值保留，未填零。

八个正式源表 WAL 均未暂停，`writerTxn = sequencerTxn`，缓冲事务大小为零。其中 `etf_portfolio` 为 1529/1529，`margin_detail` 为 121/121。

## 2. 已完成 Java 源任务

| 任务回执 | 范围 | 状态 | 源行数 / 已验证行数 |
| --- | --- | --- | ---: |
| `stk-factor-068f2f38-d20d-4b47-b7f1-5fd134d22518` | 09-22–09-24 | VERIFIED | 16667 / 16667 |
| `stk-factor-eba6ccdf-3b72-418e-b584-64b074e3e468` | 09-28–09-30 | VERIFIED | 16676 / 16676 |
| `daily-basic-5bdc9a21-9fbc-4dc2-a143-4528d5d2b691` | 09-22–09-30 | VERIFIED，恢复任务 | 33344 / 33344 |
| `stk-limit-ecde9425-a828-4c4e-a419-51995e2535df` | 09-22–09-30 | VERIFIED，恢复任务 | 33890 / 33890 |
| `moneyflow-464eefa5-18b7-49f5-bbbd-d1ce9eb98a3a` | 09-22–09-24 | VERIFIED | 16679 / 16679 |
| `moneyflow-ae452f65-7f01-401c-8b6c-d666b3a9906b` | 09-28–09-30 | VERIFIED | 16712 / 16712 |
| `etf-daily-3c9cfe0f-0f86-47e1-95ef-6213e05debd7` | 09-28–09-30 | VERIFIED | 6439 / 6439 |
| `etf-factor-d6e3e5ff-198c-4cc9-9304-e6dbf50ce5f7` | 09-22–09-30 | VERIFIED | 12855 / 12855 |
| `etf-portfolio-35156059-7d4e-4127-b72c-823a6a562c79` | 公告日 09-28–09-30 | VERIFIED_EMPTY | 0 / 0 |
| `margin-detail-987f94ff-12f7-4dbe-b1ae-5e10d12b50c3` | 09-22–09-30 | 历史回执 VERIFIED，最新日验收为 partial | 24260 / 24260 |

回执表示已验证返回数据入库。特别是旧两融回执产生于市场覆盖修复前，不能据其 VERIFIED 状态认定 9 月 30 日全市场完整。原失败记录和恢复历史均保留。本报告不评价正在协调的 `market_sentiment_daily` 计算任务，也不代替主策略整体验收。

## 3. 两只目标 ETF

`512770.SH` 与 `589330.SH` 在 9 月 28、29、30 日均各有一条日线和因子记录。六组收盘价一致；9 月 30 日分别为 **2.374**、**0.858**。因子源成交额有两位小数，而日线源精度更细，未将该精度差异判为收盘价冲突。

| ETF | 最新公告日 | 报告期 | 本次公告行数 / 权重合计 | 距 09-30 天数 | 结论 |
| --- | --- | --- | --- | ---: | --- |
| `512770.SH` | 2026-08-31 | 2026-06-30 | 124 / 53.36 | 30 | 旧 13 行、3.20 的漏入库已修复；本次公告源数据完整入库 |
| `589330.SH` | 2026-05-26 | 2026-05-22 | 10 / 20.07 | 127 | 超过既有 120 天新鲜度约定，保留警告 |

`512770.SH` 同一 2026-06-30 报告期还有 7 月 21 日披露的 10 条、权重 46.57；两次公告的代码不重叠，合计 **134 条、99.93**。只选择“最新公告日”的消费逻辑会排除前一次 10 条，属于披露消费口径限制，本次未修改策略口径。不能把最新公告单批 53.36 当作整份报告只有 53.36。

`589330.SH` 的 10 条、20.07 与既有源回执一致，但不足以证明完整投资组合应当合计 100%。既有按代码源响应及本次 Java 9 月 28–30 日空响应未提供更晚披露；这不证明基金公司没有发布其他官方公告。未伪造 9 月 30 日公告日期或调整持仓权重。

2026 年目标 ETF 持仓四键 `(ts_code, ann_date, end_date, symbol)` 当前无重复。全市场 8 月 31 日正式数据仍为 **901265 行**；当前主键空值为零，权重有 96 个源允许空值。复用 19:30 已完成的全量源对账：901265 个四键全部匹配，缺失/多余/重复均为零，3605060 个数值比较无差异（相对误差 1e-9、绝对误差 1e-6）。该历史全量回补在用户切换到 Java 前执行；后续增量源验证已走 Java，未重复写入这 901265 行。

## 4. 两融真实源验证

root 于 **2026-10-07 20:16:23–20:16:53（Asia/Shanghai）** 通过既有 Java WebFlux 客户端完成七次串行只读诊断，所有请求成功，scope 检查和 SHA 核对通过。

| 查询 | 结果 |
| --- | --- |
| 全市场 `trade_date=20260930` | 2001 行，全部 SH |
| `000001.SZ`，09-30 | 0 行 |
| `920229.BJ`，09-30 | 0 行 |
| `600000.SH`，09-30 | 1 行，日期正确 |
| `600363.SH`，09-30 | 0 行 |
| `000001.SZ`，09-29–09-30 | 仅 09-29，1 行 |
| `920229.BJ`，09-29–09-30 | 仅 09-29，1 行 |

当前配置的原 Tushare 源在全日响应中没有 9 月 30 日 SZ/BJ，抽样单代码和区间读取也没有补出该日，未发现 Java 清洗导致的丢行。不能据此证明交易所官方是否发布了数据，也不能假定所有单代码查询都与样本相同。9 月 29 日正式数据与源回执为 **SH 2002 / SZ 2107 / BJ 345，共 4454 条**；本次已验证市场覆盖的可用日期为 **9 月 29 日**。9 月 30 日已写的 2001 条继续保留为部分观测。

原 `MarginDetailSource` 已加 fail-closed 修复：完整响应至少包含 SH/SZ，BJ 实际计数保留；缺市场时保存原始行和计数的 SHA 未验证回执，在交付 writer 前失败。历史账本和已入数据未删除或改写。该存在性校验是最低发布检查，不能替代逐证券的权威全集证明。

协调任务随后用原 owner 实际运行一次 **09-30 单日 BACKFILL** 验证新准入。`margin-detail-1d2ebba8-0785-48ac-8292-ae4c928e829a` 如预期被拒绝：**FAILED，sourceRows=0，verifiedRows=0，reusedRows=0，CLI exitCode=3**，实际 `errorCode=IllegalStateException`。未验证原始回执仍保留 2001 条，`exchangeCounts={SH:2001,SZ:0,BJ:0}`、`sourceComplete=false`、`fullMarketCoverageVerified=false`，文件 SHA 为 `db825b08c86a586f005809a449c0c54178a9d7e6412716ba07fb5321822ed33b`，已核对文件名与内容 hash。

该失败是**源不完整的预期准入拒绝**，不表示 9 月 30 日数据已补齐。写前/写后查询均为 2001 行、2001 唯一代码、融资余额合计 1286761675955，原 partial 数据保留。它与覆盖修复前旧 run 的“VERIFIED 已返回值入库”分别记录，不修改旧账本状态。

官方接口支持 `trade_date`、`ts_code`、`start_date`、`end_date`，且说明 SZ/BJ 周五有延迟发布。9 月 30 日是周三，因此不能仅凭该注释解释缺失。[Tushare 原始接口说明](https://tushare.pro/document/2?doc_id=59)

## 5. 验收范围与证据

已确认股票基础源、资金流向、ETF 日线与因子到了 **9 月 30 日**。仍保留 **两融 9 月 30 日部分覆盖**、**589330 披露过期**、**512770 同报告多公告消费口径** 三项限制。市场情绪计算及完整主策略验收结果由协调任务另行出具，不能以本报告宣称全部主策略数据已通过。

- [结构化汇总](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/etf-margin-audit.json)
- [本地只读 SQL 与结果](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/etf-margin-audit-queries.json)
- [WAL、报告期合并及收盘价交叉核对](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/etf-margin-audit-extra-queries.json)
- [目标 ETF 四键唯一性核对](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/etf-target-duplicate-keys.json)
- [七次两融源诊断摘要及 SHA](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/margin-provider-probe/7probe_summary.json)
- [两融缺市场准入的实际拒绝回执](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/margin-coverage-guard-verification.json)
- [两融源覆盖调查记录](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/margin-provider-coverage-audit.md)
- [既有 8 月 31 日 ETF 全量对账](D:/work/fund_2/back-monitor/artifacts/main-strategy-refresh/20261007/etf-readonly-audit/replay-verification.json)
