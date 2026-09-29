# F012 真实恢复链路复核

补充重开账本验证：`local-F012-reopen-proof` 实际执行 1 项、0 跳过、0 失败。恢复前重新构造 SQLite 账本、区间锁和 runner；恢复适配器统计实际 `send` 调用为 1，已验证首片没有再次发送。真实来源返回 2 行、QuestDB 完整值验证 2 行、复用 1 行。证据：[live-recovery-871ee9e0995e4800a94b01711ad1c0fd.json](live-recovery-871ee9e0995e4800a94b01711ad1c0fd.json)。此项验证组件重建后的持久化恢复，不声称执行过操作系统进程强杀。

`SyncJobRecoveryLiveTest` 使用隔离 QuestDB WAL 表和真实 Tushare `stock_basic` 两代码。首轮在第二个来源响应交给 runner 前注入失败：账本为 PARTIAL，首片 VERIFIED，目标物理可见 1 行。新 run 重新请求两代码，按来源指纹与当前目标完整键和值复核首片，`reusedRows=1`，第二片写入并回读，父子 4 条当前状态均 VERIFIED。原 attempt 保留 PARTIAL。测试确认 WAL settled 后清理该次隔离表。

证据：[live-recovery-a46db9f8b45c440f9679da26d4d01e46.json](live-recovery-a46db9f8b45c440f9679da26d4d01e46.json) 与两次 run 的 `source-*.json`。独立对照恢复 run 的两条源响应与 QuestDB 回读 `ts_code,symbol,name,area,industry,list_date` 六个来源字段，2/2 全值一致；`snapshot_ts` 均为逻辑日期 2026-09-29 的 UTC 日桶微秒值 1790640000000000。只读查询 `tables()` 确认自有隔离表剩余 0 个。

定向执行：设置 `QUESTDB_WRITE_LIVE=1` 和本机有效 PGWire 凭据后运行 `./gradlew.bat test --tests '*SyncJobRecoveryLiveTest' --rerun-tasks --console=plain`，结果 `BUILD SUCCESSFUL`。此后 `local-F012-final` 全套 113 项、104 通过、9 项未启用外部测试跳过、0 失败。此证据证明已知来源失败后的断点复用；提交结果未知时继续保留区间锁，必须另行确认旧 writer 已停止并完成精确回读，不能由本次恢复成功推断未知提交可重放。

Python 卡片引用的 `sync_recovery.py` 当前仅实现每日来源日期上限；实际有界未知写入核验链在 `src/quant_platform/data/application/sync_reconciliation.py` 和 `src/quant_platform/data/adapters/connectors/sync_reconciliation.py`。此处仅作为语义参考，没有修改 Python 项目。
