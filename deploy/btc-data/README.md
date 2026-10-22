# BTC 日更与备份

日更由 `tools/btc_daily.py` 编排，正式采集只调用 Java 原入口。工作目录、原队列、账本和配置共用，不复制第二套 writer。

## 构建和运行

需要 JDK 24、`uv` 和根配置中的实际 QuestDB 连接。

```bash
./gradlew :markets:btc:data-app:installDist
uv run --script tools/btc_daily.py run
# 固定一次运行范围，截止日期包含当天，且必须已经结束：
uv run --script tools/btc_daily.py run --through 2026-10-20
```

有限 `run` 每轮固定截止日，默认两轮来源尝试。定时入口调用 `scheduled`：首次 UTC 01:00、失败每三小时续跑、同一 UTC 日最多八次；重启继续按当日报告计数，未完成意图也占一次尝试。成功后当日不重跑。Java 保留原恢复协议，来源错误不等于未决写入，未决不重发或解锁。

默认每天探测最近两个完整月的修订，最多再每周轮查一个旧月，共不超过十八个文件。旧月游标在完整探测成功后推进；发现变化持久登记跨期待办，不自动应用；普通 ZIP 修订只有原 Java 成功证据通过才解除，跨来源重叠冲突继续显式复核。新的 `UNCHANGED` 或删除报告不能解除原阻断。历史较长时整轮轮查需要多周，不能声称每天检查了全部历史。

运行结果写 `app.crypto-data.root` 下的 `daily-runs/<run_id>.json`；`latest.json` 保存最近一次结果。状态为 `PARTIAL_AVAILABLE` 时存在已明确登记的质量问题；`REQUIRES_ATTENTION` 或 `UNVERIFIED` 退出码为 2。查看 `error`、`queue`、`revisionReports` 与批次 ID，不只看进程退出。

日更新鲜度门槛检查每个数据集的末端日期，不能替代中间缺口或事件完整性核验。来源延迟、未决、修订待核对及认证不足时不切换研究指针。已有部分版本可供显式探索，旧固定批次继续保留。

## macOS 定时任务

```bash
uv run --script deploy/btc-data/install.py
launchctl print gui/$(id -u)/com.zoutrankil.btc-data
# 手动触发同一定时入口，仍遵守日更锁：
launchctl kickstart gui/$(id -u)/com.zoutrankil.btc-data
# 停止定时部署，数据和原锁保留：
uv run --script deploy/btc-data/install.py --remove
```

安装到当前用户的 `~/Library/LaunchAgents/com.zoutrankil.btc-data.plist`，首次 UTC 01:00 换算为本机 09:00；失败每三小时续跑，UTC 当日最多八次，成功后当天停止。排程由根 `schedule-start-utc-hour`、`schedule-retry-hours`、`schedule-max-attempts` 决定。排程配置、系统时区或夏令时规则改变后重新安装；仍保留原队列和当日报告计数。用户需要保持登录，机器休眠会延后执行；这不是远端常驻部署。

启动器日志在根 `build/btc-scheduler/`。业务日志按根配置保留，默认 30 天；原文、账本和固定研究批次不按日志策略删除。配置的磁盘门槛在运行前检查，下载还受原文件及来源预算限制；不是文件系统硬配额。

## 停止与恢复

创建 `var/crypto-market-data/binance-vision/stop.request` 可要求归档作业在安全边界停止；日更入口看到它会拒绝启动。停止请求不等于回滚，移除前检查当前运行和未决状态。

```bash
./gradlew :markets:btc:data-app:run --args='status-binance'
./gradlew :markets:btc:data-app:run --args='reconcile-binance --run RUN_ID'
# 只有确认原运行与缺失键、且原恢复协议允许时才补缺：
./gradlew :markets:btc:data-app:run --args='reconcile-binance --run RUN_ID --repair-missing'
```

归档组、同键修订组和冻结资金费率 API 响应均有原运行恢复入口。API 恢复先重算原响应、比较全部字段、核对原队列；不重新请求 API。同键修订恢复同步收拢原修订记录。

底层部分发送恢复仍受原协议限制，当前为首切片、总量不超过一万行；超出范围、原有值冲突、缺失原文或目标被重建时保留原锁。修订中途留下旧值与新值混合时不能当作缺键补发，不保证自动恢复。全部发送后的只读核对也必须有原成功证据。

## 一致性备份和隔离核对

```bash
uv run --script tools/btc_daily.py backup --output var/btc-backups/YYYY-MM-DD-btc
uv run --script tools/btc_daily.py verify-backup --output var/btc-backups/YYYY-MM-DD-btc
```

备份目录必须是原数据根之外的新目录。工具取得原采集、发布和研究文件锁，拒绝活动 writer；SQLite 使用 backup API 并执行 `integrity_check`，逐文件保存 SHA-256，再在隔离副本检查引用。

备份包含原文、CHECKSUM、API 响应、队列、账本、prepared、回执及研究版本。临时副本先核验修订注册表引用及 API 原响应，再持久化文件与目录后发布；坏副本不命名为完成。原件中的绝对路径不改写；隔离核对按原根映射到副本。备份是只读恢复材料，不能直接换根目录启动第二个正式 writer；真正灾难切换还须原物理表核对和明确的单 writer 切换。

连续七个日更周期的观察另行记录。一次手动执行、单元测试或安装成功都不能代替七天运行结果。

## 七个真实周期的验收

```bash
uv run --script tools/btc_daily.py observations
```

结果在原根目录的 `daily-observations.json`，日更结束时也自动刷新。必须满足最近七个不同 UTC 运行日的前日截止、正式认证、固定文件完整及当前目标覆盖。同日重复触发和历史补跑不能凑天数，旧协议或失败报告不算成功。

本轮已经取得第一个合格周期：10 月 21 日运行补齐到 10 月 20 日，定时进程退出码 0。同日再次触发返回 `ALREADY_AVAILABLE`，正式运行和日更报告数量不增加。仍须后续六个真实运行日，当前不宣称七日验收完成。
