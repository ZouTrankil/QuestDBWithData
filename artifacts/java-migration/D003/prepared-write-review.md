# D003 prepared 写入组验收

状态：已在隔离 QuestDB MONTH/WAL 表验证；正式 `index` 仍只读。输入是匹配历史 17 列的候选来源中前两行，保留明确的 `import_time`，不是独立行情 as-of 声明。

- `local-D003-prepared-resume4`：`group.prepared_writes` 的 `write.index` 子任务发布 2 行，按 `index_code` 与 18 个物理字段回读一致。使用相同冻结输入恢复组合时复用原已验证子任务，`reused=true`，没有第二次写入，目标物理身份和完整快照不变。证据：`prepared-44c274034eea42b4b9a5493032d7e41c/write-readback.json`。
- `local-D003-prepared-recovery`：发布完成后故障注入拒绝 SLICE 账本完成，状态留为 `IN_DOUBT`。同步调用结束并确认本测试独占写入者后，用冻结 prepared receipt、发布日志和实际全字段回读恢复到 `VERIFIED`；恢复期间来源请求与行插入均为 0。证据：`prepared-recovery-1ed99759906742598bf42be5fc5f776c/recovery-readback.json`。
- `local-D003-prepared-final`：对执行时凭据重验的最终代码复测上述两个真实隔离表测试，2/2 通过、无跳过。
- 前两次恢复组合测试分别发现缺少 `SyncRunLedger` 导入和准备任务复用时误用文件任务验证；已修复并复测。失败测试自行创建的 `prepared-99dd10f41a944cfea152a1e6c405e203` 与 `prepared-bb05ee73b5924c668b9372d31c5ed8f6` 目标/备份表在逐一核对发布日志物理 ID 和行数后清理，保留测试证据文件。

写入组只接受注册的数据集、冻结的全部 typed 行和明确的目标绑定。WAL 全表替换必须为组合的最后一个成员；它仍是逐子任务完成的组合，不提供跨表原子提交。相同业务字段而不同 `import_time` 的输入按目录合并规则保留原行及原导入时间，此行为源任务和 prepared 任务一致。
