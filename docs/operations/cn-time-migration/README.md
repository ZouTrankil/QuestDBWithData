# CN 时间迁移执行状态

任务 07 尚未完成。已修正 `index_daily_market` 正式/隔离契约混用，并完成六组新布局小样本演练；没有更改正式表、主策略 schema 或正式别名。

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

## 六组隔离演练

新增 `CnTimeColumnRehearsal`，通过已有有限维护入口运行，不启动来源同步或 Batch：

```sh
./gradlew questdbMaintenance --args='rehearse-cn-time-columns --output var/cn-time-rehearsal-新编号.json'
```

输出必须是新文件，不能覆盖原证据。任一对象失败或清理未完成时返回非零退出码；逐项结果已落盘，不能仅看进程退出判断数据完成。

当前服务端拒绝直接改名指定时间列，[直接改名失败证据](指定列直接改名被拒绝.json)已保存。改用新物理布局后，[六组演练](新布局隔离演练.json)全部通过，每组只复制两行，比较完整样本键和值、类型、指定列、分区、去重及有效索引，检查 WAL 稳定并保留旧副本验证。`etf_daily` 的存储类型仍为 `TIMESTAMP_NS`，时间值按存储整数比较，未经过 PGWire 显示时间的精度转换。

[演练后复查](演练后复核.json)确认没有残留隔离表，六张正式表的 ID 与原指定列未变，三张待退役表仍存在。Java guard 回归覆盖拒绝正式表、拒绝替换后的物理对象、纳秒类型变化、去重键丢失和新 DDL 映射。

样本不代表全量源覆盖；午夜日期样本不能独立验证非零纳秒历史值。未执行正式换名、MV 刷新、旧凭证迁移或真实模型比对。操作选择及边界见[设计决定](../../decisions/2026-10-20-CN指定日期列迁移.md)。

本地默认 `../back-monitor/artifacts/factor_research/l2/` 下的正式 recipe、批准摘要和计划库不存在；尚未取得实际位置。不能用临时测试产物替代这些原件。

最终定向回归 14 项通过，包含 5 项新 guard/DDL 测试；架构、模块边界与 CN 发行包检查通过。新增后全项目 1969 个 Java 文件的 import、排版和字面量检查通过。先前 4617 项离线检查针对格式提交；本次有限维护新增逻辑另由上述回归与真实隔离演练验证。

## 主策略内部身份已接入

`ScoreRow` 现在将 `instrumentCode` 与 `AggregationScope` 分开；`MARKET`、`ETF_UNIVERSE` 不能充当证券代码。`MainStrategyDataService`、股票/ETF 评分、来源准备、可变计算行及冻结都已使用这套角色。ETF 资料及持仓明确区分 ETF、成分证券、公告日和报告期末；`mkv` 仍为元，未将 `amount` 股数接进市值。

旧产物通过 `legacyV1Frames` 适配，仍使用原 v1 字段和摘要规则。源端物理列、正式产物 schema、Python 消费者和批准材料尚未升级；这部分仍未完成。实现取舍见[身份角色决定](../../decisions/2026-10-20-主策略内部身份角色.md)。

该批主策略模块 126 项通过，零跳过，含原有评分对照和新增身份保护；架构、边界及主策略发行包通过。原参考样本没有重写数值或预期结果，只显式适配原汇总标签。
