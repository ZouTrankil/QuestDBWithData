# D020 真实验收结果

- verified；人工复核 pending_review，正式表未修改。
- Python五指数全部获得非空来源，含000852.SH；各首次2行、两次重跑2行、增量3行。20轮独立12列原始来源比较MATCHED，最终15行。
- 每个代码的checkpoint独立；回补后再次增量不改变checkpoint，其他代码摘要不变。09-27真实空源为VERIFIED_EMPTY。
- 五代码取消/恢复各复用2行、发送0次；五个独立JVM CLI恢复原冻结请求；实际写入后注入丢ACK并全键回读UNKNOWN_RECONCILED。
- 修复Coverage括号、缺失导入、Page.cursor误记endpoint；失败报告保留。补充source遗漏已有键时发送前拒绝的保护，最终修订和空窗口操作使用该版本。
- 无实际数值修订时只报告重取与checkpoint行为；未声称自然触顶或限流故障通过。仅定向javac和真实操作，未运行完整构建/测试集。

## 证据

- [D020-live-acceptance.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-live-acceptance.json)
- [D020-recovery-000016-SH-33ff703c-a752-4ace-b2ac-23b45f777b8e.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-recovery-000016-SH-33ff703c-a752-4ace-b2ac-23b45f777b8e.json)
- [D020-recovery-000300-SH-b170d521-c199-42de-818f-dfdff62237a3.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-recovery-000300-SH-b170d521-c199-42de-818f-dfdff62237a3.json)
- [D020-recovery-000852-SH-3eaa8fe4-66e3-44be-bacc-4f63d232105c.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-recovery-000852-SH-3eaa8fe4-66e3-44be-bacc-4f63d232105c.json)
- [D020-recovery-000905-SH-1677cfb4-edfc-44c7-a517-0780cbac1379.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-recovery-000905-SH-1677cfb4-edfc-44c7-a517-0780cbac1379.json)
- [D020-recovery-399006-SZ-ca54e514-8f83-4eba-a137-e935060878df.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-recovery-399006-SZ-ca54e514-8f83-4eba-a137-e935060878df.json)
- [D020-cli-recovery-00753387-cc57-450c-a497-9d9c6b902502.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-cli-recovery-00753387-cc57-450c-a497-9d9c6b902502.json)
- [D020-cli-recovery-1e2ace58-f1aa-47c6-a033-147afa8c399e.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-cli-recovery-1e2ace58-f1aa-47c6-a033-147afa8c399e.json)
- [D020-cli-recovery-489cc518-1845-4ea3-b89b-5216500699da.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-cli-recovery-489cc518-1845-4ea3-b89b-5216500699da.json)
- [D020-cli-recovery-75d97a14-7aaa-4354-aeea-edef8c5b1407.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-cli-recovery-75d97a14-7aaa-4354-aeea-edef8c5b1407.json)
- [D020-cli-recovery-fbd41865-6504-40f5-ac8a-56eba616d6dc.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-cli-recovery-fbd41865-6504-40f5-ac8a-56eba616d6dc.json)
- [D020-revision-31f7a340-edba-403a-b2e2-322312234141.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-revision-31f7a340-edba-403a-b2e2-322312234141.json)
- [D020-revision-32342915-686b-468d-8924-532a1b7fc2e2.json](../market-live-5e8d6b3b491c4cedb12fcd1d45b0bf35/D020-revision-32342915-686b-468d-8924-532a1b7fc2e2.json)
