# F013 · 批量同步组合定义与串行运行

- 状态：planned，尚未派发。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`F012`；前项验收后才执行本项。
- 数据对象：`无，公共功能任务`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 功能范围

实现可版本化SyncGroupDefinition，按有序单数据job列表/显式依赖编排，统一区间参数及逐数据覆盖，默认串行fail-fast；父run引用每个child run。

## 实施清单

- [ ] 明确接口、配置及依赖，保持现有包职责。
- [ ] 实现本功能并接入已有入口；不附带实现新数据集。
- [ ] 提供本功能独立测试与调用示例。
- [ ] 记录边界、失败语义及后续数据任务使用方式。

## 验收

用模拟dataset验证重复成员去重、未知job拒绝、顺序、失败停止、resume只补未完成项、全子任务verified后父任务才完成；allEnabled与dailyEligible分开。

## 代码依据

src/quant_platform/data/adapters/config/tables.py。Java文件相对本工作区，其余src/quant_platform文件相对Python工作区。

## 本功能容错、实际验收与完成登记

- [ ] 按本卡功能验证参数错误、异常、取消/恢复等适用分支，不把mock成功当真实外部链路成功。
- [ ] F001只读QuestDB schema和有界样本；其他功能按公共契约“公共功能按职责验收”执行。实际涉及读写时必须读取QuestDB真实数据，涉及写入时必须写后回读。
- [ ] 复用已有stock_basic或前置功能的隔离验证链路，不提前实现后面的其他数据；不适用sync/写入的功能在记录中注明N/A及原因。
- [ ] 更新[逐项完成表](../completion-register.md)的 `F013` 行与 `results/F013.json`，保留真实请求、查询、容错和完成证据。
- [ ] 只有本功能适用验收全部满足才记verified；无实际证据记implemented_not_verified/blocked，人工复核保持pending_review。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 F013：批量同步组合定义与串行运行。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/01-functions/F013-sync-composition.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 F012 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/F013.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。 本卡为公共功能，请按职责适用规则验收；F001只读核对，不为满足通用提示而启动sync或写入。
```
