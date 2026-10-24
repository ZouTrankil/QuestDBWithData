# 同花顺资金流的正式目标与逐日核验

`moneyflow_ths` 已支持显式绑定同名正式表，只放行明确日期范围的 `BACKFILL`。默认 writer 仍为 `java_d025_moneyflow_ths_acceptance`，本轮没有正式写入或启用日批。

```sh
# 只读核查一天的完整来源和旧目标，不创建同步运行账本。
APP_SYNC_MONEYFLOW_THS_WRITE_TABLE=moneyflow_ths \
./gradlew run --args='check-moneyflow-ths-source --date 2026-09-18 --logical-date 2026-10-21'
```

日期改成实际要查的已完成开市日。来源按 SSE 日历逐日请求 `moneyflow_ths(trade_date=YYYYMMDD)`，每次必须取得完整非空响应。达到6000行上限、重复完整键、字段异常或日期越界均拒绝完整；原始响应先归档。

## 配置和兼容

- writer 使用 `app.sync.moneyflow-ths-write-table`，可用 `APP_SYNC_MONEYFLOW_THS_WRITE_TABLE` 覆盖。正式只认精确同名 `moneyflow_ths`，原隔离前缀仍可用。
- 原 `app.sync.moneyflow-ths-table` 继续作为 writer 的兼容回退，并控制公开读取目标。默认读取原正式表、默认写入隔离表的行为保持。若部署曾覆盖旧配置，切换时须明确核对读写两端。
- 原 `plan-moneyflow-ths-job`、`run-moneyflow-ths-job`、`--resume-from`、作业版本1及数据版本1不变。隔离建表工具仍拒绝正式名，没有新增正式 DDL 或历史迁移。
- v1定义中的隔离说明保留以维持原冻结指纹；当前正式准入规则以执行入口和本说明为准。原隔离冻结请求和身份已与 `HEAD` 对比一致。

正式执行例：

```sh
APP_SYNC_MONEYFLOW_THS_WRITE_TABLE=moneyflow_ths \
./gradlew run --args='plan-moneyflow-ths-job --mode BACKFILL --from 2026-09-18 --to 2026-09-18 --logical-date 2026-10-21'
```

正式目标拒绝 `INCREMENTAL` 和 `RECONCILE`，也不能用组执行或恢复绕过。旧行和最大日期不提供历史来源覆盖证据，不从中建立增量检查点。计划仍冻结物理身份和原范围，发送前复核；`trade_date` 保持业务日期的 UTC 零点载体，金额及比例保持原来源单位。

## 完整性和恢复

每个交易日先收齐来源并检查旧日范围；来源缺少任何旧键时，在发送前拒绝，不静默留下旧行冒充完整。随后按原批次发送、WAL及全部字段回读，最后再对整日完整键值核验。

整日回读失败时，保存 `whole-date-in-doubt-日期.json`，运行及区间锁保持 `IN_DOUBT`。不能因为逐批核验通过就释放锁或自动重发。后续日期的来源失败、且之前日期已完整核验时，保留 `PARTIAL`，沿原冻结请求恢复；已验证日期按原摘要复用。

## 本轮结果

2026-09-18 的真实来源5219行，原正式表该日5209行，原键均出现在来源中。只读核查未认证原表全部值或后续更新，也未修改正式数据。

私有 QuestDB 用全部5219行冻结来源验证首次发布、修订、公开全字段读取、范围外旧行保留，以及第二日来源失败后原请求恢复。修订、前一日来源、日历和范围外旧行是夹具，不算真实多日验收。单元测试另验证整日不符后运行及原锁保持未决。

详见[阶段证据](audits/2026-10-21-cn-moneyflow-ths.json)。正式唯一 writer、原运行和锁、补数范围、周期调度及消费者仍待验收。
