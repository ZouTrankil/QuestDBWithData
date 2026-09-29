# F012 · 断点恢复与任务取消

- 状态：verified，本地执行；人工复核 pending_review。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`F011`；前项验收后才执行本项。
- 数据对象：`无，公共功能任务`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 功能范围

按定义版本+参数+源指纹恢复已验证片；区分provider重试和未知写入的核验。支持取消、停止后续片、原attempt审计保留。

## 实施清单

- [x] 明确接口、配置及依赖，保持现有包职责。
- [x] 实现本功能并接入已有入口；不附带实现新数据集。
- [x] 提供本功能独立测试与调用示例。
- [x] 记录边界、失败语义及后续数据任务使用方式。

## 验收

模拟第二页失败与提交后失联；恢复不重复已验证片，不将可变源分页游标盲续为同一快照；进程租约到期不等于旧writer已退出。

## 代码依据

src/quant_platform/data/adapters/connectors/sync_recovery.py。Java文件相对本工作区，其余src/quant_platform文件相对Python工作区。

## 本功能容错、实际验收与完成登记

- [x] 按本卡功能验证参数错误、异常、取消/恢复等适用分支，不把mock成功当真实外部链路成功。
- [x] F001只读QuestDB schema和有界样本；其他功能按公共契约“公共功能按职责验收”执行。实际涉及读写时必须读取QuestDB真实数据，涉及写入时必须写后回读。
- [x] 复用已有stock_basic或前置功能的隔离验证链路，不提前实现后面的其他数据；不适用sync/写入的功能在记录中注明N/A及原因。
- [x] 更新[逐项完成表](../completion-register.md)的 `F012` 行与 `results/F012.json`，保留真实请求、查询、容错和完成证据。
- [x] 只有本功能适用验收全部满足才记verified；无实际证据记implemented_not_verified/blocked，人工复核保持pending_review。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 F012：断点恢复与任务取消。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/01-functions/F012-resume.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 F011 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/F012.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。 本卡为公共功能，请按职责适用规则验收；F001只读核对，不为满足通用提示而启动sync或写入。
```
