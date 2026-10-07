# 主策略数据补齐与 Java sync 验收

验收日期：2026-10-07（Asia/Shanghai）。目标截止日：**2026-09-30**。目标实例：**本地 QuestDB，127.0.0.1:9000 / 8812**。

## 1. 本次结果

本轮源同步复用了既有 `TusharePageService → TushareClient → Spring WebFlux WebClient`、`SharedRequestBudget`、各数据 owner、`SyncJobRunner`、账本与逐字段回读，没有创建第二套 HTTP 客户端。市场情绪原先只有字段投影，现已增加原生 Java 计算 owner，读取本地已验证依赖表；IPO 例外核对也通过同一个 WebFlux client。

| 用户关注的数据 | 当前结果 | 本轮完成方式 |
| --- | --- | --- |
| `stk_factor` | 最新 09-30，六个补齐日期共 33343 条；逐键、全部字段回读通过 | 原 Java owner 的有界 BACKFILL |
| `v_backtest_daily` | 最新 09-30；最近 32 个交易日行数覆盖、唯一键、收盘价/ST/停牌关键空值检查通过 | 源表补齐后视图自动反映，无需单独同步 |
| `market_sentiment_daily` | 最新 09-30；本轮 7 个日期、53 字段通过，全表 104 行 | Java 原生计算、stage 验证、带持久化发布账本的替换 |
| `regime_features_monitor_daily` | 最新 09-30；本轮 7 个日期、21 字段，全表 666 行 | Java 原生计算实际 ETF 输入包的市场上下文 |
| `etf_daily` | 最新 09-30，09-28–30 共 6439 条；当日 2140 条、目标两只 ETF 行情齐全 | 原 Java ETF owner BACKFILL |
| `etf_portfolio` | 09-28–30 的原源无新公告，Java `VERIFIED_EMPTY`；历史漏入库已修复，详见第 4 节 | 原 Java owner 检查最新窗口，复用之前完整历史对账 |

`daily_basic`、`stk_limit`、`moneyflow`、`etf_factor`、`etf_adj`、`stk_st_daily`、`stk_suspend`、`l2_daily_features` 也已实际确认到 09-30。其中本轮 Java 同步和字段回读的数量分别为 33344、33890、33391、12855；其余表复用已有可用数据并只读核对。

**验收结论：所列主策略日数据的日期与日表质量条件已通过。仍保留融资源覆盖和 ETF 披露时效/消费口径限制，不能据此声称全部增强源或全部持仓披露质量都通过。** 本次没有运行策略、发布新目标计划或进行交易。

## 2. 没有同步到最新的原因与修复

1. **旧路径的未决同步。** 既有 Python 同步账本存在 `in_doubt`；0922 融资快照也曾有源/库键和值冲突，不能直接重复旧任务。当前数据通过 Java owner 重新拉取、写入和完整回读；旧历史账本保持原状，不伪造旧任务完成。
2. **Java 部分 owner 仍只允许隔离验收表。** `stk_limit`、`moneyflow`、ETF、`margin_detail` 等原实现已存在，但部分正式表准入、冻结范围和覆盖条件未支持本次补数。修复为精确正式表白名单和有界 BACKFILL/RECONCILE；保留已有隔离表模式，未用表的最大日期冒充全历史检查点。
3. **共享凭证租约要求进程串行。** 多个 JVM 同时拉同一凭证会被既有 `SharedRequestBudget` 拒绝。初次 `daily_basic` / `stk_limit` 在源请求前失败、源行和已验证行均为零；随后通过原 resume owner 串行恢复并 `VERIFIED`，保留限流和租约机制。
4. **ETF 持仓来源上限不足。** 旧 256000 行/33 页不足以覆盖 08-31 实际 901265 行。修复原 `EtfPortfolioSource` 为有界 1000000 行/126 页，保留分页 SHA、偏移/计数、终页证据和旧回执重开兼容。chunk 复用原源文件引用，避免复制全部原始响应。超过上限仍停止。
5. **回读查询昂贵。** 在原逐键、逐字段查询外增加日期范围和代码范围，帮助 QuestDB 裁剪分区，保留原精确业务键过滤、重复检查和全字段散列。三日 `stk_factor` 的另一次实际补数约 37 秒；此前同量级三日任务约 6 分 40 秒。范围、缓存、源延迟不同，这不是独立标准化基准测试。
6. **市场情绪缺少 Java 计算 job。** 已注册原生 Java owner，复用 typed 53 字段投影、JdbcTemplate、锁、运行账本与发布日志。修复 WAL 元数据字段兼容问题并复用既有 `QuestDbWriteChecks.walSettled`。历史读取原来逐行查取消账本，已改为首行/每 1024 行检查；旧轮正常取消后重新执行，保留取消响应能力。
7. **已有日表验收漏查真实 ETF 上下文依赖。** 沿输入包消费链发现 `market_context → market.regime.daily_context → regime_features_monitor_daily`，实际仍停在 09-18。原 Java 只有 Row/View 投影，没有计算 job。已复用原 21 字段投影和现有数学/发布工具增加 Java owner，将共用日窗 typed port 抽出供情绪/上下文复用，补齐 7 个日期。两种计算 owner 的新 job 契约版本为 2，仅声明真实实现的 MATERIALIZE，保留显式中断发布恢复；已完成实际运行的 v1 冻结记录不改写。

