# Java market_sentiment_daily 入库审计

审计时间：2026-10-07 20:41:51 +08:00。目标：本地 QuestDB `127.0.0.1:9000`。运行：`market-sentiment-466c4316-9c83-489f-8c6b-11a84ebe936c`。

## 结论

**本次情绪表入库验收通过。** `2026-09-21` 至 `2026-09-30` 应有的 7 个交易日均已发布，每日 1 行；正式表现为 104 行，最新日期为 9 月 30 日。新增 7 行的全部 53 字段与 Java 输出证据逐值一致，原有窗口外 97 行全部保留，全表 SHA256 与发布 journal、publication 凭据完全相同。

9 月 30 日融资源缺少深圳、北京市场，因此该日属于 **`partial_enhanced`**：三项融资输入和 `leverage_score` 为 NULL，核心情绪与资金流分数可用。此次通过表示情绪表按明确的缺失语义发布成功；融资上游当日的全市场数据仍不完整。

此次审计仅执行只读 SQL、读取已有源证据和账本导出，并写审计文件。未修改 Java/Python 实现，未启动生产 job，未请求外部 provider，未写数据库，未运行测试或 Python 算法。

## 实际耗时与范围

| 项目 | 实际记录 |
| --- | --- |
| RUNNING | 2026-10-07 20:28:10.2051187 +08:00 |
| VERIFIED | 2026-10-07 20:29:02.0552059 +08:00 |
| 正式任务耗时 | **51.8500872 秒** |
| 输出范围 | 2026-09-21 至 2026-09-30，7 行 |
| 预热起点 | 2022-11-21，即输出起点前 1400 个自然日 |
| 实际历史日期 | 938 个 |
| 实际读取股票面板行数 | 5,171,414 行 |
| 运行逻辑日期 | 2026-10-07；数据截止由显式 `to=2026-09-30` 限定 |

耗时取自正式账本 RUNNING→VERIFIED 事件，包含本任务的计算、stage 写入、读回、正式表发布。它不包含前置行情同步、编译或先前失败/取消的尝试，也不是 Level2 全归档清洗耗时。

## 实际读库结果

| 检查 | 结果 |
| --- | --- |
| 53 列名称、顺序、类型 | 与 `MarketSentimentDailyRow` / WritePort 完全一致 |
| 物理布局 | `TIMESTAMP(trade_date) PARTITION BY MONTH WAL`，非 DEDUP，无 upsertKey |
| 新正式表物理 id | 3016，匹配 publication intent 的 replacementId |
| 备份物理 id / 目录 | 1811 / `market_sentiment_daily_rebuild_20260918_213205~1811`，匹配 originalId / originalDirectory |
| WAL | 未 suspended；writerTxn=sequencerTxn=9；bufferedTxnSize=0；无错误 |
| 全表行数 | 104；原备份 97；新增窗口 7；窗口外 97 |
| 日期集合 | 与 `daily` 在本窗口实际交易日期集合完全一致，无缺失或多余日期 |
| 日期业务键 | 全表无重复 `trade_date` |
| 时间载体 | 新业务日期均为精确 UTC 午夜；时间比较使用 epoch 微秒 |
| 窗口逐值读回 | **7×53=371 个值一致，0 差异** |
| 原有数据保留 | **97×53=5,141 个值一致，0 差异**，包括 `updated_at` |
| 数值有效性 | 全表所有非空 DOUBLE 均为有限值，无 NaN/Infinity |
| 输出月份 / 版本 | 全部 `202609` / `market_sentiment_daily_v2_rule_pca_20260427` |
| 分数自洽 | 核心六分量均值、增强可用分量均值与正式分数残差均为 0；总分在 0–100 |
| 正式账本 | RUN / ATTEMPT / SLICE 均 VERIFIED；missingKeys、duplicateKeys、mismatchedRows 均 0 |
| publication journal | VERIFIED；对应正式表/备份实际物理身份与 SHA 均通过 |

| 交易日 | daily 行数 | factor 行数 | 情绪总分（显示至 6 位） | 状态 | 数据质量 |
| --- | ---: | ---: | ---: | --- | --- |
| 2026-09-21 | 5553 | 5553 | 60.125062 | 升温期 | enhanced |
| 2026-09-22 | 5554 | 5554 | 53.800777 | 分歧期 | enhanced |
| 2026-09-23 | 5556 | 5556 | 47.201235 | 中性期 | enhanced |
| 2026-09-24 | 5557 | 5557 | 36.712265 | 中性期 | enhanced |
| 2026-09-28 | 5557 | 5557 | 32.506725 | 分歧期 | enhanced |
| 2026-09-29 | 5559 | 5559 | 52.057836 | 修复期 | enhanced |
| 2026-09-30 | 5561 | 5560 | 49.548072 | 修复期 | partial_enhanced |

