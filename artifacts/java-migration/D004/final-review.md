# D004 验收记录

实现与数据验收 verified；人工 pending_review。仅隔离 QuestDB 实际写入，生产 ths_index 保持只读。不是生产切换或自动调度准入。

## D01–D09 对照

| 要求 | 实现及实际证据 |
| --- | --- |
| D01 类型映射 | TushareThsIndexDto → ThsIndex → ThsIndexMapper → ThsIndexRow；2517 条真实行往返、非法日期/负数/类型/微秒精度校验。字段表见任务卡。 |
| D02 自然键 | ts_code；保留字母后缀。按业务内容增量，新 observed_at 不会制造重复当前行；缺席保留，不隐式删除。 |
| D03 物理语义 | MONTH/WAL，物理 dedup(ts_code,update_time)，主时间 update_time；采用暂存、全值回读、备份后改名维持自然键唯一。未迁移生产表。 |
| D04 读 | 实际 2517 条，10 页有界读取；七字段独立 SQL 一致，读取组合投影通过，见 read-live.json。 |
| D05 写 | 每批最多250行/估算256 KiB；实际暂存1→2517、11批，七字段回读及Python六来源字段独立比对通过；typed组合2条真实数据与恢复通过。 |
| D06 来源 | ths_index单次显式有界观察，上限5000/8MiB，触顶拒绝；来源样例、首次真实owner2517与重跑通过。不虚构历史日期或分页。 |
| D07 管理 | DatasetDefinition/SyncJobDefinition、CLI计划/运行/恢复、三级账本、sync组合与typed写组合已接入。 |
| D08 容错 | 重复/非法值/触顶/空完整目录/来源失败/取消/锁冲突；发布前残缺和完整stage、旧表/新表改名中断、已发布缺回执、无变化恢复；prepared回执漂移拒绝。详见两份审核说明。 |
| D09 示例与结果 | 本文CLI示例、results/D004.json及逐项完成表；实际数据库回执与独立比对留存，人工复核待办独立。 |

## 可执行入口示例

在已配置数据库及来源环境的 Java 程序入口传入下列参数（示例不是额外执行记录）：

```text
plan-ths-index-job --logical-date 2026-09-29
run-ths-index-job --logical-date 2026-09-29
run-ths-index-job --logical-date 2026-09-29 --resume-from <failed-before-write-run>
finish-ths-index-publication --run <unfinished-run> --writer-stopped true
```

计划为 INCREMENTAL，来源参数为空表示当前完整目录，不表示全历史。首次目标需显式配置隔离表。恢复命令的 writer-stopped 必须有实际生产者停止证据；未知写入不能用 resume 重新取数覆盖。组合 ID 为 group.ths_index_manual；prepared写入dataset_id为ths_index。

## 最新验证

- local-D004-prepared-recovery-1014：发布完成但账本确认失败的 prepared write，停止证明缺失/回执漂移拒绝；恢复后三级 VERIFIED、锁释放、实际两行七字段及物理身份不变。
- local-D004-final-regression-1015：306 项，226 通过、80 条件跳过、0 失败。条件跳过的实际专项有各自 opt-in 执行记录，不算本次通过。
- local-D004-incremental-final-1016：修正后实际暂存和缺席保留专项通过，2517 条完整回读及无隐式删除重新验证。独立字段比对位于 staging-66492e153e1d42a0bddb2997c9a34325。

## 验证边界与人工比对

- 同一真实来源重跑没有新增修订；真实上游修订未观测到，修订覆盖使用明确受控测试，不能声称真实修订验收。
- 单次目录接口无可用日期checkpoint；checkpoint为已实际回读的物理身份与完整内容指纹，不是MAX(update_time)。
- 初始真实来源 2517，首次插入2517；重跑未变2517、插入/修订0；受控缺席1条仍保留2517，removed=0。
- 已修正工作区曾出现的隐式删除变更；若后续再次改变此契约，必须重新验收，不得复用本报告作为新代码证明。
- 没有OS进程强杀试验，使用持久中断状态、Error或账本失败注入；失败现场留档。没有生产切换或定时同步。
- 用户逐个核对：自然键、六个来源字段、observed_at不变规则、空count、2517完整回读、取消与恢复三级账本、保留备份与失败现场。人工验收仍 pending_review。