## 3. Java 运行回执

下列“行数”是本轮完整源/回读数量，包含对已有范围的验证或修复，不等于全部是新增行。

| 数据 / 范围 | 行数 | 终态 | run ID |
| --- | ---: | --- | --- |
| stk_factor 09-28–30 | 16676 | VERIFIED | `stk-factor-eba6ccdf-3b72-418e-b584-64b074e3e468` |
| stk_factor 09-22–24 | 16667 | VERIFIED | `stk-factor-068f2f38-d20d-4b47-b7f1-5fd134d22518` |
| daily_basic 09-22–30，resume | 33344 | VERIFIED | `daily-basic-5bdc9a21-9fbc-4dc2-a143-4528d5d2b691` |
| stk_limit 09-22–30，resume | 33890 | VERIFIED | `stk-limit-ecde9425-a828-4c4e-a419-51995e2535df` |
| moneyflow 09-28–30 | 16712 | VERIFIED | `moneyflow-ae452f65-7f01-401c-8b6c-d666b3a9906b` |
| moneyflow 09-22–24 | 16679 | VERIFIED | `moneyflow-464eefa5-18b7-49f5-bbbd-d1ce9eb98a3a` |
| etf_daily 09-28–30 | 6439 | VERIFIED | `etf-daily-3c9cfe0f-0f86-47e1-95ef-6213e05debd7` |
| etf_factor 09-22–30 | 12855 | VERIFIED | `etf-factor-d6e3e5ff-198c-4cc9-9304-e6dbf50ce5f7` |
| etf_portfolio 09-28–30 | 0 | VERIFIED_EMPTY | `etf-portfolio-35156059-7d4e-4127-b72c-823a6a562c79` |
| margin_detail 09-22–30，旧市场规则 | 24260 | VERIFIED 响应落库；09-30 非全市场 | `margin-detail-987f94ff-12f7-4dbe-b1ae-5e10d12b50c3` |
| margin_detail 09-30，新缺市场规则 | 0 交付 writer | FAILED，实际拒绝不完整源；无新增写入 | `margin-detail-1d2ebba8-0785-48ac-8292-ae4c928e829a` |
| market_sentiment_daily 09-21–30 | 7 | VERIFIED、published | `market-sentiment-466c4316-9c83-489f-8c6b-11a84ebe936c` |
| regime_features_monitor_daily 09-21–30 | 7 | VERIFIED、published | `regime-monitor-5c1ba217-3a43-40cd-82cb-69a83d69b756` |

运行账本：`var/main-strategy-java-refresh/20261007/sync-ledger.sqlite3`。运行日志与原始源回执：同目录及 `sync-evidence/`。全部操作均显式覆盖 `app.questdb.host=127.0.0.1`，未向默认远程地址写入。

### 市场情绪实际验收

