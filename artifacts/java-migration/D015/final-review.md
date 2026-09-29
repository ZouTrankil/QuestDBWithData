# D015 真实验收结果

- 状态 verified；人工复核 pending_review。
- 本机 QuestDB 10.0.1；隔离目标 `java_d015_etf_adj_0e27dd96a0934279b0f84c830bca2d60`；正式表未修改。
- 首次真实来源 2177 行；两次同范围重跑行数与摘要相同；增量后 4355 行、0 重复键。
- 四次运行分别验证收据SHA-256，再从原始值独立核对全部 3 字段，0 缺失/0 差异。
- 取源前取消及完整页验证后取消；恢复重新取源并核对 2177 行，发送次数 0。
- 人为注入发送后丢 ACK：2 个真实来源行实际写入，发送者关闭后回读一致，UNKNOWN_RECONCILED。
- 已编译任务来源与实际运行工具；没有声称重跑完整Gradle构建或测试集。

## 修正与保留现场

真实 fund_adj 按官方 offset/limit=2000 分页；每日期均保留终止短页与完整页计数；物理 timestamp 为 TIMESTAMP 微秒；按完整键排序和规范化 JSON 生成可跨 JVM 重开的来源指纹。 早期失败报告与隔离现场保留，不覆盖为成功。

## 证据

- [实际运行](../market-live-0e27dd96a0934279b0f84c830bca2d60/D015-live-acceptance.json)
- [独立原始来源核对](../market-live-0e27dd96a0934279b0f84c830bca2d60/D015-independent-source-readback.json)
- [取消、恢复、未知写入回读](../market-live-0e27dd96a0934279b0f84c830bca2d60/D015-recovery-c61d4aaa-18e9-4a07-b487-15beaaa11740.json)

- [独立进程 CLI 恢复](../market-live-0e27dd96a0934279b0f84c830bca2d60/D015-cli-recovery-c52c99d8-0ad2-4cf9-96b1-7e22abdeea79.json)：2177 行复用，独立原始来源核对 MATCHED。
