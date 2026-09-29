# D018 真实验收结果

- verified；人工复核 pending_review。QuestDB10.0.1，隔离 `java_d018_etf_portfolio_921d13f6c4294134a69ad37cf3feb69e`，正式表未修改。
- 08-26首次3651行，两次相同冻结观察时间重跑九字段摘要不变；增量到08-27后22369行。四次独立原始来源回读MATCHED，0缺失/额外/重复/差异。
- 08-27真实来源18718行，三页8000/8000/2718、两runner chunk；首10000行完成后取消，恢复复用10000且仅发送8718，九字段摘要不变。
- 独立JVM CLI恢复同一冻结请求通过；取源前取消0行；2个已独立源核对的实际行发送后丢确认，经关闭发送者与准确回读UNKNOWN_RECONCILED。
- 新观察时间回补后增量通过，checkpoint仍08-27。当前batch250/1MiB、来源每日期256000行/33页/96MiB；不做无界分页。
- 08-28第27页实际捕获216000行，两个同四键amount/mkv冲突，严格拒绝该日期，SELECT确认其写入0行。正常验收的日期不代表08-28已成功；源冲突原文、PARTIAL ledger与早期250/200错配IN_DOUBT现场均保留。
- 仅限定javac编译与实际运行；未运行完整Gradle或测试集。

## 证据

- [D018-live-acceptance.json](../market-live-921d13f6c4294134a69ad37cf3feb69e/D018-live-acceptance.json)
- [D018-multichunk-recovery-72e1a33f6fa8.json](../market-live-921d13f6c4294134a69ad37cf3feb69e/D018-multichunk-recovery-72e1a33f6fa8.json)
- [D018-cli-recovery-9875c632-626f-42e8-aed2-789f701797f4.json](../market-live-921d13f6c4294134a69ad37cf3feb69e/D018-cli-recovery-9875c632-626f-42e8-aed2-789f701797f4.json)
- [D018-revision-45983799-1654-447b-81c1-1883b84da7b4.json](../market-live-921d13f6c4294134a69ad37cf3feb69e/D018-revision-45983799-1654-447b-81c1-1883b84da7b4.json)
- [来源冲突摘要](source-conflict-20260828-summary.json)
- [拒绝日期与旧失败目标实际零行](rejected-attempts-physical-readback.json)
