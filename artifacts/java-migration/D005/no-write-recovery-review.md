# D005 无写入恢复及写入前失败重试

D005 仍 running，人工 pending_review。

`IndexMembershipNoWriteRecovery` 仅在冻结来源重建、分类/请求一致、原目标身份与全14列快照完全未变、无发布日志及无stage意图时补齐账本。未确认原写入者停止时拒绝恢复。空来源保留 VERIFIED_EMPTY，未知写入的 IN_DOUBT 不能降格为空成功。

`IndexMembershipSliceJob.resume` 只接收 FAILED/CANCELLED、原目标身份、完整冻结请求一致且不再持锁的旧任务，新run保存resume-intent，保留旧失败记录。RUNNING/IN_DOUBT必须先核验，已完成任务不走失败重试入口。

`local-D005-no-write-1043`：1测试、0跳过、0失败，包含以下实际场景：

| 场景 | 来源/数据证据 | 验收 |
|---|---|---|
| 801011.SI 无变化观察后中断 | 真实Y/N共7条，已有7条不变 | Error后三级RUNNING；恢复0新请求、0WAL事务，三级VERIFIED并释放锁 |
| 801217.SI 空观察后中断 | 真实Y/N均0，已有其他行业7条保留 | 恢复0新请求、0WAL事务，三级VERIFIED_EMPTY并释放锁 |
| 来源写入前失败后重试 | 受控IOException；同范围重试真实取得7条 | 旧run保留FAILED；新run VERIFIED，7条未变、0写入；改变范围及对已完成run重复重试均拒绝 |

每个无写入恢复均实际读取QuestDB，表身份、14列快照及writerTxn/sequencerTxn与中断前一致。成功后隔离表清理，SQLite账本、冻结来源和实际SQL回读凭据保留。

证据：[recovery-readback.json](no-write-recovery-91aee19ffa72456faefbcbc11055a541/recovery-readback.json)。1042是添加安全重试前的无写入专项，1043覆盖最终代码的无变化、空源恢复与写入前失败重试。故障为受控Error/IOException，不是OS杀进程；源请求失败重试不等同于重复写入。

下一步仍需多行业串行协调及已验行业复用、CLI/注册、sync与typed-write组合，以及最终整项回归。当前没有把单行业执行器提前注册为支持完整批量任务的owner。
