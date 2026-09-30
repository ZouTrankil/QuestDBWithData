# `post_close` 有界恢复触发

独立 Quartz schedule 增加两个初始暂停的 recovery trigger：`post_close_recovery` 在工作日 19:00–23:45 每 15 分钟触发，`post_close_recovery_final` 在 23:55 触发。两者均使用 `Asia/Shanghai` 和 `DO_NOTHING` misfire 策略；触发器安装不会自动启用 scheduler 或恢复暂停状态。

每次触发只查找该逻辑日唯一的未被后续 revision 替代的 `post_close` 实例，并只考虑 `BLOCKED`、`PARTIAL`、`WAITING_SOURCE`、`WAITING_UPSTREAM`。没有实例、revision 分支不唯一、日历/时区不符或任务已成功时均跳过。`RUNNING`、`IN_DOUBT`、`FAILED` 不自动重放，需要先通过 reconciliation 判断原执行与物理副作用。

SQLite 的 `post_close_recovery_attempt` 与 `post_close_recovery_trigger` 表在启动任何重试前原子记录 attempt、最早下次时刻和 Quartz fire ID。每个业务实例每晚最多 21 次；常规冷却为 15 分钟，23:45 到 23:55 的末次冷却为 10 分钟。相同 fire ID 不会重复 claim。新的恢复 request ID 保持原 `post_close` business instance identity，因此复用统一 `LaunchService` 和 source fan-out；每个新触发能重试失败的只读 source probe。已写入来源内容变化仍需显式来源 revision，未知状态不会自动继续。

超过窗口、跨过业务午夜的 misfire、错误日历版本、闭市日期和达到次数上限都只写审计事件，不发起 Batch/source 请求。schedule 仍须在隔离环境中单独启用并分别恢复两个 trigger；服务默认不启动 Quartz，生产执行能力仍关闭。此能力目前只覆盖 `post_close` 的有界重触发，尚未实现完整 N1/N2、主策略缺口恢复或历史回补执行队列。