- 读取 938 个历史日期、5171414 条股票面板输入，预热起点 2022-11-21；本次 Java 运行阶段实际耗时 51.85 秒，不是单日常规运行基准。
- 09-21、22、23、24、28、29、30 共 7 个输出日期；7×53=371 个源结果/实际读回字段逐值相等，Double 按 IEEE754、时间按 UTC 微秒核对。
- 其余 97×53=5141 个字段与保留备份完全相同，全表业务日期唯一，共 104 行。
- 正式表 MONTH、WAL、非 DEDUP、指定时间 `trade_date` 和全部 53 字段类型符合原合同。WAL 未挂起，writerTxn=sequencerTxn=9，bufferedTxnSize=0。
- 模型版本 `market_sentiment_daily_v2_rule_pca_20260427`。静态对照既有规则/PCA 实现，保留原预热、滚动及全窗 winsor/PCA 语义；未执行 Python 计算 job 或声称完成 Python/Java 独立全数值重算基准。
- 09-30 三个融资输入及 `leverage_score` 为 NULL；资金流向有效，质量为 `partial_enhanced`。其他六日为 `enhanced`。计算未使用截断的融资总额。
- 09-30 `daily` 比 `stk_factor` 多出的 `001246.SZ`，已通过原 WebFlux client 确认上市日期就是 20260930，因子精确查询返回空；保留动态源证据，没有硬编码忽略或伪造因子。
- 持久化发布日志为 VERIFIED，保留正式表原内容的 backup；重新读取全表并使用原 CODEC 构造 SHA256，与发布结果完全相同：

```text
sourceFingerprint:     083bda003cd84bf7776f5b7092a015922a7541fbe27d5c2a014021a7cf94d365
fullTargetFingerprint: c5e64e8f17e97f8188921c615490ad50634c04ede45ffe91df3db885bf77649b
```

### 实际 ETF 市场上下文补齐

原输入包要求的 9 个上下文字段包含 6 个有限数值，不能用情绪总分代替。新 Java owner 以 09-01 月初预热，读取 **21 个交易日、116577 条因子股票面板** 和 **116588 条估值输入**，复用既有 size/PB 三桶 proxy-style、月内累计复利、5 日均值、预热全窗估值百分位、北向月累计及国债 ERP 计算语义。

- 正式表从 659 行变为 **666 行**，新增 7 个交易日，最新 09-30；物理仍为 21 字段、MONTH/WAL/非 DEDUP。
- 预热区间 SSE 日历 30 个自然日完整，股票面板全部 21 开市日期齐全，所有 factor 股票均有 daily_basic 与 stk_limit 连接；稀疏 ST/停牌按原语义处理。
- 北向 21 日、估值 21 日、gov10Y 22 日期。09-20 为非交易日国债记录，保留源证据并在 valuation exact join 之外忽略，没有用它产生额外输出日期。
- 9 个主策略必需字段的日期集合、唯一性、月份、`proxy_style` 标签和 6 个有限数值已通过综合只读验收。
- 09-30 `margin_balance_change_mtd` 为 NULL，保留缺 SZ/BJ 的准入证据；其他核心上下文、北向和 ERP 可用。该标签沿用原股票分桶代理语义，不宣称可选融资数据完整。
- 独立逐值验收通过：新增 7×21=147 个字段与 source 完全一致，原 659×21=13839 个字段与发布前快照、备份各自完全一致；全表 666 行 canonical SHA 与合并结果、发布回执、发布账本一致。
- 实际账本创建至 VERIFIED 为 6.04 秒，其中 RUNNING 至 VERIFIED 为 5.79 秒；包含该窗口读取、计算和发布，不作为单日常规运行基准。

```text
algorithmVersion:      java.regime_features_monitor_daily.v1_legacy_proxy_style
sourceFingerprint:     cb8cde9cd23b8036d455fbeb494be3392cbce4c6e0d0d5c190793c58f0c77cf5
fullTargetFingerprint: c21beb9d2deca48ea85ce24a4a62d6d8ccd0f4912bebfb7b4304f521f4bf50e3
```

## 4. 保留的数据质量限制

### 09-30 两融未全市场发布

当前配置的 Tushare 全日响应仅有 2001 条 SH，SZ/BJ 为零。原 WebFlux client 的七次诊断还确认：`000001.SZ`、`920229.BJ` 精确查询 09-30 为空；查询 09-29–30 仅返回 29 日。原响应就缺市场，没有发现 Java 归一化丢行。此结论只针对当前上游响应，不证明交易所未发布。

