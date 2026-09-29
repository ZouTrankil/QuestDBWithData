# D005 index_member 验收记录

实现与隔离数据验收：verified。人工复核：pending_review。正式 `index_member` 只读，未做 Python 消费者切换、生产写入或自动调度准入。

## D01–D09 对照

| 要求 | 已验证实现与证据 |
| --- | --- |
| D01 类型与字段 | `TushareIndexMembershipDto`、`IndexMembership`、`IndexMembershipMapper` 显式映射 14 个物理字段；`in_date` 是期间键，`out_date` 可修订，`observed_at` 是观察时间。正式表旧值 `out_date="None"` 在 typed read 中变为 null，未修改正式行。 |
| D02 键与幂等 | 完整业务键为 `(index_code,ts_code,membership_start_date)`；YEAR/WAL 表无物理 dedup。来源合并保留缺席期间及 endpoint 未提供的 `weight/con_code`，观察时间变化不制造来源修订；准备写入明确拥有全部 14 字段。 |
| D03 物理契约 | `update_time` 为主时间，YEAR 分区、WAL、无 dedup；暂存整表全值校验后通过带备份与期望物理 ID 的改名日志发布，仅对隔离目标准入。正式表在验收后为 ID 1760、5902 行、WAL writerTxn=sequencerTxn=2、buffered=0、未 suspended。 |
| D04 读取 | `IndexMembershipReadRepository` 按完整字段进行 typed 分页；正式 5902 条的有界读和原始 SQL 对照通过。读取组合已接入。 |
| D05 写入 | 单行业准备行通过 `write.index_member`、独立 `prepared-write-request` 回执、`WAL_REPLACE` 写入组合。隔离表 2 条全字段回读、原组恢复复用、同输入新运行 0 次暂存写入；暂存/已发布两个中断点均可在显式停写证明后恢复。跨数据 THS→membership 串行写入及部分失败恢复通过。 |
| D06 来源 | 先 `index_classify(level=L2,src=SW2021)` 冻结 134 行及 SHA，再逐 L2 向 `index_member_all` 显式请求 Y/N。有界页、触顶/短响应拒绝；真实 801011.SI 首次 Y4/N3 共7条，隔离表写入7条，第二次真实请求仍7条但0写入；801217.SI 真实0行记 `VERIFIED_EMPTY`。 |
| D07 管理 | `IndexMembershipJobService` 注册 `data.index_member@1`、CLI 发现/离线计划/运行/恢复、三级 run/attempt/slice 账本、同步组合及写入组合。状态查询复用通用 run/job 入口。 |
| D08 容错 | 分类与成员原始回执校验、有限请求/重试/取消、整表快照连续性、锁与 `IN_DOUBT` 保留、发布各阶段及无写入恢复、多行业串行及已验子任务复用、外部漂移拒绝。协调器在计划后或 slot 创建后首个子任务前中断，两种空前缀恢复均通过。 |
| D09 实际核对 | `slice-owner-8cd23f9f66674a8195db5b74ae807eec` 真实来源首次7行、隔离写7行、全14字段相等；独立 Python 对照9个来源字段，缺键0、差异0。另有5902→5905暂存全值校验、两个非空行业连续发布、正式表不变证明。结果见 `results/D005.json`。 |

## 最新回归

- `local-D005-full-live-build`：48 项，46 通过、2 条未开启 bounded-read 开关而跳过、0 失败；包含真实 Tushare 请求及隔离 QuestDB 写入。长来源等待最终自行完成，未将等待当作通过。
- `local-D005-final-read-build`：上述两个只读类实际通过、0 失败。
- `local-D005-global-regression-build`：项目常规回归 351 项，244 通过、107 条件跳过、0 失败。条件跳过不计为本轮已执行；D005 的来源/写入/读取场景有上述专项通过记录。
- 准备写入、已暂存/已发布恢复、跨表部分失败及提前终止专项见 [prepared-owner-review.md](prepared-owner-review.md) 和 [batch-early-recovery-review.md](batch-early-recovery-review.md)。

## 来源与物理边界

正式表有131个行业代码；分类源有134个。三个只在分类源出现的代码 `801217.SI`、`801768.SI`、`801786.SI` 的 Y/N 请求各返回0行。`is_pub=0` 不能作为跳过行业条件：`801011.SI` 标记为0但返回Y4/N3。历史 `N` 行只进入隔离验收；现有 Python 消费者未全部筛 `is_new=Y`，因此正式表不能在本任务内直接改为 Y/N 混合。

独立核对回执：[owner-readback.json](slice-owner-8cd23f9f66674a8195db5b74ae807eec/owner-readback.json) 与 [independent-source-values.json](slice-owner-8cd23f9f66674a8195db5b74ae807eec/independent-source-values.json)。首次 run `membership-21704b96-19ce-4a8d-a1dd-6a8832c6af67` 的目标物理 ID 2364→2365，checkpoint 内容指纹 `4f53cda1...`→`d05a7abf...`；真实重跑 7 行未变，提交暂存行0。成功隔离测试的目标和备份按各测试清理，SQLite 账本与 JSON 回执保留；失败现场不冒充成功。

## 限制与人工核对

真实上游字段修订本次未发生；修订逻辑通过受控测试验证。中断用故障注入/Java `Error` 模拟，没有操作系统强杀试验。来源 Y/N 两次请求分别带捕获时间，接口未提供共同快照版本，不能声称原子来源快照。`update_time` 不是上游有效日期或可靠修订游标，checkpoint 使用完成回读后的物理身份与完整内容指纹。

人工核对待办：复核 14 字段、期间键、旧 `None` 兼容、分类134与正式131的差额、来源Y/N真实回执、独立9字段比对、正式表保持5902条、两个写入恢复阶段和账本状态。人工状态保持 `pending_review`。
