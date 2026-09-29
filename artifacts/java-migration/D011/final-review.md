# D011 实际验收

- 状态 verified；人工复核 pending_review。
- 本地 QuestDB 10.0.1；隔离表 `java_d011_stk_suspend_00f1a5c441d14abe877cd13a99139970`；正式表未修改。
- 首次 12 行，两次同窗口重跑一致，增量后 25 行；完整键及全部 3 列独立来源核对零差异、零重复。
- 取源前取消、完整页后取消及 CLI 冻结请求恢复通过；复用 12 行，stage 重建可能写入。
- 受控注入旧 positive key 后，完整真实源替换撤回该记录；空窗口也可撤回，窗口外数据保持一致。
- 发送后丢确认的两个真实行由实际 SELECT 回读确认；非空和空窗口均实际触发 rename 确认丢失，IN_DOUBT 可恢复为 VERIFIED / VERIFIED_EMPTY 并释放本 run 锁。
- Java 24 范围编译和实际操作已完成；没有声称新增或运行测试集、自然限流/超时故障或触顶响应演练。

修复 stage WAL 等待、重复窗口处理和冻结请求恢复；非空及空发布收尾从保存的原始收据核验后闭账。 早期失败现场和失败报告均保留。

## 证据

- [D011-live-acceptance.json](../market-live-00f1a5c441d14abe877cd13a99139970/D011-live-acceptance.json)
- [D011-window-recovery-544515e6-0401-4da9-a631-c1b152e9dde4.json](../market-live-00f1a5c441d14abe877cd13a99139970/D011-window-recovery-544515e6-0401-4da9-a631-c1b152e9dde4.json)
- [D011-empty-window-b93e890d-9fd9-4d89-854c-091b2592f455.json](../market-live-00f1a5c441d14abe877cd13a99139970/D011-empty-window-b93e890d-9fd9-4d89-854c-091b2592f455.json)
- [D011-publication-recovery-d360857e-bec4-4ae4-817a-00235ffd3437.json](../market-live-00f1a5c441d14abe877cd13a99139970/D011-publication-recovery-d360857e-bec4-4ae4-817a-00235ffd3437.json)

实际首次 rename 确认丢失曾错记 PARTIAL；失败发布依据日志完成，历史状态保持 PARTIAL。修复 adapter 的不确定状态传播后重新演练，非空/空 run 均 IN_DOUBT → verified 并释放本 run 锁；未改写早期失败为成功。
