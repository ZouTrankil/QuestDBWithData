# 原 Run 重命名统计异常恢复

原 run：`backtest-materialize-25356ba8-ab6a-4ced-9d4a-5faa94d67e02`。

2026-10-08 00:29 +08 的首次发布停在 IN_DOUBT。201 个月原 slice 全部 VERIFIED，完整源和阶段表均为 10,400,431 行，完整源窗口 2010-01-29–2026-09-30。旧正式基表已更名为 `java_backtest_daily_backup_2cf197c215184001b14792d3597dfe64`（原 id1757 / directory `backtest_daily~1757` / 10,355,884 行），阶段表 id3020 保留；正式基表此时尚未切入。

只读账本仅保留 `IllegalStateException` 类名，未保留原异常消息。实际 catalog 显示旧备份 `table_row_count/table_txn/min/max` 全为 null，但正确 DAY/WAL/DEDUP Schema、物理 ID/directory 与磁盘 WAL frontier 18/18 保持，真实 COUNT 正确。根据实际控制流，重命名后 `optionalIdentity(backup) → identity → number(table_row_count)` 会因 null 失败，符合停止位置。

修复保持物理身份和完整性检查：identity 从 attached 分区读取精确 sum(numRows)/min/max，并在前后检查相同 ID、directory、Schema 与 settled WAL frontier；事务统计 null 时读取 disk writerTxn 对应值，绝不默认 0。sourcePin 维持已冻结的规范序列及非空字段，仅缺失的 tracker 值由分区和磁盘事实补齐。nativeState 与 FULL 前版本查询采用相同磁盘事务 fallback。相关来源见 [QuestDB Meta Functions](https://questdb.com/docs/query/functions/meta/#data-precision-and-limitations) 和 [wal_tables](https://questdb.com/docs/query/functions/meta/#wal_tables)。

实际 `gradlew classes --no-problems-report -q` exit 0。没有启动新 bootstrap，没有修改原账本或手工重命名，没有删除原数据。父任务在确认原 PID38960 已停止后，按 `finishInterrupted(originalRunId, writerStopped=true)` 恢复原 publication；仍须完整源/阶段/旧备份字段证明、原租约和原 run 恢复验证，不能依据进程退出宣称成功。
