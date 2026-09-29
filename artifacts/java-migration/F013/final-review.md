# F013 验收

本项已完成，人工复核 pending_review。此前 `recovery-review.md` 中缺失生产恢复复核的问题已经补齐：`ChildExecutor.revalidateCompleted` 默认拒绝复用；正式 stock_basic 组合服务通过 `VerifiedRunRecovery` 重新有限取源并调用 `VerifiedSliceRecovery` 逐片读取当前 QuestDB 完整键和值、检查 WAL。源指纹或目标值变化均失败，不写入修复。父任务完成凭据包含 `reusedChildReadback`，指向新复核文件。

## 实际证据

- [正常组合及恢复](3f30663cbb284ddd8138d477b24dda16/group-readback.json)：原父 PARTIAL；重开账本后首项重新只读复核、第二项执行，父 VERIFIED；原 attempt 保留。
- [独立字段对照](3f30663cbb284ddd8138d477b24dda16/independent-values.json)：两条业务源记录与实际 SELECT 的 7 字段全部一致，无缺失或重复。
- [目标漂移拒绝](3f30663cbb284ddd8138d477b24dda16/drift-rejected.json)：将隔离表首行名称改为 `F013 injected drift` 并确认写入可见；再恢复时父 FAILED，未执行子任务，目标变化未被自动覆盖。测试完成后清理自有隔离表。
- `local-F013-drift-live` 的真实恢复/漂移测试及定向测试通过。`local-F013-recheck-final` 全套 128 项，112 通过，16 项外部条件未启用而跳过，0 失败；真实测试单独执行，不以跳过代替外部验证。

## 定义与入口

`SyncGroupDefinition` 版本化已有 job 成员，重复一致成员去重，未知依赖及循环拒绝。`SyncGroupPlan` 在开始执行前冻结共同参数、逐成员覆盖、区间与逻辑日期。按依赖稳定排序，串行 fail-fast，SQLite 父子引用与未完成成员槽阻止父任务提前成功。全部启用任务与日常任务集合独立。取消保持已完成子项，并停止后续子项；正式子任务同时观察父组取消。

CLI：`show-sync-group-definitions`；`run-stock-basic-group --codes 000001.SZ,600000.SH --logical-date 2026-09-29`；恢复增加 `--resume-from GROUP_RUN_ID`。正式示例组只有已接入的 stock_basic；真实恢复测试使用两个测试专用别名复用同一适配器，没有提前准入其他业务数据。后续数据任务完成后才加入业务组合。

stock_basic 是当前快照，不能代表后续历史数据的增量验收。组不承诺跨表事务或跨查询一致快照。时间预算在任务边界检查，底层调用沿用有限网络/JDBC 超时。写入结果未知仍拒绝自动重放。结果登记见 `docs/migration-tasks-20260929/results/F013.json`。

补充全套启用外部测试复核：`local-F013-final2` 共 128 项，119 通过、9 跳过、0 失败、0 错误。该次真实组合证据在 [group-readback.json](98f0bc867af64bf385af9e738473b554/group-readback.json)，[独立字段对照](98f0bc867af64bf385af9e738473b554/independent-values.json)仍为 7 字段、2 行一致。该次组恢复包含旧子项的新鲜只读复核凭据；`git diff --check` 无空白错误。
