# D023 实际验收

- verified；人工复核 pending_review；正式表未写入。YEAR/WAL/DEDUP=false完整保留。
- 09-17真实1031行，首次/两次幂等重跑/09-18增量四轮，来源及提交5155行，最终2062行。四轮独立原始11列与SQL逐键全字段MATCHED，0差异、0重复。
- 增量回看两天并clamp到初始anchor；BACKFILL只覆盖已验证链内receipt、不推进checkpoint。实际回补后再增量成功，全表摘要一致。
- 取消前0来源/0写入；独立JVM CLI恢复原冻结请求；实际rename后丢ACK、发布后取消、发布后异常均IN_DOUBT并恢复VERIFIED；全表摘要不变。
- 实际暂存表1031行写入/回读后、发布manifest前注入异常；凭冻结请求/FETCHED原始收据/全量stage+target证据补齐manifest并发布，独立11列MATCHED。
- 再次实际暂存中断证明孤立stage阻止新计划；v2收据绑定run/request/物理stage，分别核对窗口外和完整快照，恢复后2062行摘要不变。发布丢确认也在v2证据下重新执行。
- 实际失败揭示并修正canonical日历依赖、日级查询误用批次251行上限、重复推进journal版本、adapter/runner重复retain同一锁。两次失败发布现场均实际恢复，原报告保留。
- 仅定向javac和真实隔离操作。未执行完整构建或测试集，未声称全历史、自然限流或来源真实历史修订。

## 证据

- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-live-acceptance.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-cancel-prepare.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-recovery-cli-9a4d90eb-006c-4f1e-9e15-c326a14bec71.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-recovery-publication-cfe8f402-77f7-4af4-bf0c-4f444deecc12.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-recovery-post-cancel-bf7b93b4-9ce1-4bcd-a92b-303ca7ae5825.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-recovery-post-failure-f2fa40c9-0952-48b7-b7c3-7902b37319c4.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-recovery-stage-only-f5bf0392-6005-4d79-b346-9c24b822ecbd.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-revision-a8950087-c130-41f9-a32d-a8a6cbe66e95.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-finish-4c1471d9-a8d1-480d-894b-82fc814bacaa.json
- artifacts/java-migration/market-live-a875999b9f9748b1baf7c2dfcf42b613/D023-finish-9e4af203-dda4-4751-a44d-a043b20d24db.json
