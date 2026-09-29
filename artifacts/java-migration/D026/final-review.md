# D026 实际验收

- verified；人工复核 pending_review；正式表未写入。YEAR/WAL/DEDUP=true保持原schema。
- 09-17首次6018行、两次幂等重跑及09-18增量，四轮主验收来源/提交30090行，最终12036行。15列原始收据与独立SQL逐键全字段MATCHED，0差异/重复/缺失/额外键。
- 取消前0来源；完整已验证页取消后恢复复用6018行、实际0次发送；独立JVM CLI恢复冻结请求成功。
- 真实发送2行后注入丢确认，独立回读UNKNOWN_RECONCILED；所有恢复操作全表摘要不变。
- BACKFILL后再次增量成功；回补不推进anchor/through。真实来源和隔离操作均有界，未运行完整构建或测试集。
- 限制：两天样本，未声称全历史、自然限流/超时、自然历史数值修订或权威空来源日期。

## 证据

- artifacts/java-migration/market-live-865c042f5f534677a8755f084aabb45d/D026-live-acceptance.json
- artifacts/java-migration/market-live-865c042f5f534677a8755f084aabb45d/D026-recovery-exercise.json
- artifacts/java-migration/market-live-865c042f5f534677a8755f084aabb45d/D026-recovery-cli.json
- artifacts/java-migration/market-live-865c042f5f534677a8755f084aabb45d/D026-revision-c548d691-3cd6-42cb-b3ab-a51ef2c27413.json
- artifacts/java-migration/D026/provider-diagnostic/offset-pagination-proof.json

## 保留失败

- Actual 6018-row response exceeded old 6000-row unpaged bound; no writes. Bounded diagnostic captured SH/SZ/BJ distinct keys and four real offset pages equalled the full 15-field response. artifacts/java-migration/market-live-6451d1f4cda64aa194fcd21d1bb09dac/D026-live-acceptance-failure.json
- First v2 startup exposed missing declared limit/offset parameters in PageContract; corrected before source/write. artifacts/java-migration/market-live-cf2ec1dfce4140e28887158003ad3d1a/D026-live-acceptance-failure.json

## 实际分页契约

v2 每日最多10000行/6页，每页2000行，短页结束。09-17实际offset 0/2000/4000/6000对应2000/2000/2000/18行，与6018行全响应全部15字段相等。该能力由当前provider实际操作证明；旧Python6000行描述与当前返回不一致，未通过扩大未分页上限宣称完整。原始收据保存每页offset/limit/返回数/键集合，生产恢复和独立回读分别核对全部键覆盖、无重复及短页结束。保留北交所代码。
