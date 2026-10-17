# 主策略的数据和计算

`main-strategy-runtime` 把数据模块和 Batch 接起来，独立运行主策略。正式数据准备和现有生产评分在 Java；研究、训练和参考算法留在 Python。

## 一轮主要做什么

当前日批依次经过这些阶段：

1. `Sources`：准备和核验来源。
2. `MarketSentimentDaily`：市场情绪日数据。
3. `RegimeFeaturesMonitorDaily`：市场状态监控特征。
4. `BacktestDaily`：核验原生 `mv_backtest_daily`，不另发一张 Java 回测结果表。
5. `L2DailyFeatures`：核验并按协议补齐正式 L2 日特征。
6. `ScoreInputs`：使用已验收的方案和训练文件，计算股票与 ETF 分数，发布评分和覆盖结果。
7. `OfficialPlanData`：读取或导入正式周计划，绑定原评分来源。
8. `MainStrategyAcceptance`：检查日期、来源、评分、输入包和正式计划是否完整匹配。

当前批定义为版本 2，旧版运行记录不能直接冒充新版完成证据。每个阶段要看实际结果，不是执行过就算通过。

## 各部分由谁负责

| 内容 | 主要实现 | 结果和检查 |
| --- | --- | --- |
| 读取行情、日历、持仓等输入 | `QuestDbStrategyInputs` | 限定范围，检查日期、版本、字段和 WAL |
| 股票、ETF 评分 | `L2StockScoreProducer`、`EtfOnlyTop2ScoreProducer` | 使用固定的已验收方案和训练文件摘要 |
| 评分与覆盖数据 | `ScoreArtifactPublisher` | 成对 Parquet，SQLite 同时提交目录别名和来源信息 |
| 市场输入包 | `NativeMarketInputPackagePublisher` | `calendar`、`etf_market`、`market_context` 三部分绑定同次评分 |
| 正式周计划 | `OfficialPlanStore` | 导入规范 JSON，本地 SQLite 保存并检查冲突和来源 |

情绪输入读 `market_sentiment_daily`；市场状态读 `regime_features_monitor_daily`。后者保留当前修订和可用时间，但只有最新修订视角；固定今天的值不证明历史当时就知道。

`mv_backtest_daily` 的基表是 `stk_limit`，其他关联来源先完成，再提交它。右侧表单独修订不会自动完成四表历史重算，见[同步说明](data-pipeline.md#回测输入来自哪里)。

## 常用命令

```sh
./gradlew mainStrategyData --args='status'
./gradlew mainStrategyData --args='inspect-plans'
./gradlew mainStrategyData --args='produce-scores --target YYYY-MM-DD'
./gradlew mainStrategyData --args='import-plan --source PATH --source-sha256 SHA256 --score-run RUN_ID'
```

后两个命令会实际生成产物或导入计划。日期、路径、摘要和运行编号换成真实值；先用 `status` 检查缺什么。`produce-scores` 不训练替代权重，也不伪造周计划。

长期运行用 `./gradlew mainStrategyRun`。管理接口在 `/api/v1/main-strategy/data` 下，提供状态、评分、输入包、计划查询和导入，使用配置中的 API token。它和数据 API 是不同进程。

## 配置和本地文件

配置在 `app.sync.batch`：`research-root`、`promoted-recipe`、`promoted-recipe-pins`、`promoted-artifact-root`、`official-plan-db`、`score-start-date`、`score-source-row-budget`。

默认研究根目录跟随 `app.paths.research-root`，目前是 `../back-monitor`。方案、批准摘要和计划文件必须真实存在；缺文件、摘要不符、训练包含未来数据、日历缺口或 WAL 未稳定都会阻止发布。

批准摘要文件包含 `recipe`、`factor_weights`、`factor_states`、`l2_factor_weights` 的小写 SHA-256。CLI 默认最大堆 16 GiB，可用 `-PmainStrategyDataHeap=...` 调整；读取还有逐来源和累计行数预算。

## Python 怎么配合

Python 默认通过 `JAVA_DATA_API_URL`、`JAVA_DATA_API_TOKEN` 读取 Java 已发布结果。正式链路不在 Python 里重算另一套覆盖调整或训练对齐；Java 调用失败也不回退本地正式计算和写入。

正式方案写入按现有默认计划库或 `JAVA_MAIN_STRATEGY_PLAN_DB` 的约定交给 Java。显式的研究或测试计划目录仍保留自己的写入方式，不能混用成正式结果。

## 日期和历史计划怎么绑定

历史请求读取对应日期已经发布的那次评分，不能拿后来的最新别名替代。正式周计划一直绑定原评分和训练身份，日频别名更新不重绑旧周计划。

新计划和当前日输入重新检查四份批准摘要；已有周计划保留原训练身份。周计划还要符合 SSE 日历中跨 ISO 周的相邻开市日，处理节假日和跨年；计划过期或缺下一开市日就不能验收。

评分和覆盖成对保存还不够，同次市场输入包也必须完整。已有运行的输入变了，需要明确修订，不静默覆盖已完成记录。

代码测试、固定样本和历史跑通记录都不代表当前生产就绪。实际缺口以本次 `status` 和完整来源验收为准。
