# 本地回测日表盘后更新

`run.ps1` 使用现有 Java owner，从 Tushare 依次更新正式表 `daily`、
`daily_basic`、`stk_factor`、`stk_limit`、`stk_suspend`、`stk_st_daily`，
随后由 `BacktestDailyMaterializationJobService` 更新 `backtest_daily` 和原生
物化视图 `v_backtest_daily`。本任务可独立运行，不依赖主策略的 Level2、情绪、
ETF 持仓或策略发布阶段；它的完成仅表示上述日表和回测视图已核验。

目标固定为本机 `127.0.0.1` 的正式 QuestDB 服务，使用原有
`var/sync-ledger.sqlite3` 和 `var/sync-evidence`，不绕过 owner 写表。
Tushare 使用[官方 REST 地址](https://tushare.pro/document/1?doc_id=40)
`http://api.tushare.pro`；本次恢复期间配置中的 HTTPS 地址出现了 502 和读取超时。
凭证沿用应用配置，不写入任务参数或日志。

## 安装与定时运行

```powershell
# 在管理员 PowerShell 中安装并注册无需登录的开机/盘后任务。
.\deploy\backtest-post-close\install.ps1 -RegisterTask

# 已有固定运行时，仅更改任务配置时使用此命令，不重新编译应用。
.\deploy\backtest-post-close\register-task.ps1
```

安装脚本使用仓库 Java 24，离线构建完整应用，把 jar 复制到独立目录并记录 SHA-256。
运行时检查固定 jar 的摘要，每天执行不依赖 Gradle。重新部署时再次执行安装命令。
`-Unattended` 保留为兼容参数；注册任务默认使用 S4U，不保存账户密码。
Windows 要求管理员权限才能创建开机触发器。脚本会保留修改前的任务 XML 和注册回执
`var/backtest-post-close/task-registration.json`，失败时明确报错。

任务名为 `QuestDB-Backtest-PostClose`，时区为北京时间：

- 每天 20:35 启动，之后每 15 分钟重试，窗口持续 3 小时 15 分钟。
- 开机后延迟两分钟检查补数，无需登录账户。交易日历决定目标日期，20:30 前只取前一自然日及以前的完整交易日。
- 使用当前账户的 S4U 后台会话，账户注销后也能运行；电脑需要开机。
- 开始时等待本机 QuestDB 和 Tushare 网络连通，最长十分钟；失败后每隔十五分钟重试，最多三次。
- 启用 `StartWhenAvailable`，错过触发时间后允许补跑。长时间关机的缺口按小批次连续补齐，任务不设强制终止时限。

重复触发有文件锁和原有数据集锁保护。每次都检查受管区间内六个稠密表/视图的实际交易日期，
即使最新日期相同，也会发现中间缺失的交易日。没有缺日且八个正式对象的身份及
WAL 事务均未变化时，才对已核验的目标返回 `already_verified`。不同日期会重新获取修订窗口。
遇到不确定写入时保留原始 ledger 和证据，停止执行，不能把未确认的写入当成成功。

## 手工检查和补数

```powershell
# 只输出计划，不访问 Tushare，也不写 QuestDB。
.\deploy\backtest-post-close\run.ps1 -Through 2026-10-15 -From 2026-10-12 -PlanOnly

# 执行截至指定日期的补数。
.\deploy\backtest-post-close\run.ps1 -Through 2026-10-15 -From 2026-10-12

# 日常运行：按完成时间、交易日历和实际缺失交易日自动选取窗口。
.\deploy\backtest-post-close\run.ps1

# 无数据库写入的补数计划回归测试。
.\deploy\backtest-post-close\test-planning.ps1
```

`coverage-anchor.json` 固定受管区间起点，本机从已验收的 2026-10-12 开始。
每天比较正式 `exchange_calendar` 的开市日期与 `daily`、`daily_basic`、`stk_factor`、
`stk_limit`、`backtest_daily`、`v_backtest_daily` 的实际日期，记录 `missingByTable`。
补数从最早缺日开始，同时保留目标日前两自然日的修订窗口；不再有 32 天总窗口上限。
日历不足时通过现有 Java `run-exchange-calendar` owner 获取来源日历，不能用工作日猜测。
采集按至多五个自然日切分；
`stk_factor` 的十分钟 owner 时限较短，始终按单日执行。空停牌日由原有 owner
出具来源证据，不用稀疏表的最大日期推断覆盖。

最终验收包括交易日期完整、每个日期股票键唯一、基础表和视图分别与来源 SQL 的
全部 13 个字段双向 `EXCEPT ALL` 相等，以及审计前后 WAL 状态不变。
停牌股票使用前一次收盘价的 ASOF 规则与 Java materialization owner 相同。
`VERIFIED` 表示现有取数口径一致；`dailyUniverseComplete` 和 `dailyUniverseMissing`
另外披露日线存在、视图尚未覆盖的股票键。新股上市首日可能只有日线、没有来源因子行，
这种情况保留缺口，不伪造技术指标，也不据此宣称全市场覆盖完整。后续日常修订窗口会重读来源。

检查运行结果：

```powershell
Get-Content var\backtest-post-close\status.json
Get-Content var\backtest-post-close\last-check.json
Get-ScheduledTask -TaskName QuestDB-Backtest-PostClose | Select-Object Principal,Triggers,Settings
Get-ScheduledTaskInfo -TaskName QuestDB-Backtest-PostClose
```

`last-check.json` 每次触发都更新，记录运行账户、会话 ID、是否交互会话、QuestDB/网络/Java
就绪状态、缺日计划及错误。用 `Start-ScheduledTask -TaskName QuestDB-Backtest-PostClose`
可以验证真实 S4U 后台运行；成功应看到任务返回码 0、`userInteractive=false`，
以及 `ALREADY_VERIFIED` 或 `VERIFIED`。开机触发器的配置可直接从任务读回验证。
2026-10-16 08:03 的实际后台运行已满足这些条件，Session ID 为 0，
截至 2026-10-15 没有缺失交易日。注册配置、运行回执及逐日行数见
[`verification.json`](../../artifacts/backtest-post-close-unattended/20261016/verification.json)。

每次实际执行保留独立 `var/backtest-post-close/runs/<日期>-<标识>/`，包含计划、
每个 owner 的日志、退出状态和最终验收结果；每次检查也保留独立 `checks/<标识>/check.json`。
失败时先看 `error` 和该阶段日志。`status.json` 保留最近的数据执行结果，单次检查失败另见 `last-check.json`。

用户查询可增加按日行数检查：

```sql
SELECT trade_date, count() AS rows
FROM v_backtest_daily
WHERE trade_date > '2026-09-30' AND trade_date < '2026-10-16'
GROUP BY trade_date
ORDER BY trade_date DESC;
```
