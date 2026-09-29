# F013 真实组合恢复复核

`local-F013-group-recovery` 显式启用 `QUESTDB_WRITE_LIVE`，真实测试 1 项通过。两个测试专用 job 别名都复用已验收的 stock_basic 适配器；没有将其注册为新的业务数据集。

首项真实获取 `000001.SZ` 并写入隔离表；第二项在来源请求前注入失败，父状态 PARTIAL。重开 SQLite 账本后，原首项复用，只有第二项执行真实 `600000.SH` 请求与写入。新父状态 VERIFIED，原父仍为 PARTIAL；子任务均使用单数据执行器的完整值/WAL 验证。成功后清理自有隔离表。

- 实际数据与父子状态：[group-readback.json](7091a7caffe94730958205b0c2481b67/group-readback.json)。
- 独立逐字段核对：[independent-values.json](7091a7caffe94730958205b0c2481b67/independent-values.json)。`tools/verify_group_recovery_evidence.py` 对照两条源响应及实际 SELECT 输出，7 字段、2 行一致，无缺失或重复。
- `local-F013-full-review`：127 项，111 通过，16 项未启用外部条件而跳过，0 失败。上述真实组合验证单独运行，不包含在这个未启用外部测试的汇总中。
- 已补上正式子任务服务对父组持久化取消的观察，后续来源页及写入边界沿用单数据 runner 的取消检查。

尚待解决：当前组合恢复直接引用旧 VERIFIED 子任务，没有在生产恢复路径重新读取其当前 QuestDB 值。真实测试输出进行了独立全值比对，但不能替代生产恢复路径的复核。因此 F013 仍为 running，不登记整体完成。还需验证目标内容漂移时拒绝复用，并保留该次复核证据。
