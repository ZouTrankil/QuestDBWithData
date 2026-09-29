# D012 实际验收

- 状态 verified；人工复核 pending_review。
- 本地 QuestDB 10.0.1；隔离表 `java_d012_stk_st_daily_96fe6e81659b405e8431d8324dfeac24`；正式表未修改。
- 首次 280 行，两次同窗口重跑一致，增量后 559 行；完整键及全部 3 列独立来源核对零差异、零重复。
- 取源前取消、完整页后取消及 CLI 冻结请求恢复通过；复用 280 行，stage 重建可能写入。
- 受控注入旧 positive key 后，完整真实源替换撤回该记录；空窗口也可撤回，窗口外数据保持一致。
- 发送后丢确认的两个真实行由实际 SELECT 回读确认；非空和空窗口均实际触发 rename 确认丢失，IN_DOUBT 可恢复为 VERIFIED / VERIFIED_EMPTY 并释放本 run 锁。
- Java 24 范围编译和实际操作已完成；没有声称新增或运行测试集、自然限流/超时故障或触顶响应演练。

修复 DISTINCT 排序别名、非 ST 名称记录的字母代码保留、失败原始响应留存、增量重叠下界、stage WAL 等待及无 SSE 交易日的权威空窗口发布。 早期失败现场和失败报告均保留。

## 证据

- [D012-live-acceptance.json](../market-live-96fe6e81659b405e8431d8324dfeac24/D012-live-acceptance.json)
- [D012-independent-source-readback.json](../market-live-96fe6e81659b405e8431d8324dfeac24/D012-independent-source-readback.json)
- [D012-window-recovery-2af8d485-8a7d-43b6-925d-f663b5d3ae7a.json](../market-live-96fe6e81659b405e8431d8324dfeac24/D012-window-recovery-2af8d485-8a7d-43b6-925d-f663b5d3ae7a.json)
- [D012-empty-window-0fc0d2e7-e93c-4276-8f44-ec066e4c2e89.json](../market-live-96fe6e81659b405e8431d8324dfeac24/D012-empty-window-0fc0d2e7-e93c-4276-8f44-ec066e4c2e89.json)
- [D012-publication-recovery-5d4a0db1-5785-4668-94f7-6560a8fa5cfa.json](../market-live-96fe6e81659b405e8431d8324dfeac24/D012-publication-recovery-5d4a0db1-5785-4668-94f7-6560a8fa5cfa.json)
