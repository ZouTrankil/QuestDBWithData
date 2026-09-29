# D021 实际验收

- verified；人工复核 pending_review；正式表未写入。
- 八指数快照4450行；两次冻结观察时间重跑；八指数08-31历史回补；再次刷新。五轮均11列独立MATCHED，最终4450行、0差异、0重复。
- 来源保留七份原始XLS二进制、Tushare原始行与D002名称引用收据；Java独立POI和Python pandas/xlrd分别重算，未用生产mapper或normalizedRows充当期望值。
- 默认以完整已验证快照的观察时间做七天门控；CLI NOT_DUE不建run、不发请求、不冒充空数据验收。第6天不刷新，第7天计划可刷新；历史回补不改变门控。
- 取消后300行复用、4150行补写；独立JVM CLI恢复原冻结请求；真实发送后注入丢ACK并逐键回读确认。09-27历史空窗口不改变全表。
- 名称补充复用本地已同步D002快照，冻结物理目标和实际引用行，避免额外stock_basic调用；这是明确的Python调用链复用差异。
- 修复实际XLS双语表头、QWP符号顺序、名称指纹序列化顺序、独立助手历史路由与恢复证据目录；保留早期失败证据，不把它们登记为通过。
- 仅定向javac和上述真实操作，未运行完整构建或测试集。未声称全历史、自然限流或权重成员撤回修复。

## 证据

- [D021-live-acceptance.json](../market-live-28b6bdc1d5144ce9b90e11142380138c/D021-live-acceptance.json)
- [D021-recovery-78c3b621-78de-428c-8935-43ddd014b52f.json](../market-live-28b6bdc1d5144ce9b90e11142380138c/D021-recovery-78c3b621-78de-428c-8935-43ddd014b52f.json)
- [D021-cli-recovery-7889c9ad-38a5-4e4f-be59-1ad56f429e21.json](../market-live-28b6bdc1d5144ce9b90e11142380138c/D021-cli-recovery-7889c9ad-38a5-4e4f-be59-1ad56f429e21.json)
- [D021-gate-empty-1f1a447e-ca7c-44a2-aa05-6c973682d1c6.json](../market-live-28b6bdc1d5144ce9b90e11142380138c/D021-gate-empty-1f1a447e-ca7c-44a2-aa05-6c973682d1c6.json)
- [D021-python-independent-index-weight-58a090e3-f695-4836-8e89-9616609f954f.json](../market-live-28b6bdc1d5144ce9b90e11142380138c/D021-python-independent-index-weight-58a090e3-f695-4836-8e89-9616609f954f.json)
- [D021-python-independent-index-weight-a1758410-799b-4f41-8b4a-de76dd97c450.json](../market-live-28b6bdc1d5144ce9b90e11142380138c/D021-python-independent-index-weight-a1758410-799b-4f41-8b4a-de76dd97c450.json)
