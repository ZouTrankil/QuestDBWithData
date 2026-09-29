# D005 发布阶段恢复验收

状态：局部通过，D005 仍为 running，人工 pending_review。

直接本地执行 `local-D005-publication-1033`，1 项实际 QuestDB 测试通过。证据：
[publication-readback.json](publication-33402ddc32bb432486c24fdffbcf86ca/publication-readback.json)。

每个场景先用已保存的真实来源 4 条当前成员建立独立目标，再合并同一行业的 Y/N 共 7 条来源，新增 3 个历史期间。验证正常发布，以及 PREPARED、OLD_MOVED、PUBLISHED 三个持久阶段注入 Error 后重新打开发布器恢复。每次均实际 SELECT 全部 14 列，7 条目标与期望完全一致，原 4 条原值保留，备份快照与发布前完全一致。重复恢复结果一致；发布前取消不改表、不创建发布意图；未确认写入者停止时拒绝恢复。

发布绑定整表锁、原目标身份、端点、原表和替换表身份及完整内容指纹；两个 RENAME 不是跨表原子事务。无法匹配已知布局时拒绝自动改动。测试的 Error 注入不是操作系统杀进程。

1030/1031 的测试准备阶段采用 DROP 后立刻复用表名，遇到 WAL 未就绪，等待 20 秒也未恢复；失败现场保留，不能算通过。1032 编译时发现准备阶段新增的等待代码引用旧变量，已修正。1033 改为直接使用已验证的独立 seed 表作为目标，不复用刚删除的名字；正常发布及三个中断恢复均通过。未据此断言 QuestDB 的名称复用故障根因已修复。

本测试未改正式 index_member，也未重新调用 Tushare。正式 owner 的 run/attempt/slice、逐行业 checkpoint、分类 SHA 绑定、CLI、sync/写入组合及其恢复尚待完成；发布日志通过不等于正式任务验收。
