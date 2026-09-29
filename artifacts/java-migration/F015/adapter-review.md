# F015 预备批次执行适配器（进行中）

`PreparedWriteAdapter` 把 `WriteGroupPlan` 中一个成员转为有界 INGEST 页，交给既有 `SyncJobRunner`。冻结的请求包含组批次、成员批次、完整计划指纹和有效载荷指纹；真实写入/可见性核验、SQLite run/attempt/slice 状态、区间锁及未知提交处理沿用单数据执行器。

owner 的 DatasetValues→Java record→DatasetValues 往返必须保留原批次指纹，否则在发送前拒绝。每次预检、发送、回读和 WAL 检查都会重新检查 owner 目标身份。适配器保存 `sourceKind=prepared-write-request` 的输入证据，不把调用者传入的数据声称为新调用的 Tushare 响应。

`local-F015-adapter-b72c`：适配器 3 项及计划 3 项通过，无失败或跳过。真实 SQLite 配合模拟写入端验证：非空批次通过原执行器落入 VERIFIED；重开账本恢复后复用已验证片，发送次数保持 1；有损映射和目标身份变化在发送前拒绝；空批次 VERIFIED_EMPTY 且发送次数为 0。这些是执行语义测试，不替代真实 QuestDB 验收。

组级运行必须保留 durable parent/child 引用及未知提交证据，不能只依靠内存 `prior Result` 决定是否重放。下一步将组级执行接入既有 SQLite 组合账本，再验收部分完成、恢复、重复提交及真实来源/QuestDB 写后回读。F015 仍 running，累计完成 14 项。
