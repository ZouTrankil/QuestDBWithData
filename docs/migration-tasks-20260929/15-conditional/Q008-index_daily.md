> **处置：已退出当前落库候选（retired_with_evidence）。不要按本历史任务卡创建表或执行迁移。** 依据：[Q对象处置记录](../q-object-disposition.md)。保留此卡仅用于审计/兼容追踪。

# Q008 · index_daily（未落库，待准入）

- 状态：retired_with_evidence，不再活跃准入。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`Q007`；前项验收后才执行本项。
- 数据对象：`index_daily`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 待准入单数据任务

Python声明了 `index_daily`，但审计现场没有同名对象。先确认是否为别名、未部署功能或新数据需求，不能自动建表。

- Python模型：`D:\work\fund_2\back-monitor\src\quant_platform\data\adapters\questdb\models\index\market.py`。
- 声明主时间：`trade_date`；分区：`YEAR`；声明键：`None`。
- [ ] 确认单一owner、来源、现有替代与caller，形成准入记录。
- [ ] 获准后单独冻结本数据请求模式/限流/分页及D01—D09交付，不与其他缺失模型合并。
- [ ] 未获准保持conditional，不计为已迁移或已完成。

验收：准入后按公共单数据验收；若确认无需迁移，保留逐对象决策与依据。未确认不是删除结论。

## 本任务容错、实际验收与完成登记（必做）

- [ ] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [ ] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [ ] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [ ] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [ ] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [ ] 更新[逐项完成表](../completion-register.md)的 `Q008` 行及 `results/Q008.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [ ] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## 历史执行提示（已失效）

本卡已归档，不派发、不执行建表。后续如出现新的生产者与研究消费者证据，先更新 [Q对象处置记录](../q-object-disposition.md) 并新建准入决策。
