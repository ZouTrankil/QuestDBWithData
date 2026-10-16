# 独立实时 Level2 应用

This process directly consumes the existing vendor MQTT feed and runs no trading actions. It uses three topics per symbol: `trans/<code>`, `order/<code>`, and `market/<code>`. The current limit is 16 SH/SZ symbols and 48 topics. The provider requires client ID to equal username and allows one terminal per account. Stop the previous collector through its runtime control before taking over that account.

Configure `l2.username` and `l2.password` in the `l2` profile of the shared `config/application.yml`; the collector automatically selects this profile and inherits the shared QuestDB endpoint. See [configuration usage](../../docs/configuration.md). Existing `var/l2-live/credentials.env` files are still accepted as literal overrides, followed by process environment variables. The collector does not print credentials.

```powershell
./deploy/l2-live/run.ps1 -Action plan
./deploy/l2-live/run.ps1 -Action start
./deploy/l2-live/run.ps1 -Action status
./deploy/l2-live/run.ps1 -Action stop
```

`start` 构建独立的 `l2-live-app` 并启动 `session`。该模块只依赖配置/日志公共库，不再加载数据 API、主策略、PGWire、Parquet 或离线计算组件。主类和原始日志路径保持兼容。每次启动使用独立 argfile 与 `var/l2-live/runtime/<id>/lib` 下的固定 JAR 快照，manifest 保存 SHA256，后续构建不替换正在运行的 classpath。已构建过可加 `-NoBuild`。

`session` 每次仅运行当天，先通过 QuestDB HTTP 只读查询 `exchange_calendar`（默认交易所 `SSE`）。`is_open=0` 以 `non_trading_day` 正常结束；缺失、重复、非法标记和查询失败均报错退出，不接触 MQTT。真实交易日会在上海时间 09:00 前等待，到 15:05 断开、排空可处理消息并结束；午间保持连接。时区、交易所和启停时间配置于 `l2.session`。已过收盘时间的补触发直接结束，不跨天采集。

缺少凭据时进入 `awaiting_credentials`，每秒重新加载配置；到收盘仍缺少凭据也会结束。启动成功不等于订阅成功：检查 `collector.ready`、全部 topic 的 SUBACK、持续增长的接收数与验证写入数。手工 `run` 保留不检查日历的诊断语义；日常部署使用 `session`。

## 每日自动启动

先构建一次快照，再由操作系统每天 08:55 启动前台会话。机器时区应设为 `Asia/Shanghai`，应用按同一时区判断窗口。真实交易日由数据库判定，任务本身可以每日触发。

Windows（当前用户的任务计划程序）：

```powershell
./gradlew.bat writeL2LiveRuntime --offline
./deploy/l2-live/register-task.ps1
```

任务直接托管前台进程，禁止并发实例，错过触发后补启动，失败最多重试 3 次；每日不重新编译。当前用户需处于登录状态。更新代码后重新构建快照，下一次触发使用新版本。

macOS / Linux 的前台入口：

```sh
./gradlew writeL2LiveRuntime --offline
./deploy/l2-live/run.sh plan --symbols 563360
./deploy/l2-live/run.sh start --wait-credentials true
./deploy/l2-live/run.sh status
./deploy/l2-live/run.sh stop
```

macOS 提供 `com.zoutrankil.l2-live.plist.example`：将其中 `/ABSOLUTE/QuestDBWithData` 全部换成实际项目路径，保存到 `~/Library/LaunchAgents/com.zoutrankil.l2-live.plist`，执行 `launchctl bootstrap gui/$(id -u) <plist绝对路径>`。它每天 08:55 触发；登录用户和机器需可运行任务，不会自动唤醒关机机器。Linux 可用 cron 的 `55 8 * * * /实际路径/deploy/l2-live/run.sh start --wait-credentials true`，时区同上。

本次迁移提供并验证应用与部署文件，不自动注册系统任务或连接真实供应商。主策略通过 `./gradlew mainStrategyRun` 独立启动，管理 API 默认端口 8081；旧 Web 进程应先停掉，避免旧调度器继续持有共享账本。

Default symbols come from unexpired `force_symbols` in the existing Python override file, filtered by allow/block policy. Allow entries alone do not create subscriptions. An explicit symbol list is also supported:

```powershell
./deploy/l2-live/run.ps1 -Action start -Symbols '563360.SH'
```

A running process freezes its startup symbol list. Apply a changed subscription scope by graceful stop and start. No expired override is extended automatically. Private credentials are necessary to activate a real subscription; this implementation does not purchase a provider account.

原始字节与精确接收时间先提交到 `var/l2-live/raw-journal.sqlite3`，再执行标准化。标准化事件、分钟观察和累加器检查点冻结在 `prepared_batch` 的 JSON payload 中；本地读回并校验 SHA256 后完成批次。当前使用本地 SQLite 存储，不再向 QuestDB 的两张旧实时结果表写入；QuestDB HTTP 在 `session` 中只用于读取交易日历。原始数据上限和整个 SQLite 页上限均为 8 GiB，不自动删除历史或预留磁盘空间。

Within the admitted 1 MiB raw-envelope bound, protocol frames above 64 KiB are audited as `oversized_wire_payload`: their exact bytes remain in the journal/export, while QuestDB audit rows retain the capture ID/hash instead of inline large Base64. The raw envelope records whether all SUBACKs were confirmed when the message arrived. Unconfirmed subscription, scope/QoS rejection and `aggregation_overflow` produce invalid audit events and never enter minute aggregation.

分钟结果保留十档深度，使用 UTC 盘中时间、解析器版本和本地 capture identity，表示处理时可见的局部观察。现有外部读取器需要显式接入本地 SQLite 批次结果；独立进程拆分不代表主策略已经消费这份实时输出。

MQTT QoS 0 cannot recover messages never received during a disconnection. Local raw replay recovers committed captures only. Vendor sequence scope remains unverified; the implementation does not assume per-symbol continuous sequence numbers or hash away identical trade payloads.

```powershell
./deploy/l2-live/run.ps1 -Action replay
./deploy/l2-live/run.ps1 -Action export
```

Replay requires the live process to be stopped and publishes pending frozen captures through the same verification path. Export writes an immutable raw JSONL snapshot and reports its hash. Stop uses `stop.request` and waits for graceful draining; it does not kill an unrelated process.

原始报文、解析事件、分钟观察和累计检查点均保存在 `var/l2-live/raw-journal.sqlite3`。`prepared_batch.payload` 保存带 SHA256 的完整不可变批次；本地验证后原子推进完成状态，重启复用已冻结批次。实时采集不再创建或写入 QuestDB 事件/分钟表；QuestDB 仅用于读取交易日历。SQLite 仍受原有 8 GiB 配额限制。
