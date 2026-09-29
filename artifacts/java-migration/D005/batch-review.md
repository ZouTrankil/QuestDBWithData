# D005 多行业串行初步验收

状态：D005 running，人工 pending_review；本页只覆盖已实际执行的路径。

`IndexMembershipBatchJob` 按冻结行业顺序执行独立子run，并在父run下保留每行业slot。总时限传递到子任务；未验证子任务使批次停止，未知写入仍由原子任务保持IN_DOUBT和锁。批次控制锁防止同一账本内同时运行多个成员批次，最终核验获取数据集锁。完成批次恢复时先验证冻结请求、端点、原始来源SHA、子任务账本和该行业全部实际期间行，才复用子run。

`local-D005-batch-1047` 实际测试1通过、0跳过、0失败：

- 801011.SI Y/N → 801217.SI Y/N，顺序恰好4个请求；来源分别7条、0条，隔离目标7条。
- 两个行业对应 VERIFIED、VERIFIED_EMPTY；父run/attempt验证通过。
- 相同冻结请求恢复整批：两个子run均复用，0新增来源请求，目标身份及14列全值快照不变；父控制锁、核验锁释放。

证据：[batch-readback.json](batch-9b76342575b24fd3bc5e4c1bd600ab55/batch-readback.json)，其sync-evidence保存父计划、逐行业来源与实际SQL回读及父完成记录。

## 修复的程序问题

1044首批停止于第一行业，子任务已完成但父任务未认可。1045对原现场只读诊断发现：`JsonNode.equals` 区分IntNode/LongNode，文件中的小表ID解析为IntNode，`valueToTree`中的long ID为LongNode，错误报告before快照变化。改用Snapshot类型比较，同时修正有既存完成文件时恢复路径的JSON规范化比较。1046对原现场只读通过，未新取来源或写QuestDB；证据：[retained-child-readback.json](batch-c9efdad89c78404cb4a76ee6dda5c101/retained-child-readback.json)。临时诊断测试已移除，正常批次实际测试保留。

同一工作区另有发布WAL未就绪现场，保留其日志和IN_DOUBT；不能据1047通过宣称所有WAL现场已恢复。当前发布器等待上限为60秒，仍坚持原WAL一致性检查。

## 未完成边界

- 批次部分成功后失败、取消、父进程中断、缺子run记录及未知写入的恢复验收尚待补齐。
- 本次第二行业为空，没有发生第二次整表替换；多个非空行业连续发布后复用仍需验证。
- CLI/owner注册、跨数据sync组合、typed write组合及D005最终回归未完成。

父批次FAILED/PARTIAL表示协调未完成；是否存在未知写入必须查对应子run状态，不能依据父状态直接重试。当前resume会拒绝未终结或仍持锁的子run，先用单行业恢复入口核验。
