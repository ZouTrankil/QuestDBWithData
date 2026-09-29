# D022 实际验收

- verified；人工复核 pending_review；正式表未写入。隔离目标保留 YEAR/WAL/DEDUP=false。
- 三代码（含 CSI→SH 别名）各首次两个月、两次幂等重跑、增量到08-31；12轮27来源/提交行，最终9行。独立原始收据解析和直接SQL核对14列，0差异、0重复；其他代码和窗口外内容保留。
- 三代码新观察时间回补后再增量；回补不推进checkpoint。1990-01上市前空窗均0行且全表摘要不变；未结束当月拒绝计划。
- 取消前0请求/0写入、独立JVM恢复冻结CLI请求、实际rename后丢ACK恢复通过。真实早期stage两行/target零行失败，经READY intent、原始来源、物理身份及全快照验证后恢复发布。
- 实际发布完成后注入取消与收尾异常均保持IN_DOUBT和lease；finishInterrupted恢复到VERIFIED，14列独立一致，全9行摘要不变。
- 新Adapter.recoveryRequired默认false，D022依据本run publication/stage证据报告恢复需求；错误检查按不确定处理。保留不允许转IN_DOUBT的slice原态，分别持久化attempt/run，避免slice非法转换阻断闭账。
- 早期Spring代理、ledger初始化、DEDUP准备、capability和身份/数值证据失败均保留；未将这些失败冒充完成。仅定向javac和真实操作，未执行完整构建或测试集。

## 证据

- artifacts/java-migration/market-live-b2c209f9df7d49f192b6d90a97586970/D022-cancel-prepare.json
- artifacts/java-migration/market-live-b2c209f9df7d49f192b6d90a97586970/D022-live-acceptance.json
- artifacts/java-migration/market-live-b2c209f9df7d49f192b6d90a97586970/D022-recovery-cli-355b4016-290c-451d-aa7a-306a419af384.json
- artifacts/java-migration/market-live-b2c209f9df7d49f192b6d90a97586970/D022-recovery-post-cancel-69aa34a1-f7d5-43e7-9bf3-477e44ef712d.json
- artifacts/java-migration/market-live-b2c209f9df7d49f192b6d90a97586970/D022-recovery-post-failure-11497e26-08bc-4287-a6cc-8467a67bc607.json
- artifacts/java-migration/market-live-b2c209f9df7d49f192b6d90a97586970/D022-recovery-publication-3c1f1f43-f619-458e-a8fe-b0dc42d6c0cc.json
- artifacts/java-migration/market-live-b2c209f9df7d49f192b6d90a97586970/D022-revision-empty-fdf90b18-daad-4e47-bbaf-b83ff04f089e.json
- artifacts/java-migration/market-live-f474ede9865245d987ee0ab517289aa6/D022-finish-340ad7f8-58ba-4eb5-a2ad-0716e7b36722.json
- artifacts/java-migration/market-live-f474ede9865245d987ee0ab517289aa6/target-stage-physical-counts.json