精确数值、53 字段 schema、所有实际 SQL 与结果见 [只读读回证据](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/market-sentiment-native-readback.json)。可复用的审计脚本为 [audit-market-sentiment-native.ps1](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/audit-market-sentiment-native.ps1)。脚本只允许 `SELECT`，并使用 JSON 原始数值 token 重构原 CODEC 格式；另用 DOUBLE 的 IEEE754 位和 epoch 微秒做逐值比较。

## SHA256 与发布证据

全表 canonical SHA 以日期排序，对每行原 WritePort 53 字段 snake_case JSON 加 LF，再累计 SHA256。时间从 SQL 显式 epoch 微秒恢复为原 Java `Instant` 字符串。本次独立重算结果：

| 对象 | SHA256 |
| --- | --- |
| 备份 97 行 canonical SHA；等于 journal.beforeFingerprint | `c8f60c8ac993f6dbd678843444ef24a71fb109c671d238bc087725cee440d6ef` |
| 新正式 104 行 canonical SHA；等于 expected 合并内容、publication.fullTargetFingerprint、journal.afterFingerprint | `c5e64e8f17e97f8188921c615490ad50634c04ede45ffe91df3db885bf77649b` |
| source.json 文件 SHA；等于 journal.scope.sourceEvidenceSha256 | `f79dd1e85d68793f9438e917cbc65bf4f79d0898309fc69aa0e6fa77b503b0c8` |
| 原始面板/增强来源及准入证据的 sourceFingerprint | `083bda003cd84bf7776f5b7092a015922a7541fbe27d5c2a014021a7cf94d365` |
| 7 行 source 与正式窗口独立 IEEE754/epoch 内容 SHA（两边相同） | `7f37a277e01f345944101ab0a0a326d35916233db96303f67293fd54045de019` |
| 97 行窗口外数据独立 IEEE754/epoch 内容 SHA（新旧相同） | `bf3eed6927bcb81727b6ecba0f507168637b533feb7b773dcc8f80c68343303e` |

源指纹、文件 SHA、正式表 canonical SHA 的对象不同，不能混作同一种校验值。账本 ATTEMPT/RUN 的 sourceFingerprint 是框架对 slice 指纹的汇总；逐页源指纹应核对 SLICE 与 publication scope。本次这两个位置都等于 `083bda...`。

发布链：53 字段 stage 完整校验 → journal intent 落盘 → 旧正式表改名为保留备份 → stage 改名为正式表 → 正式全表再次读回校验 → publication / run VERIFIED。实际正式表并非旧表追加产生；保留备份名为 `java_d121_market_sentiment_daily_backup_330d0c1869114a32b5a6908d2206c50e`。

证据：[source.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/sync-evidence/market-sentiment-466c4316-9c83-489f-8c6b-11a84ebe936c/source.json)、[publication.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/sync-evidence/market-sentiment-466c4316-9c83-489f-8c6b-11a84ebe936c/publication.json)、[只读 SQLite 账本导出](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/market-sentiment-ledger.json)、[正式 attempt3 日志](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/market-sentiment-0921-0930-attempt3.log)。

## 两项已明确的源缺口

### 9 月 30 日融资增强

实际 `margin_detail`：SH=2001、SZ=0、BJ=0；9 月 29 日为 SH=2002、SZ=2107、BJ=345。`source.json.marginAvailability` 记录先前 20 个已观测日中三个市场均持续存在（各 20 日），按“先前 20 日，至少 3 日存在”规则期望 SH/SZ/BJ，因此 9 月 30 日因 `entire_recent_exchange_missing` 拒绝融资增强。

正式行实际为：

| 字段 | 9 月 30 日实际值 |
| --- | ---: |
| margin_buy_sell_ratio | NULL |
| margin_buy_amount_ratio | NULL |
| margin_balance_change_5d | NULL |
| leverage_score | NULL |
| moneyflow_score | 54.710391876978214 |
| sentiment_score_core | 44.3857523025751 |
| sentiment_score_enhanced / sentiment_score | 49.54807208977665 |
| data_quality_flag | partial_enhanced |

总分等于两个可用分量的均值 `(core + moneyflow_score) / 2`，与既有“增强分量至少一项可用”的算法语义一致。缺失融资输入没有伪造为零、没有当作全市场数据；相关原始融资行仍保留。此前协调器使用正式 Java WebClient 的 bulk、个股和范围共 7 个只读请求也未恢复当日 SZ/BJ，详见 [融资源范围证据](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/margin-provider-coverage-audit.md)。

### IPO 当日因子缺失

