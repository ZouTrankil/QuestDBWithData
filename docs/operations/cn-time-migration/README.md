# CN 时间迁移执行状态

任务 07 尚未完成。当前只修正了 `index_daily_market` 正式表契约与旧隔离布局混用的问题；没有更改正式数据库、主策略 schema 或正式别名。

本次只读复查确认正式表指定列为 `trade_date`，YEAR/WAL，去重键 `(ts_code,trade_date)`。`IndexDailyMarketDataset.DEFINITION` 现在是 v2；`legacyIsolatedDefinition` 明确提供 v1 的 `timestamp` 布局，且拒绝正式表名。现有 D019 writer/read repository 与 adapter 仍显式绑定 v1，旧冻结请求、Job 版本和恢复流程未变。不能把正式契约 v2 当作生产作业已接管。

## 逐文件改动

- `IndexDailyMarketDataset`：正式 v2 与隔离 v1 分开。
- `IndexDailyMarketReadRepository`、`IndexDailyMarketWritePort`：明确使用旧隔离布局，保留原 SQL 和物理目标。
- `IndexDailyMarketSyncAdapter`：校验 v1 的数据集版本，不被正式契约升级改变。
- `IndexDailyMarketLayoutTest`：验证两套指定列、完整键、版本和日期语义，拒绝正式表套用隔离契约。

[105 个原登记对象状态](对象状态.csv)沿用原审计范围，三项已退役对象仍保留审计记录。其余对象不因名称未改而自动算完成。该表不是新生产字典。

## 未完成项

六个交易日别名、两项公告日、三项利率观测日、主策略 `instrument_code` 及单位矩阵尚未贯通。物理迁移、MV 重建和正式输入包切换都未执行。待核三项继续保留原名。

正式切换需要定位运行中的唯一写入方、原账本/锁及消费者部署；还要固定批准方案、训练文件、冻结输入和独立参考版本，才能核对新旧分数、排名与候选。测试资源、临时验收产物和本地空账本不能替代正式证据。拿不到这些材料时，不改写原批准摘要，不把 SQL 改名或编译通过当作迁移完成。

相关回归涵盖正式/隔离布局、D019 来源与恢复、目标完整键值、读取装配和分组写入；未执行正式写入或物理迁移。
