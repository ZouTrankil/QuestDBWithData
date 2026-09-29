# D005 单行业写入任务恢复

局部验收通过，D005 仍 running，人工 pending_review。

恢复前要求原写入者已经停止并持有该 run 的整表锁，按账本恢复完整请求和行业分类 SHA；逐一验证来源完整回执、Y/N 子响应 SHA、请求范围及原始字段，重新构造期间数据并重算合并。原目标身份、端点、全部旧行、替换行和备份均与冻结证据核对。拒绝回执漂移或冲突布局，不能凭已有 completion 文件直接完成账本。

| 测试 | 注入点 | 实际结果 |
|---|---|---|
| local-D005-run-recovery-1038，1通过/0跳过 | 已发布、尚未写完成回执 | 三级 IN_DOUBT；停止确认后实际回读7条一致，0新来源请求、目标WAL事务未变化，三级VERIFIED，释放锁 |
| local-D005-stage-recovery-1041，2通过/0跳过：完整stage | stage写完、发布前 | 复用原stage；0新来源请求、0新增行插入；实际发布7条及备份核对通过 |
| 同上：未写完stage | CREATE成功、首批插入前 | 保留0行stage，新建独立stage写7条；0新来源请求；实际发布及三级账本通过 |

证据：

- [发布后恢复](run-recovery-7e34ec592a03483c95864e3161d705c5/recovery-readback.json)
- [完整stage复用](stage-recovery-3685bf28f0204bd496c742eba32bf58a/recovery-readback.json)
- [未完成stage重建](stage-recovery-6f943679495745f4a63eb6e17d19660c/recovery-readback.json)

三个场景均用真实 Y/N 成员响应，显式停止确认缺失及来源回执改动均被拒绝；原表不因拒绝而变化。测试注入 IOException，不是操作系统杀进程。成功现场的隔离表清理，来源、SQL回读、账本及恢复凭据保留。

1040 使用 TRUNCATE 模拟未完成stage时，WAL在20秒内未就绪，第二场景失败，现场保留；不能算恢复通过，未降低WAL检查。1041在实际stage写入函数的CREATE后注入异常，覆盖自然中断点。TRUNCATE后的WAL行为根因未查明，本任务不使用该操作修复写入。

仍待：无变化/空来源任务中断后的恢复、失败后安全重跑、多行业串行及复用已验行业、CLI注册、sync/typed-write组合。未写完stage本次为0/7行中断，尚未验证首批部分非零行中断；不把此案例描述为任意写入阶段全部覆盖。
