# 每日筹码及胜率同步

`cyq_perf` 已接入 Java 正式 owner `cyq_perf_owner` 和任务 `data.cyq_perf`。写远端原表，复用共享来源配额、同步账本、整表锁、完整键值回读和未决恢复；不建表、不改列。当前是有限 CLI，尚未实现自动日批。

每次明确一个开市日和 1～6000 只证券。证券必须保留前导零及交易所后缀；来源逐只请求，全部齐备后才发送。缺一只、字段冲突或分页未结束，整次不发送。单证券当天没有来源时不补零，也不认证为空源完成。按共享盘后边界，20:30 前不接当日。

```sh
# 只生成冻结计划，不调用来源或写入账本。
./gradlew run --args='plan-cyq-perf --codes 000001.SZ,600000.SH --date 2026-10-16 --logical-date 2026-10-21'

# 查真实来源及目标结构/WAL，保存来源原件，不写 QuestDB 或正式运行账本。
./gradlew run --args='check-cyq-perf-source --codes 000001.SZ,600000.SH --date 2026-10-16 --logical-date 2026-10-21'

# 核对实际 writer、原账本和未决归属后，才运行正式写入。
./gradlew run --args='run-cyq-perf --codes 000001.SZ,600000.SH --date 2026-10-16 --logical-date 2026-10-21 --mode RECONCILE'

# 仅恢复原请求，不能额外换日期或证券。
./gradlew run --args='resume-cyq-perf --run 原运行编号'
```

模式支持 `INCREMENTAL`、`BACKFILL`、`RECONCILE`，均使用明确的单日范围，不自行推算下一天或历史起点。同日重跑可接收来源修订，仍核对全部字段。来源归档在原账本旁的 `sync-evidence/`；恢复保留原证券、业务日、定义和物理目标。`IN_DOUBT` 保留原锁，不自动重发或解锁。

`ts_code,trade_date` 是完整业务键。交易日按原 UTC 午夜日期载体读写，不加 8 小时；胜率、成本分位和价格保持供应商原值，不另做缩放或复权。当前没有为该表新增 MV 或评分消费者。

官方接口要求证券代码，见 [Tushare 文档](https://tushare.pro/document/2?doc_id=293)。来源调用受当前账号配额限制；大量证券耗时会增长，执行器最长 24 小时。Windows 一次传入大量 `--codes` 还会受命令行长度限制，不能把单次少量证券成功称为全市场就绪。

2026-10-21 已真实核验上面两只证券、一个交易日的来源及正式表结构/WAL，没有正式写入。相关回归、架构、边界及发行包结果见[本轮验证](audits/2026-10-21-cn-cyq-perf-owner.json)。来源检查和隔离测试均不计作正式表接管完成。
