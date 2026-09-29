# D017 真实验收结果

- verified；人工复核 pending_review。隔离 `java_d017_etf_factor_779e998396d74251b53b0104914f49fe`，QuestDB 10.0.1；正式表未修改。
- 首次 2163 行，两次相同窗口重跑全89字段摘要不变，增量后 4299 行；四次独立来源核对 MATCHED，0缺失/额外/重复/数值差异。
- 9个行情数值字段+78个指标完整保留；真实基金158008.OF保留。早期domain仅78字段、独立校验器缺OF及BASIC日期查询错误均修复，失败报告保留。
- 取源前取消、完整页后取消；恢复重核2163行，发送0次；独立JVM CLI恢复冻结请求通过。
- 两个真实来源行写入后人为丢确认，发送者关闭后回读 UNKNOWN_RECONCILED。
- 单日实际返回少于8000，未将无界或触顶响应记完整。限定javac编译和实际运行；未声称运行完整Gradle或测试集。

## 证据

- [D017-live-acceptance.json](../market-live-779e998396d74251b53b0104914f49fe/D017-live-acceptance.json)
- [D017-recovery-0f4ff7d6-bdb8-418a-a6da-0bc2a5d2fc3b.json](../market-live-779e998396d74251b53b0104914f49fe/D017-recovery-0f4ff7d6-bdb8-418a-a6da-0bc2a5d2fc3b.json)
- [D017-cli-recovery-bb7dc87c-a8a2-4e1c-bb0b-90b3dbef1d84.json](../market-live-779e998396d74251b53b0104914f49fe/D017-cli-recovery-bb7dc87c-a8a2-4e1c-bb0b-90b3dbef1d84.json)

- 09-27 SSE 无交易日：VERIFIED_EMPTY，独立范围 SELECT 空，全表4299行摘要不变。[证据](../market-live-779e998396d74251b53b0104914f49fe/D017-empty-window-be1d7787-5a75-44c3-9e51-0b75a2d46568.json)
