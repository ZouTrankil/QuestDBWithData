# D025 实际验收

- verified；人工复核 pending_review；正式表未写入。YEAR/WAL/DEDUP=true保持原schema。
- 09-17首次5216行、两次幂等重跑及09-18增量，四轮主验收来源/提交26080行，最终10432行。13列原始收据与独立SQL逐键全字段MATCHED，0差异/重复/缺失/额外键。
- 取消前0来源；完整已验证页取消后恢复复用5216行、实际0次发送；独立JVM CLI恢复冻结请求成功。
- 真实发送2行后注入丢确认，独立回读UNKNOWN_RECONCILED；所有恢复操作全表摘要不变。
- BACKFILL后再次增量成功；回补不推进anchor/through。真实来源和隔离操作均有界，未运行完整构建或测试集。
- 限制：两天样本，未声称全历史、自然限流/超时、自然历史数值修订或权威空来源日期。

## 证据

- artifacts/java-migration/market-live-8b7caadbf37548ea8a90ddd9a849709e/D025-live-acceptance.json
- artifacts/java-migration/market-live-8b7caadbf37548ea8a90ddd9a849709e/D025-recovery-exercise.json
- artifacts/java-migration/market-live-8b7caadbf37548ea8a90ddd9a849709e/D025-recovery-cli.json
- artifacts/java-migration/market-live-8b7caadbf37548ea8a90ddd9a849709e/D025-revision-71daa10f-a9e3-4f95-aacc-4c33eed35df0.json
