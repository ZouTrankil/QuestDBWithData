# D016 真实验收结果

- 状态 verified；人工复核 pending_review。QuestDB 10.0.1，隔离表 `java_d016_etf_share_03920d258719454e8da565b45ebb0c2f`，正式表未修改。
- Job v3，SH/SZ/O 三市场；2026-09-17 首次 1,774 行（SH999/SZ766/O9），两次同冻结观察时间重跑摘要不变；09-18 增量后 3,566 行。
- 四轮原始收据 SHA 与全部六字段独立回读 MATCHED，0 缺失、0 额外键、0 重复键、0 字段差异。
- 取源前取消、完整页后取消；恢复重核 1,774 行、发送 0 次；独立 JVM CLI 按原 run 恢复且身份不变。
- 实际发送两个来源行后注入确认丢失，关闭发送者后回读 UNKNOWN_RECONCILED。
- 隔离表中实际来源空分类行先设受控旧值，再写 provider null，全表摘要恢复。
- 新观察时间 BACKFILL 后仍可规划增量；checkpoint 保持 09-18；后续增量六字段独立回读通过。
- 历史 v2 两市场收据及早期失败报告保留，不用作 v3 checkpoint。已 scoped javac 编译；未运行完整 Gradle 或测试集。

## 证据

- [D016-live-acceptance.json](../market-live-03920d258719454e8da565b45ebb0c2f/D016-live-acceptance.json)
- [D016-recovery-5c284dbe-0b8b-4af1-b2ff-696f60d64edb.json](../market-live-03920d258719454e8da565b45ebb0c2f/D016-recovery-5c284dbe-0b8b-4af1-b2ff-696f60d64edb.json)
- [D016-cli-recovery-69132040-d187-4d22-a416-ebd174585432.json](../market-live-03920d258719454e8da565b45ebb0c2f/D016-cli-recovery-69132040-d187-4d22-a416-ebd174585432.json)
- [D016-revision-6f4cd85c-9f3e-4d59-b13a-07ed3d27a1ae.json](../market-live-03920d258719454e8da565b45ebb0c2f/D016-revision-6f4cd85c-9f3e-4d59-b13a-07ed3d27a1ae.json)
