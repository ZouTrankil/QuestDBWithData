# D005 两个非空行业及CLI入口

D005仍running，人工pending_review。

`local-D005-cli-chain-1052`：离线计划启动及两次非空发布链两项通过、0跳过。保存的真实来源801011.SI当前成员4条、801207.SI当前成员2条，依次发布；第二次before快照完整等于第一次actual快照，物理ID两次更换，最终6条。完整批次恢复均复用、0新来源调用。随后仅在隔离表插入第三行业的受控漂移行，整批恢复在任何新取数前拒绝。漂移行不是业务来源，也不计入正向验收数据。

证据：[chain-readback.json](batch-chain-80f5f14ce85145b2b03abe312d949a84/chain-readback.json)。整表连续性核验覆盖全部行业，不仅核对被请求行业，因此外部行业的插入也会阻止复用。

`local-D005-cli-live-1054`：真实CLI执行及离线规划2项通过、0跳过。经注册的 `IndexMembershipJobService` 和命令入口新取两个行业CURRENT响应，依次写入4+2条，实际14列回读及完整发布链验证。CLI恢复复用两个子任务，最终表身份/全部行不变；`show-sync-run`可查询父/attempt/industry记录。

证据：[cli-readback.json](cli-6e32689ca9b647b882620129a8f6045a/cli-readback.json)及其sync-evidence。离线计划测试使用不可连接的数据库地址仍通过，且没有创建运行账本。错误SHA、缺行业参数、计划混入resume参数、缺少停止确认均拒绝。

1053曾在计划阶段意外访问尚未创建的隔离表，启动失败；最终`planFromReceipt`仅验证本地分类文件及范围，1054同时重验离线计划和实际执行。`Plan`/`fromReceipt`的目标预检接口与CLI离线计划入口用途不同，不能互换。

## 已接入命令

```text
discover-index-member-catalog --output-directory PATH
plan-index-member-job --classification-receipt PATH --classification-sha256 SHA --industries 801011.SI,801207.SI --selection CURRENT --logical-date 2026-09-29
run-index-member-job --classification-receipt PATH --classification-sha256 SHA --industries 801011.SI,801207.SI --selection CURRENT --logical-date 2026-09-29
```

执行命令加`--resume-from RUN_ID`复用终结批次；已停止的协调任务恢复再加`--writer-stopped true`。未知写入的子任务使用`finish-index-member-child --run CHILD_ID --writer-stopped true`先核验，不能直接重放父批次。`selection`支持CURRENT/HISTORICAL/BOTH，默认同步模式为INCREMENTAL。

写入目标由`app.sync.index-member-table`配置；注册owner拒绝直接发布正式`index_member`，当前验收使用隔离表。分类发现会请求Tushare并保存本地证据，计划命令本身不取成员、不写QuestDB。执行未达验证条件以IncompleteCommandException返回未完成，不用成功退出码替代数据验收。

剩余：跨数据sync组合、typed-write组合、较早批次中断与未知子任务的处理，以及最终回归/逐项结果收敛。本轮没有验证CLI恢复未完成发布的命令路径；对应单行业恢复已在先前实际测试覆盖，CLI参数入口已接入。
