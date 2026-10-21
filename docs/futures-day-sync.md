# 期货日频同步

首张接入 `fut_daily`，正式任务为 `data.fut_daily`，owner 为 `futures_day_owner`。写已有远端同名表，复用共享来源配额、同步账本、整表锁和完整键值回读，不建表、不改列。当前为有限 CLI，自动日批尚未接入。

`fut_mapping` 使用连续合约；其他表使用带到期月份的合约。每次明确一个合约和 1～31 个自然日。逐日查来源，不套 SSE 开市日，也不按接收时刻重新划分夜盘交易日。结束日须不晚于冻结业务日，并已过现有 20:30 盘后来源边界。

```sh
./gradlew run --args='plan-futures-day --dataset fut_daily --code IF2612.CFX --from 2026-10-16 --to 2026-10-19 --logical-date 2026-10-21'

# 只保存真实来源及目标结构检查证据，不写 QuestDB 或正式运行账本。
./gradlew run --args='check-futures-day-source --dataset fut_daily --code IF2612.CFX --from 2026-10-16 --to 2026-10-16 --logical-date 2026-10-21'

# 核对原 writer、账本、锁和调度归属后才执行。
./gradlew run --args='run-futures-day --dataset fut_daily --code IF2612.CFX --from 2026-10-16 --to 2026-10-19 --logical-date 2026-10-21 --mode RECONCILE'

./gradlew run --args='resume-futures-day --dataset fut_daily --run 原运行编号'
```

模式支持 `INCREMENTAL`、`BACKFILL`、`RECONCILE`，均使用明确范围，不按 `MAX(trade_date)` 自动接续。全部日期的来源齐备后才发送；某日读取失败，不先发布前面的日期。

`ts_code,trade_date` 是完整业务键。交易日仍是 UTC 午夜日期载体；`update_time` 单独使用冻结请求里的真实 UTC `observedAt`，表示本次观察开始时间。恢复使用原值，不重新取现在。回读全部列时先后核对 WAL 版本，发送确认不代表验收完成。

来源允许空响应，但目标仍有同日同合约旧键时停止，不删除旧值，也不签为空源完成。`VERIFIED_EMPTY` 仅表示指定请求源为空、对应目标键缺失，不证明市场闭市、合约应当无行情或全历史齐全。未决发送保留原锁，不自动重发或解锁。

日线价格和数量原样保存。`vol`、`oi` 沿用手数，`amount` 沿用万元，不新增缩放或复权，见 [Tushare 日线说明](https://tushare.pro/document/2?doc_id=138)。恢复与旧 Batch 隔离协议分开，见[决定](decisions/2026-10-21-期货日频观测时间冻结.md)。

本轮 `IF2612.CFX` 单日真实来源 16 个业务字段、原表 17 列及 WAL 检查通过，未执行正式写入。8 项相关回归通过；架构、边界和发行包在本期货批次末统一检查。[证据](audits/2026-10-21-cn-fut-daily-owner.json)不代表全市场、全历史或正式接管完成。

`fut_mapping` 同样使用上述四个命令，替换 `--dataset fut_mapping`，连续合约示例为 `--code IF.CFX`。真实单日来源及目标预检通过，13 项相关回归通过；[证据](audits/2026-10-21-cn-fut-mapping-owner.json)仍属于有限范围检查，未正式写入。