源层已修复最低全市场检查（SH/SZ）和证据重开检查。实际 09-30 单日 job 在交付 writer 前拒绝，`sourceRows=verifiedRows=reusedRows=0`，真实 `errorCode=IllegalStateException` / CLI exit=3。原部分数据仍为 2001 个唯一键，运行前后融资余额合计均为 1286761675955，无新增写入。旧 VERIFIED 运行只证明其返回内容正确落库，不能再当作完整市场覆盖。

本轮已证明市场覆盖的最近融资日为 **09-29：SH 2002 / SZ 2107 / BJ 345，共 4454 条**。日后完整源可用时，复用原 Java owner 只补 09-30，再重算对应情绪窗口。

### ETF 披露时效与消费口径

- `512770.SH` 最新公告 08-31，124 个成分、权重 53.36；此前 13 个、3.20 的入库缺口已修复。同一 06-30 报告期另有 07-21 公告 10 个、46.57，两批代码无重叠，总计 **134 个、99.93**。原消费逻辑只取最新公告批次，仍会只得到 53.36；本次没有修改策略的公告选择口径。
- `589330.SH` 最新源公告仍为 05-26，10 个成分、20.07，距 09-30 **127 天**，超过 120 天治理约定。未把旧披露改成 09-30，也未声称持仓权重必须由这次源响应覆盖到 100%。
- 08-31 全市场 **901265** 个四键与源逐键匹配，3605060 个数值对账无差异。**这一历史大范围回补在用户切换到 Java 前已通过 Python 路径完成**；本轮 Java 复用该证据、验证最新窗口没有新公告，没有重新写这 901265 条。当前六日日线/因子/资金数据均有本轮 Java source/readback 回执。

`regime_features_monitor_daily` 曾至 09-18 的额外缺口也已通过 Java owner 补齐到 09-30。本次更新数据，没有重跑或发布 ETF score_panel、正式持仓计划及策略执行制品。

### 补算记录的可见时间

新上下文记录的 `updated_at` 为实际计算时间 **2026-10-07T12:57:26.404569Z**，没有回填为 09-30。现有目录将上下文可见时间声明为 `updated_at`；显式要求 `available_as_of=20260930` 的历史查询应排除本次补算记录。因此，“当前可读取截至 09-30 的数据”不等同于“当时 09-30 已可见的数据”。本次验收没有认证历史时点回放；当前 ETF 输入包的 market_context 未开启该可见时间过滤，仍保留原声明语义。

## 5. 主策略只读检查

按 `main_strategy_daily.py` 已有日表条件及实际 ETF 输入包的 regime 日期格/必需字段条件执行本地只读 SQL，使用已有官方目标代码 `512770.SH`、`589330.SH`，不运行策略或 companion-plan 校验：

- `stk_factor`、`v_backtest_daily`、`market_sentiment_daily`、`regime_features_monitor_daily`、`etf_daily`、`etf_adj`、`etf_factor`、Level2 最新均为 09-30。
- 08-17–09-30 检查 32 个实际因子交易日，每日因子至少 5000 条，视图覆盖不少于因子行数、唯一数等于行数，`close` / `is_suspended` / `is_st` 空值均为零。
- 09-30 ETF 行情 2140 条，近期中位数 2119.5，覆盖 100.97%，高于 95% 阈值；目标两只收盘价分别 2.374、0.858。
- 两只目标均有 cutoff 前披露。日表验收条件没有检查 120 天披露时效、完整持仓权重或融资全市场覆盖，这些限制在第 4 节另列。

## 6. 证据入口

- [主策略日表只读验收 JSON](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/main-strategy-readonly-acceptance.json)
- [Java 基础源最终审计](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/java-sync-data-audit.md)
- [ETF 与两融审计](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/etf-margin-audit.md)
- [原生 Java 情绪审计](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/market-sentiment-native-audit.md)
- [原生 Java 上下文审计](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/regime-native-java-audit.md)
- [上下文独立逐值验收 JSON](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/regime-native-java-audit.json)
- [情绪运行与发布账本](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/market-sentiment-ledger.json)
- [情绪源结果与覆盖例外](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/sync-evidence/market-sentiment-466c4316-9c83-489f-8c6b-11a84ebe936c/source.json)
- [情绪发布回执](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/sync-evidence/market-sentiment-466c4316-9c83-489f-8c6b-11a84ebe936c/publication.json)
- [上下文发布回执](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/main-strategy-java-refresh/20261007/sync-evidence/regime-monitor-5c1ba217-3a43-40cd-82cb-69a83d69b756/publication.json)

