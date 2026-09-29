# D003 owner、管理入口与组合验收进展

本报告记录 owner 和管理阶段的验收过程；D003 后续已完成整卡验收，当前状态见 [最终验收](final-review.md)。直接本地执行，没有通过 Orca 派发。

## 已实际验证

- `local-D003-owner-check-0930`：完整真实候选 CSV 的 2343 行与隔离目标原 2274 行合并。新增 529、修订 1814、保留缺席 460，最终 2803 行逐键逐字段回读一致。相同文件重跑新增/修订为 0，物理表身份不变。正式 `index` 表身份和全部值未变。
- `local-D003-run-recovery-0930`：向空隔离 MONTH/WAL 表发布真实来源 2343 行，注入 SLICE 完成记录失败，run/attempt/slice 均保留 IN_DOUBT。同步调用已返回后确认写入者停止，移走原完成回执，利用冻结准备证据和真实 QuestDB 回读恢复三级 VERIFIED。恢复没有新来源请求或行插入；目标物理身份及全部值保持一致。
- `local-D003-cli-group-0930c`：实际应用启动执行计划，冻结绝对文件路径、SHA-256、观察日期；计划不创建 ledger。修正直接序列化 FrozenRequest 导致启动失败的问题，改用现有 SyncRequestIdentity 快照格式。拒绝缺少停止证明的恢复入口。
- `local-D003-group-live-0930`：注册 `group.index_catalog_manual`，真实文件 2343 行通过正式子任务发布；恢复同一组合时，沿用原子任务及其冻结目标身份，重新读取当前全部值后复用，不产生第二个来源/写入子任务。
- `local-D003-boundary-0930`：对已有非空隔离目录，空源返回 VERIFIED_EMPTY 且原数据不变；计划后文件 SHA-256 改变返回 FAILED；父取消返回 CANCELLED；已有整表锁返回 DATASET_INTERVAL_BUSY 且不影响持有者。四种边界均独立读取 QuestDB 确认完整目标未变。恢复原冻结文件后，重跑前述写入前失败任务成功，形成 2803 行。发布后账本失败恢复测试同时复测通过。

## 管理入口

```text
plan-index-catalog-job --file artifacts/java-migration/D003/source-catalog.csv --logical-date 2026-09-29
run-index-catalog-job --file PATH --logical-date YYYY-MM-DD [--resume-from ID]
finish-index-catalog-publication --run ID --writer-stopped true
```

执行命令使用配置中的目标表；上面的实际写入验收全部显式使用隔离目标。文件内容变更必须重新生成计划。观察日期和 import_time 均不表示文件行情日期；源文件没有声明行情 as-of，不能据此宣称最新行情。

## 仍需完成

上述阶段待办已完成：组合写入实际验收、暂存早期恢复、无变化/空源观察恢复及最终 278 项回归均通过。结果登记在 docs/migration-tasks-20260929/results/D003.json；累计 20 项 verified，人工复核 pending_review。
