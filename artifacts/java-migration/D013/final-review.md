# D013 真实快照验收

- verified；人工复核 pending_review；本机 QuestDB 10.0.1。
- 隔离目标 `java_d013_etf_basic_c6e3b1cdbe864651a2d34d035a14a9ee`，正式表未修改。
- 四次来源均2961行，含46条空status；两次同一冻结观察时间的重跑，完整物理摘要不变。
- 第四次新快照刷新同一技术键，update_time使用新观察时间；每次写后立即独立核对原始25字段和2个派生时间列，完整键/27列0差异。
- 来源没有日期变更游标，因此采用SNAPSHOT；1970 timestamp为技术键，不虚构增量日期checkpoint。
- 取源前、已验证页后取消；恢复复用2961行、0发送；CLI从原run恢复冻结计划，同样复用2961行，不继承旧取消标记。
- 人为注入关闭发送者后的丢ACK，2行完整键回读一致，UNKNOWN_RECONCILED。
- 修复了空sentinel定义、恢复错误继承旧取消标记、CLI重新计划改变观察时间问题。操作脚本最初把FrozenRequest对象引用当值比较的失败报告保留，后改为冻结JSON比较。
- 已编译源码和共享接线；未重跑完整Gradle构建或测试集。

[四次运行及每次独立核对](../market-live-c6e3b1cdbe864651a2d34d035a14a9ee/D013-live-acceptance.json) · [取消、恢复和丢ACK回读](../market-live-c6e3b1cdbe864651a2d34d035a14a9ee/D013-recovery-53d9c73a-07a1-46f5-bb0c-88f4079801ac.json)

## Fresh-process CLI recovery

`run-…-job --resume-from RUN_ID` restored the original frozen request in a new JVM. Reused 2961 rows; independent raw-source readback MATCHED; target count unchanged. Evidence: `artifacts/java-migration/market-live-c6e3b1cdbe864651a2d34d035a14a9ee/D013-cli-recovery-add6954a-6486-41ad-8133-cabfd001a262.json`. No new test suite run.