本窗口 `daily \ stk_factor` 实际只有 `2026-09-30 / 001246.SZ`。`source.json.dailyOnlyMissingFactor` 包含 Java WebClient 当次 `stock_basic` 返回上市日 `20260930`，及同日 `stk_factor` 请求 0 行的响应。准入依据是动态上市日和源响应，代码未硬编码这只股票。没有编造该股因子；情绪以既有 `stk_factor` 面板为基础。其余 factor 股票全部有 daily_basic / stk_limit，7 日缺失计数均 0。

## 主策略消费核对

1. [主策略质量入口](D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/quality/main_strategy_daily.py:364) 对情绪表检查“有数据且最新日期不落后 stk_factor”。实际两者最新日期均为 **2026-09-30**，该项阻塞已消除。此入口没有审计全部 53 列或融资全市场覆盖，因此本次另作上述校验。
2. [情绪数据目录定义](D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/catalog_definitions/table_market.py:65) 对外投影为 trade_date、sentiment_score、sentiment_state、data_quality_flag 和四个风险布尔字段，本次均按既有名称/类型落库。[equity_research 的情绪组件](D:/work/fund_2/back-monitor/src/quant_platform/data/application/product_definitions/equity_research.py:43) 是可选组件，没有规定融资必须完整。
3. [ETF hybrid 的情绪预处理](D:/work/fund_2/back-monitor/src/quant_platform/research/panels/etf_market.py:159) 仅剔除无日期/无总分的行，按总分生成分位；[拥挤惩罚](D:/work/fund_2/back-monitor/src/quant_platform/research/scoring/stock_guards.py:114) 消费日期、总分、状态、分位。有限总分的 `partial_enhanced` 行可按既有行为消费；没有要求 leverage_score 非空。
4. 当前 **ETF_ONLY_TOP2** 的正式 [输入包角色](D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/etf_only_top2/etf_only_top2_input_bundle.py:26) 和 [市场上下文](D:/work/fund_2/back-monitor/src/quant_platform/data/application/product_definitions/etf_rotation.py:64) 使用 `market.regime.daily_context`，并另读 score_panel / score_coverage 制品。它不能由情绪表的发布替代。因此**本报告只完成情绪依赖验收，不代表全部主策略依赖或 regime/context 已完成**。

Java 算法已静态对照既有 Python 同名算法，保留面板、过滤、rolling、缺失增强、PCA 特征与符号、版本和时点范围语义；独立代码审查结果见 [semantics-review](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/market-sentiment-semantics-review.json)。本次 53 字段精确相等是 **Java 输出与实际存储** 的相等，不宣称另跑 Python/sklearn 的逐位数值结果。既有整段预热窗口 winsorization / PCA 重建行为也被保留，本报告没有增加新的历史时点无未来信息保证。

后续进展另行验收：协调器随后完成原生 `regime-monitor-5c1ba217-3a43-40cd-82cb-69a83d69b756` MATERIALIZE 发布，正式运行记录为 VERIFIED、输出 7 日；该表的 21 字段、原有 659 行和发布 SHA 验收结论见 [独立 regime 审计报告](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/regime-native-java-audit.md)。上文市场上下文说明对应本情绪报告 20:41 的审计时点，后续状态以该独立报告为准。

接口收尾：本次已经执行的 sentiment/regime 运行保留各自冻结的 **jobVersion=1、mode=MATERIALIZE** 事实。当前源码的两个原生计算 job 定义提升至 **jobVersion=2**，只声明并接受 MATERIALIZE，避免把未实现的只读 RECONCILE 暴露为会发布正式表的入口。DatasetDefinition/schema 仍为版本 1，数学算法、已有行内容和旧 run 恢复能力保持本次已验收语义。

## 可直接检查的 SQL

```sql
SELECT trade_date, sentiment_score, sentiment_score_core,
       sentiment_score_enhanced, sentiment_state,
       leverage_score, moneyflow_score, data_quality_flag, model_version
FROM market_sentiment_daily
WHERE trade_date >= '2026-09-21' AND trade_date < '2026-10-01'
ORDER BY trade_date;

SELECT *
FROM (SELECT trade_date, count() AS n FROM market_sentiment_daily GROUP BY trade_date)
WHERE n > 1;

SELECT trade_date, margin_buy_sell_ratio, margin_buy_amount_ratio,
       margin_balance_change_5d, leverage_score, moneyflow_score, data_quality_flag
FROM market_sentiment_daily
WHERE trade_date = '2026-09-30';
```

第一条应返回上述 7 日，第二条应为空，第三条应返回 NULL 融资字段和 `partial_enhanced`。所有实际执行 SQL 的完整结果已保存在读回 JSON。
