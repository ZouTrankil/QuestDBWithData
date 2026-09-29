# D004 中断恢复与组合入口实际核验

本地串行执行；本记录仅证明下列场景。人工复核 pending_review，最终任务收敛另行登记。

## 组合 sync

- `StockBasicGroupService` 注册 `group.ths_index_manual`，执行与完成子任务复核均路由到 THS owner。
- 恢复沿用原 child run 冻结的 targetId，而不是取换表后的新物理身份重新生成请求。
- `local-D004-group-recovery-1009` 中 `ThsIndexGroupLiveTest` 通过：真实 Tushare 单次完整目录观察，实际隔离写入、全字段回读；恢复复用原 child，source 调用总次数仍为 1。生产表前后完整快照一致。
- 证据：`group-42318406e5a4483cbe8aff5c144b81f6/group-readback.json`。

## 发布完成、账本确认失败

- 同一构建的 `ThsIndexRunRecoveryLiveTest` 通过：SQLite trigger 注入 slice VERIFIED 转移失败，run/attempt/slice 均保留 IN_DOUBT；禁止无停止证明恢复或直接重新取数。
- 移开完成回执后，恢复根据冻结源回执、实际发布表及备份表补记完成；目标物理身份、全行值及指纹一致，三级账本 VERIFIED，锁释放。
- 证据：`run-recovery-8bf666f28f5d4c12b6e414ad7a71d953/recovery-readback.json`。

## 发布前中断与无变化中断

- `local-D004-no-write-recovery-1011` 通过。使用已保留的真实 2517 条来源响应，未重新发出 HTTP 请求；模拟生产者已停止的持久状态，不声称执行了 OS 进程强杀。
- 完整暂存表：恢复复用原 stage，发布后实际 2517 条逐字段一致。证据：`early-recovery-b6bb30327ca44206b38f7206c6196b85/early-recovery-readback.json`。
- 残缺暂存表：保持原空 stage 不变，创建新 stage，分批写入后发布、实际回读。证据：`early-recovery-3469e9a8cca042e582497b7981722187/early-recovery-readback.json`。
- 两个场景均再模拟一次来源内容未变、尚未生成完成回执的中断：无需 stage/rename，实际完整快照不变，三级账本 VERIFIED、锁释放。各目录 `unchanged-recovery-readback.json` 保存证据。
- 未提供 writerStopped=true 时均拒绝；成功测试自有表清理，证据保留。

## 组合 typed write

- `local-D004-prepared-review-1012` 通过：从实际 QuestDB 读取两条已有 THS 数据，经过 typed mapper、JSON write group、owner、暂存发布，隔离表 7 字段回读一致。
- 恢复相同组合复用原子任务，并重新读取目标核验；这是已有真实数据的组合写入测试，不是新增上游来源同步。
- 证据：`prepared-aa07fcad193f4347acd439ce2244d357/write-readback.json`。

## 收敛补记

- `local-D004-management-1013`：真实 Spring CLI 计划预览、组计划和增量合并专项通过。计划不创建账本；拒绝缺 logical-date、虚构分页参数、plan 携带 resume-from、恢复缺停止证明等输入。
- 最新全套回归 local-D004-final-regression-1015：306项，226通过/80条件跳过/0失败；逐项要求见 final-review.md。
- local-D004-prepared-recovery-1014 单独核验 prepared write 的账本失败恢复；改变回执字节时拒绝恢复、实际目标不变且锁保留。还原本测试回执后完成回读，三级 VERIFIED。证据 prepared-recovery-105b23086fed4fbd848726e5bd3a2a7a/recovery-readback.json。
