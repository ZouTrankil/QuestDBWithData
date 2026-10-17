# L2 文件怎么导入

A 股数据应用（`market-cn/data-app`）直接用 Java 读取本地 Parquet，不再通过 Python 子进程读取。五类来源分别是清单、日特征、分钟特征、事件响应和 T0 训练标签。

## 配置在哪里

| 配置前缀 | 内容 |
| --- | --- |
| `app.sync.l2-manifest` | 数据集清单，D085 |
| `app.sync.l2-daily-features` | 日特征，D086 |
| `app.sync.l2-intraday-bar-features` | 分钟特征，D087 |
| `app.sync.l2-event-response-features` | 事件响应，D088 |
| `app.sync.l2-t0-training-labels` | T0 训练标签，D089 |

各项使用 `dataset-root`。默认跟随根配置里的 `app.paths.l2-dataset-root`，位于研究目录下的 `artifacts/level2_t0_dataset`。旧的 `python-executable`、`reader-script` 不再生效。

## 读文件时检查什么

保留合法 null、64 位整数和原有时间精度。拒绝字段或顺序漂移、重复完整业务键、不支持的嵌套字段、无穷值、损坏清单和不安全路径。

读取仍有行数、文件、字节、分页、取消和截止时间限制。开始消费前复查来源文件和摘要。分钟、事件、标签来源选择每只证券最新的回执；最新失败时不会偷偷回退旧成功批次。

## 正式日特征怎么导入

正式表 `l2_daily_features` 有单独的 `FormalL2DailyFeaturesIngestion`：

```sh
./gradlew run --args='validate-formal-l2-day --date YYYY-MM-DD'
./gradlew run --args='ingest-formal-l2-day --date YYYY-MM-DD'
```

把日期换成真实的已完成日期，未来日期不接受。

- `validate`：只读来源和目标，比较全部 110 个正式字段、当天完整键、WAL 和物理版本；不写数据，也不初始化本地运行记录。
- `ingest`：取得持久日期锁，只补当前完整来源中已证明缺失的键。已有值冲突或目标多出来源没有的键就停止。

写入每批最多 200 行，并限制实际传输不超过 1 MiB；每批和整个日期都要读回。它不能建删表、覆盖已确认的旧值或把历史行补记成假成功回执。

结果不确定时保留 `IN_DOUBT` 和日期锁，先核对原运行再重试。缺少当天清单或 Parquet，不能拿旧回执或数据库已有行代替。

## 离线归档的 JSONL 也能导入

原生归档计算会生成每日 JSONL 和 `COMPLETE` 清单。确认两者完整后可以执行：

```sh
./gradlew run --args='ingest-formal-l2-jsonl-day --date YYYY-MM-DD --source-root PATH'
```

日期和目录替换成真实值。导入检查整包摘要、每只证券的成功回执和结果摘要，继续使用同一正式写入器、日期锁、冲突检查和完整读回。

这些命令证明的是所选清单范围，不自动证明整个策略所需证券和日历都完整，因此成功也保留 `factorReady=false`。是否满足主策略输入要求，由调用方另外验收。
