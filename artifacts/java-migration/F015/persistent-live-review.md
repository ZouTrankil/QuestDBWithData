# F015 持久化组合与真实回读（进行中）

`PersistentWriteGroupRunner` 将每个 PreparedWriteAdapter 绑定为单数据 INGEST job，再复用 SyncGroupRunner 的 SQLite 父子账本、SyncJobRunner 的写入核验和区间锁。恢复依据持久化 run ID，不使用调用者提供的内存 Result 作为完成凭据。已完成成员必须重新完整回读后才复用，原父子记录保留。

`local-F015-persistent-live-cf2b` 真实测试通过：Tushare 返回 000001.SZ、600000.SH 两条记录，映射为两个测试专用数据定义，分别写入自有隔离 WAL 表。第二个目标在组预检通过后注入故障，首轮父任务 PARTIAL。重建执行器并重开账本恢复，只发送第二项；再次恢复两项均只读复核。实际 send 调用各为 1，两个表各保留 1 行，WAL settled，成功后清理隔离表。

证据：[write-group-readback.json](acb6ee1f124149c0a1c1e797cf9b2ef7/write-group-readback.json)；[独立逐字段核对](acb6ee1f124149c0a1c1e797cf9b2ef7/independent-values.json)。`tools/verify_write_group_evidence.py` 独立对照原始 Tushare 响应和实际 SELECT，2 表 2 行 7 字段一致，无缺失或重复。

`local-F015-persistence-boundaries-7a64` 定向回归通过：计划、适配器和持久化组覆盖部分完成恢复、重复恢复不重发、目标数据缺失拒绝复用、有损映射及目标身份错误、空输入不写、未知提交保持 IN_DOUBT 并拒绝自动重放。模拟写入端仅证明这些故障语义，实际数据证据以上述 live 测试为准。

正式应用入口和完整回归仍待完成，F015 保持 running，累计完成数仍 14。没有新增业务数据集准入，也不承诺跨表事务。
