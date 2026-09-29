# F010 同步运行账本验收

`SyncRunLedger` 使用独立本地 SQLite 事务存储，不在 QuestDB 行情表保存运行锁。
sync_runs 保存父运行、job/version、逻辑日期、目标和不可变完整定义/参数快照；sync_entries 保存 run/attempt/slice 层级和当前状态；sync_events 按 revision 追加状态、游标/源指纹/计数/摘要/错误/验证证据 JSON。

状态更新与事件追加同事务提交，revision compare-and-set 拒绝过期写入。SQLite foreign_keys、WAL、synchronous FULL、5秒忙等待启用；初始化 DDL 逐条执行。状态事务异常时回滚，不用 JSON 文件覆盖账本。

支持 PENDING/RUNNING/FETCHED/VALIDATED/SUBMITTED/ACKNOWLEDGED/IN_DOUBT/VERIFIED/VERIFIED_EMPTY/PARTIAL/FAILED/CANCELLED。ACK 不等于 verified；提交后异常不能直接变 failed 并自动重放。IN_DOUBT 不允许回到 running，新 child 也被拒绝；修复完成需 writerStopped 加完整回读证据。成功要求正数完整键匹配计数与来源/回读证据；空成功要求来源已完整结束、0返回、0提交及响应证据。子任务未成功时父任务不得成功。

`show-sync-run --run ID [--ledger PATH] [--after ENTRY_ID] [--limit N]` 使用 SQLite mode=ro/query_only，缺失文件直接报错，不创建账本。entries/events 都是稳定游标、每页1..1000；历史事件与当前状态只有一个事务权威。

## 验收

- 8项账本测试：重开恢复、SQL投影/事件一致、过期版本拒绝、非法层级/状态拒绝、事件插入失败触发器强制回滚、成功证据门槛、只读/有界查询、完整任务版本快照和实际CLI状态查询、父子完成约束。
- `live-ledger-15bb99ac3c544db4afebb1467662a465.json`：真实Tushare单代码1行，隔离QuestDB QWP写后7字段回读一致；完整run/attempt/slice均verified，slice 7条阶段事件在关闭连接、重新打开账本后可读，批次回执与摘要保存在 verified 事件中，任务版本及逻辑日期不变。成功后只清理自有隔离表；SQLite账本留在记录中的var路径。每次live测试使用独立文件，旧账本证据链接不会被新测试覆盖。
- 全套 `local-F010-final`：91项，82通过、9项其他显式外部测试未启用跳过、0失败。真实F008/F010写入测试已启用并通过。

账本只检查提交的证据结构、计数一致性和状态转换，不凭一个路径自行证明数据库值。真实回读由F008执行器提供，F011负责将正常执行与账本串接；F012负责持久化checkpoint/恢复决策。本项不声称定时调度或全部数据迁移已经完成。

参考Python：`D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/local_files/sync_state.py` 与 `common/runtime/job_run_store.py`。
复现：参考Python目录运行 `uv run python C:/Users/zouqiang/IdeaProjects/QuestDBWithData/tools/run_local_validation.py --build-name local-F010-final --enable QUESTDB_WRITE_LIVE`。人工复核pending_review。
