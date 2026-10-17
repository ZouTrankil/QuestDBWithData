# ETF 行情写到一半怎么恢复

这条恢复入口只适用于：正式 `etf_daily` 的一次执行只有一个已提交的日期切片、原写入进程已经退出，而运行记录仍是 `IN_DOUBT`。多日期已经提交多个切片时，不套用这条流程。

## 操作顺序

1. 保存原 CLI 完整日志，确认原进程已经退出。等超时不算退出。
2. 保留原 SQLite 运行记录、`var/sync-evidence/<run>/source` 和区间锁。
3. 明确目标是正式表，使用原运行编号和原日志恢复：

   ```sh
   APP_SYNC_ETF_DAILY_TABLE=etf_daily ./gradlew run --args='reconcile-etf-daily-job --run RUN_ID --writer-log LOG_PATH'
   ```

4. 返回 `PARTIAL` 后，继续原请求：

   ```sh
   ./gradlew run --args='run-etf-daily-job --resume-from RUN_ID'
   ```

把 `RUN_ID` 和 `LOG_PATH` 换成实际值，不新建一套来源或锁。

## 恢复时会检查什么

先验证原进程、请求、物理表身份、来源文件摘要和逐批摘要。已经提交的行必须全部存在且字段一致；只补同一份原始响应中已证明缺失的行，补完再核对整日数据。

当天切片通过后变成 `VERIFIED`，父批次仍是 `PARTIAL`。续跑重新获取来源并核对该日，仍一致才复用断点，再处理后面的日期。

恢复进程再次中断时，先确认它退出，再用同一个原运行编号和原日志重试。不要手动解锁或另开写入；程序会复用已存在且一致的行，并拒绝仍存活的旧恢复进程。
