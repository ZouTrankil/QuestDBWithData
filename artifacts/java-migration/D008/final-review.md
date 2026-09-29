# D008 真实验收结果

- 状态：verified；人工复核 pending_review。
- 本机 QuestDB 10.0.1；隔离目标 `java_d008_basic_9b4cbc43e2224ae28b1d522fec0f38b3`，正式表未修改。
- 首次真实来源 5557 行；两次同范围重跑行数及摘要不变；增量后 116572 行、0 重复键。
- 原始收据指纹验证后独立 SELECT 核对全部 18 字段，4 次运行均 0 缺失/0 差异。
- 取源前取消及完整页验证后取消；恢复重新取源并核对 5557 行，发送次数 0。
- 人为注入发送后丢 ACK：2 个真实来源行实际写入，发送者关闭后完整键回读一致，UNKNOWN_RECONCILED。此项为故障注入，非自然网络事故。
- 共享限流实际执行；异常/触顶分支沿用既有契约回归证据，不声称真实发生供应商限流。

## 证据

- [实际运行](../market-live-9b4cbc43e2224ae28b1d522fec0f38b3/D008-live-acceptance.json)
- [独立来源核对](../market-live-9b4cbc43e2224ae28b1d522fec0f38b3/D008-independent-source-readback.json)
- [取消、恢复与未知发送回读](../market-live-9b4cbc43e2224ae28b1d522fec0f38b3/D008-recovery-80b74797-fd2b-400c-86df-c289895ba475.json)

## 执行中修正

FrozenRequest 显式序列化；D007 checkpoint 键比较改为 HashMap；D008 完整日历列投影；恢复回读改为每批 100 个完整键。后续 D009 取源失败单独留证。

## Fresh-process CLI recovery

`run-…-job --resume-from RUN_ID` restored the original frozen request in a new JVM. Reused 5557 rows; independent raw-source readback MATCHED; target count unchanged. Evidence: `artifacts/java-migration/market-live-9b4cbc43e2224ae28b1d522fec0f38b3/D008-cli-recovery-09d7ef7d-cdb8-466a-917f-1266b65bdebd.json`. No new test suite run.
