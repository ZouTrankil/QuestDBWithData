# D024 实际验收

- verified；人工复核 pending_review；正式表未写入。YEAR/WAL/DEDUP=true保持原schema。
- 09-17首次5553行、两次幂等重跑及09-18增量，四轮主验收来源/提交27765行，最终11106行。20列原始收据与独立SQL逐键全字段MATCHED，0差异/重复/缺失/额外键。
- 取消前0来源；完整已验证页取消后恢复复用5553行、实际0次发送；独立JVM CLI恢复冻结请求成功。
- 真实发送2行后注入丢确认，独立回读UNKNOWN_RECONCILED；所有恢复操作全表摘要不变。
- BACKFILL后再次增量成功；回补不推进anchor/through。真实来源和隔离操作均有界，未运行完整构建或测试集。
- 限制：两天样本，未声称全历史、自然限流/超时、自然历史数值修订或权威空来源日期。

## 证据

- artifacts/java-migration/market-live-e87794d99a684e6fbffcdd483d24a33e/D024-live-acceptance.json
- artifacts/java-migration/market-live-e87794d99a684e6fbffcdd483d24a33e/D024-recovery-exercise.json
- artifacts/java-migration/market-live-e87794d99a684e6fbffcdd483d24a33e/D024-recovery-cli.json
- artifacts/java-migration/market-live-e87794d99a684e6fbffcdd483d24a33e/D024-revision-f158610f-e748-472d-bed2-de20fc3ae430.json

## 保留失败

- Actual 5553-row response included BJ stocks; old SZ/SH-only validator rejected before writes. Source/domain/independent readback now accept observed BJ codes. Original incomplete receipt retained. artifacts/java-migration/market-live-eba84fb653b74354ad514bbb6737db67/D024-live-acceptance-failure.json
