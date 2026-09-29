# D004 增量保留、正式 owner 与取消边界

本地直接执行，不使用 Orca。D004 仍为 running，人工 pending_review。

## 修正

- 完整目录观察采用内容增量，不根据观察时间判断业务修订先后。当前任务卡明确规定 Java 默认 INCREMENTAL 保留来源缺席的旧代码；删除须另有显式 reconcile 准入，这与 Python 原有全量覆盖不同。完整来源缩水超过 2% 时仍拒绝并等待核验。
- merge、暂存和回读的缺失代码语义已统一。受控缺席一条时 removed=0、retainedAbsent=1，现有 2517 行与原物理身份保持不变。此场景不能当作真实上游删除证据。
- PageExecutor 在取数后收到取消会抛出 Incomplete；owner 现同时读取取消状态，将未提交写入的 run/attempt/slice 记为 CANCELLED。已开始写入的未知状态仍保持 IN_DOUBT。

## 实际证据

- `local-D004-owner-incremental-1004`：增量合并 3 项和正式 owner 实际验收 1 项通过。首次实际 Tushare 来源写入隔离表，重跑无变化，不换物理表；生产表只读且未变化。完整回执：`owner-7f8f2ab2aed24e21a94e81e482a933b0/owner-readback.json`。
- `local-D004-retain-absent-1005`：保留的真实来源 2517 条，隔离暂存从 1 条增量至 2517 条，新增 2516，11 批。模拟从完整来源移除一条后 retainedAbsent=1、removed=0、requiresWrite=false；实际 SELECT 仍有原 2517 条，7 字段一致。此为受控缺席场景，不能称为真实上游删除。回执：`staging-b0ff972dd05445ea86425a2129b6eb15/omission-readback.json`。
- 同一 staging 目录的 `independent-source-values.json`：Python 独立核对六个源业务字段，2517 条，缺失/额外键及字段差异均为 0，12 条 count 为空。
- `local-D004-owner-boundary-1007`：空完整目录、来源异常、取数后取消、整表锁冲突均通过；真实隔离 QuestDB 预先保留一条生产来源记录，前后物理身份及全部字段一致。取消时三级账本均 CANCELLED，其他来源失败为 FAILED，无发布日志，锁释放；冲突者的锁保留且未取数。回执：`boundary-555b0f73e1f24a52a3a14594bfbe8558/boundary-readback.json`。

## 失败记录与验收边界

- 当前完整回归 `local-D004-current-regression-1008`：299 项，225 通过、74 条件跳过、0 失败/错误。实际数据库与来源专项使用各自 opt-in 执行，不将跳过项算作验收通过。

- `local-D004-owner-incremental-1003` 编译失败，原因是当时恢复类仍引用不存在的文件来源类型；当前 provider receipt 实现已能编译并执行上述验收。
- `local-D004-owner-boundary-1006` 暴露取消误记 FAILED。失败现场 `boundary-1445c48dec324316bd92ac7dee7cc7bd` 保留；未修改其历史状态来伪造通过。后续修复用新的隔离现场完成验收。
- 暂存/发布恢复已有独立场景证据；正式 owner 的中断恢复、组合 sync/write 和最终整体验收仍需完成，不能据上述部分通过提升 D004 为 verified。
