# ETF 行情中断恢复

适用于正式 `etf_daily` 的一次执行只有一个已提交日期切片、写入进程已退出、账本仍为 `IN_DOUBT` 的情况。多日期已有多个切片的恢复不在此入口范围内。

## 执行顺序

1. 保存原始 CLI 完整日志。确认原进程已经退出；观察超时不能作为退出依据。
2. 保留原账本、`var/sync-evidence/<run>/source` 和区间锁。
3. 设置正式目标并执行恢复：

   ```powershell
   $env:APP_SYNC_ETF_DAILY_TABLE = 'etf_daily'
   ./gradlew.bat :run --args='reconcile-etf-daily-job --run <原 run ID> --writer-log <原日志路径>'
   ```

4. 恢复返回 `PARTIAL` 后，从原请求续跑：

   ```powershell
   ./gradlew.bat :run --args='run-etf-daily-job --resume-from <原 run ID>'
   ```

恢复入口验证原始进程、冻结定义、物理表身份、源响应哈希和逐批摘要。已提交的每一行必须在正式表中存在且全部字段一致；随后只写同一冻结响应中尚缺的行。写入前保存恢复意图，完成后再验证整日数据。

整日切片转为 `VERIFIED`，父批次保持 `PARTIAL`。续跑重新获取源响应并校验该日期，源和目标仍一致时复用断点，再处理后续日期。恢复过程不把部分行数冒充整日完成，也不修改其他日期。

恢复进程中断后，先确认它已退出，再使用同一个原 run ID 和原日志重试。恢复意图用于拒绝仍存活的旧恢复进程；数据库已有且一致的行会被复用。

## 本地执行证据（2026-10-11）

- 原批次：`etf-daily-323aafe8-8059-407c-9132-266b6d652338`，10 月 8 日已提交 250 行。
- 恢复：复用 250 行，只补 1,902 行，整日验证 2,152 行。
- 续跑：`etf-daily-82072344-0022-4f0b-8858-d091b64f5e87`，`VERIFIED`；复用 2,152 行，两日共验证 4,316 行。
- 独立核对：`artifacts/local-factor-audit/20261011/etf-daily-october-complete-audit.json`，两日完整键及全部数值字段无差异，无重复、缺失或额外行，WAL 已应用。

本次发现读回 SQL 缺少独立日期范围，250 组完整键 OR 条件导致查询超时。`EtfDailyWritePort` 与 `EtfFactorWritePort` 已增加日期范围和证券过滤，同时保留完整键对约束。ETF 行情与技术因子批次的可见性等待上限为 3 分钟，仍须通过全部字段核验。
