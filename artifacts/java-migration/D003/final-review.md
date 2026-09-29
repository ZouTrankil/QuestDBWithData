# D003 index 验收

状态：verified；人工复核 pending_review。真实来源和隔离 QuestDB 验收通过，正式 `index` 未切换。

## 字段、来源和增量规则

本表是历史指数目录文件，不是 Tushare `index_daily`。Python 参考项目 `D:/work/fund_2/back-monitor` 的历史导入脚本已核实，保留查找路径和来源文件哈希。读取 17 列 CSV 的上限为 5000 行、8 MiB；非法值、归一化后重复代码、错误列数或超限整体拒绝，不静默跳行。

`index_code` 为自然身份，保留前导零及 H 前缀。`base_date`、`publish_date` 使用 LocalDate；`import_time` 使用 UTC 微秒 Instant，表示采集观察，不能当行情日期或增量游标。`return_1m` 保持来源百分数点，-6.95 不转换为 -0.0695。样本数虽沿用物理 DOUBLE，但业务限制为非负整数。其他名称/类别/标记维持明确文本映射，源空值和物理空值往返一致。

沿用 MONTH/WAL/import_time 物理结构，不宣称存在物理 UPSERT KEY。写入通过有界完整合并、250 行/估算 256 KiB 分批暂存、全字段回读和持久发布日志实现幂等。WAL_REPLACE 与普通追加写、非 WAL 静态替换分别准入。文件未包含的记录保留；无变化不建新暂存、不换表。

## 实际结果

原目标 2274 行与真实来源 2343 行合并：新增 529、修订 1814、保留缺席 460，最终 2803 行。Java 对全部 18 列、Python 对来源 17 列及保留记录时间独立核对，缺失/多余键与字段差异均为 0。再次执行相同文件新增/修订均为 0，物理表身份保持不变。

文件 job、run/attempt/slice、CLI、读组合、sync 组合和准备数据写组合已接入。组合恢复先核对原冻结请求和物理目标后重新 SELECT。准备数据写组合使用两个真实文件记录验收，整文件 owner 使用全部 2343 条验收，不混淆范围。

空源、文件漂移、父取消、锁冲突均验证原目标未变；写入前失败恢复原文件后可安全重跑。发布后记账失败保持 IN_DOUBT；停止证明、冻结准备数据、目标/备份真实回读一致后补记完成。完整暂存可复用，残缺暂存保留并在新暂存重建；无变化/空源观察中断只读恢复，不写入或换表。证据冲突拒绝自动完成。

## 验证记录

- 完整回归 `local-D003-current-regression`：278 项，216 通过、62 条件跳过、0 失败、0 错误。真实库专项已单独启用执行，未把跳过当通过。
- 完整 owner：`local-D003-owner-check-0930`。
- CLI/注册：`local-D003-cli-group-0930c`；sync 组合：`local-D003-group-live-0930`。
- 边界/三级恢复：`local-D003-boundary-0930`。
- 发布前恢复：`local-D003-early-recovery-0930b`；原失败现场恢复：`local-D003-early-observed-0930`。
- 无写入恢复：`local-D003-no-write-recovery-0930`。
- 准备数据组合与文件恢复复测：`local-D003-write-regression-0930`；准备数据回执恢复：`local-D003-prepared-recovery`。
- 最终 prepared 入口写入、组合复用和中断恢复：`local-D003-prepared-final`，2/2 真实隔离表测试通过；详见[prepared 写入组验收](../../../artifacts/java-migration/D003/prepared-write-review.md)。
- 最终只读核验：正式 `index` 物理 ID 332、2274 行，与原基线全部 18 列逐键比对 0 差异；见[正式表只读回执](../../../artifacts/java-migration/D003/production-final-readonly.json)。
- 完整结果和逐条比对入口：[D003.json](../../../docs/migration-tasks-20260929/results/D003.json)。

限制：候选文件没有行情 as-of，不能证明最新行情；未切生产表；测试构造了真实持久中断状态并使用 Error 注入，但没有实际杀 OS 进程。保留的失败现场和备份见早期恢复报告，不自动处理其他会话的现场。
