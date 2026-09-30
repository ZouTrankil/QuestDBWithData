> **历史专项标准：对象已退出当前落库候选，不再等待准入，也不应据此建表。** 处置理由：[Q对象处置记录](../../migration-tasks-20260929/q-object-disposition.md)。

# Q006 · factor_exposure_snapshot_daily 待准入验收

[索引](../datasets.md) · [原模型声明](../../migration-tasks-20260929/15-conditional/Q006-factor_exposure_snapshot_daily.md)

此对象未在既有目标审计中落库，不能编造已存在的物理列或SQL。

## 对象专项门槛

- [ ] 同日同实体多因子及多定义版本不得覆盖；暴露矩阵的实体/因子坐标与缺失规则明确。
- [ ] 提交当前Python模型的全字段/类型、实际调用方、来源和唯一owner，决定独立迁移、并入现有对象或不迁移；用户逐项确认。
- [ ] 准入后提交与tables目录同等粒度的字段映射矩阵、完整键冲突样例、明确输入/预期值及真实非空来源回读证据；这些缺失前状态只能conditional/blocked，不算验收标准已冻结。

这里是准入门槛，不是现成表的完成认证。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