构建 `gradlew classes --no-problems-report` 已通过；本轮未执行单元测试套件。上述通过项来自实际 Java source sync、QuestDB 写入和只读回读/独立验收，不只依据进程退出。

最终构建后又执行两种 owner 的只读计划：job 契约版本 2、supportedModes 仅 MATERIALIZE、`executed=false`；共用 typed port 打开的情绪表 104 行和上下文表 666 行仍分别得到上述原发布 SHA。该步骤没有再次写入或重算数据，结果见 [当前 Java 计划/共用端口复核](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/native-current-plan-v2-readonly.json)。

## 7. 本地复查 SQL

```sql
SELECT max(trade_date) FROM stk_factor;
SELECT max(trade_date) FROM v_backtest_daily WHERE trade_date >= '2026-08-16';
SELECT trade_date, sentiment_score, sentiment_score_core,
       data_quality_flag, leverage_score, model_version
FROM market_sentiment_daily
WHERE trade_date >= '2026-09-21' AND trade_date < '2026-10-01'
ORDER BY trade_date;
SELECT ts_code, close FROM etf_daily
WHERE timestamp = '2026-09-30' AND ts_code IN ('512770.SH','589330.SH');
SELECT trade_date, month, data_quality_flag, margin_balance_change_mtd, updated_at
FROM regime_features_monitor_daily
WHERE trade_date >= '2026-09-21' AND trade_date < '2026-10-01'
ORDER BY trade_date;
SELECT symbol, ts FROM l2_daily_features
WHERE ts >= '2026-09-28' AND ts < '2026-10-01' LIMIT 5;
```

## 8. 按最终目标口径收尾

用户确认：成分持仓没有 09-30 新披露时，采用上游实际最新披露。两只目标当前分别采用 `512770.SH` 的 08-31、`589330.SH` 的 05-26，原公告日期、报告期和治理时效提示保留；本次不要求把披露日期补成日行情日期。

2026-10-07 收尾时重新读取实际本地 QuestDB：主策略日表截止日仍全部为 09-30，32 个交易日的日表检查再次通过；情绪和上下文全字段/历史/SHA 审计再次通过。基础源 09-30 的数量与唯一代码数相等：stk_factor 5560、daily_basic 5561、stk_limit 5651、moneyflow 5572、etf_daily/etf_factor 各 2140。Level2 三日为 7926/7918/7888，仍为 110 字段、零重复代码；10 个正式 WAL 表均无挂起且已追平。

最后又实际执行 `gradlew classes --no-problems-report -q`，退出码 0；显式本地 host 的 Java 上下文只读 plan 退出码 0、`PLANNED`、`executed=false`，读出的 666 行和完整 SHA 与正式发布回执一致。没有为收尾重新执行源同步或计算发布。

当前证据：[本地 QuestDB 实查](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/goal-current-questdb-observation.json)、[最终构建凭据](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/goal-final-build-receipt.json)、[显式本地 Java plan 启动凭据](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/goal-local-regime-plan-launch.json)。

两只成分还重新对照了已保存的按 ETF 代码完整源回执（均有完整短终页证据）：当前最新公告的 124/10 个业务键、共 536 个业务数值字段与本地实际读回完全一致。源回执保留之前 Python 获取的真实来源，本轮没有冒称重新通过 Java 获取这些历史披露。详见 [实际最新成分核对](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/goal-constituent-actual-latest.json)。

最终 [Java 链路审计](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/goal-java-route-closure-audit.json) 确认无必须追加的 Java 数据任务；[逐项完成审计](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/main-strategy-java-refresh/20261007/goal-completion-audit.json) 的 11 项要求均有实际证据。按用户确认的实际最新成分口径，本次主策略数据入库目标完成，保留第 4 节上游可选增强数据与治理提示。
