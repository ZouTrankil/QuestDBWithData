# F013 定义、计划及串行边界复核（进行中）

本地执行 `local-F013-cancel-plan`，12 项测试通过，0 失败，0 跳过：定义 4 项、冻结计划 4 项、组合执行器 4 项。报告目录：`var/local-F013-cancel-plan-build/test-results/test`。

- 版本化成员去重；显式依赖稳定排序；未知依赖、循环和冲突成员拒绝。
- `SyncGroupPlan` 在启动前冻结所有子任务：共同参数加逐任务覆盖，统一逻辑日期，允许显式空窗口清除继承区间；无效后置成员使整组预检失败。
- `SyncGroupRunner.Request.commonParameters` 已接入同一冻结计划；每个成员仍显式声明目标。
- 全部启用与日常任务分开；日常组包含非日常任务时拒绝。
- SQLite 父子关联、第二项失败后停止、重开账本恢复未完成成员、未完成子项阻止父项完成。
- 持久化取消后保留已验证首项，第二项不会启动；子任务 CANCELLED 传播为父任务 CANCELLED。

这些测试使用模拟子执行器和真实 SQLite，不能证明外部数据写入。F013 仍为 running；真实 Tushare/QuestDB 组合链路、正式入口及恢复验收尚需补齐。累计完成数仍为 12，人工复核 pending_review。
