# D019 真实验收结果

- verified；人工复核 pending_review，正式表未修改。
- 同一隔离表的 000300.SH/index_daily 与 801080.SI/sw_daily 各首次2行、两次重跑各2行、增量3行；八次12物理列独立原始来源比较 MATCHED，最终6行。
- 每代码 bootstrap/checkpoint 独立，回补不推进增量checkpoint；新的 observedAt 回补后再次增量通过，另一代码全列摘要不变。
- 两路线取源前取消、整页后取消与恢复通过；各复用2行、发送0次；独立JVM CLI保留原冻结请求。实际两行写入后注入ACK丢失，完整键回读确认 UNKNOWN_RECONCILED。
- 两路线09-27真实空来源均 VERIFIED_EMPTY，全表摘要不变。
- 修复 update_time 的 INSTANT/TECHNICAL 类型不匹配，以及 SW 请求未提供的 pre_close；SW显式保存null、不推导。失败记录保留。
- 仅定向javac及真实操作，未运行完整构建或测试集；本次只抽取两代码有界日期，未宣称全88代码历史回填。

## 证据

- [D019-live-acceptance.json](../market-live-8e5078c8551b4b2ba086f80b6ed85bc1/D019-live-acceptance.json)
- [D019-recovery-000300-SH-1d5ab040-e82f-42d9-9d07-e97e1d08099d.json](../market-live-8e5078c8551b4b2ba086f80b6ed85bc1/D019-recovery-000300-SH-1d5ab040-e82f-42d9-9d07-e97e1d08099d.json)
- [D019-recovery-801080-SI-de9d944e-0a59-4fac-b54e-702d7398d5bb.json](../market-live-8e5078c8551b4b2ba086f80b6ed85bc1/D019-recovery-801080-SI-de9d944e-0a59-4fac-b54e-702d7398d5bb.json)
- [D019-cli-recovery-4816ce80-9f6d-4bf8-8ded-1fed1c813f9d.json](../market-live-8e5078c8551b4b2ba086f80b6ed85bc1/D019-cli-recovery-4816ce80-9f6d-4bf8-8ded-1fed1c813f9d.json)
- [D019-cli-recovery-945c271a-e8ef-4fea-8926-f6f028c06616.json](../market-live-8e5078c8551b4b2ba086f80b6ed85bc1/D019-cli-recovery-945c271a-e8ef-4fea-8926-f6f028c06616.json)
- [D019-revision-cc631ae7-2efd-4860-a78d-ed15b39c0e50.json](../market-live-8e5078c8551b4b2ba086f80b6ed85bc1/D019-revision-cc631ae7-2efd-4860-a78d-ed15b39c0e50.json)
