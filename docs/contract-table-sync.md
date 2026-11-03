# 有限范围的资料与公告同步

以下 16 张表已接 `contract_table_owner`，任务是 `data.<dataset>`。只写原同名表，使用原同步账本、整表锁、逐字段回读和未决保护，不执行 DDL。另有 `balance` 复用[财务执行器](financial-sync.md)。这批没有正式写入，也没有启用自动更新。

| 表 | `--code` | `--from` 和 `--to`（两者必须相同） |
| --- | --- | --- |
| `broker_recommend` | `ALL` | 一个月的月初，来源参数为 YYYYMM |
| `stk_alert` | 一只明确股票 | 提示生效起始日；先归档该股全部提示历史，再筛选起始日 |
| `fut_holding` | CFFEX 具体合约符号，如 `IM2612` | 来源交易日；不接受 IF 品种作为具体合约 |
| `cyq_chips` | 一只明确股票 | 来源筹码分布日期 |
| `sge_daily` | `ALL` | 来源单日全部现货合约；含前一夜盘，不套股票日历 |
| `fut_basic` | `CFFEX`、`DCE`、`CZCE`、`SHFE`、`INE`、`GFEX` 中一个 | 当前上海业务日；不提供历史挂牌快照 |
| `disclosure_date` | `ALL` | 一个已结束的季度末；来源按 `end_date` 查询 |
| `fina_mainbz` | 一只明确股票 | 一个已结束的季度末；P/D 分别收齐 |
| `share_float`、`dividend`、`fina_audit`、`stk_holdernumber`、`stk_holdertrade` | 一只明确股票 | 公告日；审计按真实报告披露，不推断每日应有数据 |
| `pledge_stat` | 一只明确股票 | 来源统计截止日，不按股票日历推断期次 |
| `stk_shock`、`stk_high_shock` | 一只明确股票 | 公告日，来源字段仍为 `trade_date` |

```sh
./gradlew run --args='plan-contract-table --dataset fut_basic --code CFFEX --from 2026-10-21 --to 2026-10-21 --logical-date 2026-10-21'

# 只查来源、目标结构和旧键，不写 QuestDB 或正式运行账本。
./gradlew run --args='check-contract-table-source --dataset disclosure_date --code ALL --from 2026-09-30 --to 2026-09-30 --logical-date 2026-10-21'

# 原 writer、锁、账本及消费者归属核对后，才用明确范围执行。
./gradlew run --args='run-contract-table --dataset pledge_stat --code 601091.SH --from 2026-09-18 --to 2026-09-18 --logical-date 2026-10-21 --mode RECONCILE'

./gradlew run --args='resume-contract-table --dataset pledge_stat --run 原运行编号'
```

`fut_basic` 示例日期需换成实际运行日；观察跨日后拒绝用当前目录认证旧请求。其他恢复沿用原请求和物理目标，未决写入不自动重发或解锁。模式支持 `INCREMENTAL`、`BACKFILL`、`RECONCILE`，都不靠最大日期自动接续。

## 写入和回读规则

- 先收齐整个请求，再保存写入意图和发送。无分页来源达到容量上限就拒绝完整；主营按原分页和分类规则执行。
- `timestamp=1970-01-01T00:00:00Z` 仍是合约目录的技术哨兵。`update_time` 使用请求里冻结的真实 UTC 开始时刻，精度为微秒；不能拿哨兵判断目录新鲜度。
- 公告日、报告期和解禁日分别保留。旧 UTC 午夜日期载体不加 8 小时；质押次数和股东人数用 LONG，不经过 double。
- 正式表固定原分区和 WAL；`fut_holding` 为 MONTH，其他为 YEAR。推荐名单、重点提示和黄金现货须先显式修复旧键，owner 不自动改表。主营 `bz_code` 的正式列是 VARCHAR，旧 Batch 来源契约继续保留 STRING；本批没有改旧冻结请求或隔离目标。
- 来源重复键、主营分类冲突、增减持同键但不同变动区间都会停止。其他事件来源缺少原范围旧键时也停止，不删除或掩盖旧值。
- 合约目录保留旧合约，披露计划保留旧公告版本。本次来源键单列，旧行保留摘要并纳入最终键值核验；不把旧行认证为当前目录或本次来源覆盖。非空旧范围遇到空来源仍拒绝完成。
- 每批按完整键和值回读、检查稳定 WAL，最后再核对原请求全范围。回读、结构检查和发送确认分别记录。

异动公开读取继续用 v2 的 `instrument_code`、`announcement_date`，写入显式使用 v1 物理字段。原只读注册归并到同一 owner，公开列和日期含义不变。

## 当前验证范围

非空来源和原表预检、被阻断范围、原件摘要见[阶段证据](audits/2026-10-21-cn-remaining-contract-tables.json)。单证券、单日期或单交易所不代表全市场、全历史已齐。常驻调度、正式补数、下一次更新及消费者验收仍未完成。

## 数组和旧键

`cyq_chips` 收齐全部价格档位后，按价格排序，成对保存 `price`、`percent` 原生 DOUBLE[]。拒绝重复价格、混合日期、无效数值和明显不完整的占比，不归一化来源比例。每次修订替换整对数组，再逐项回读；不使用 JSON/BLOB。真实来源当前有 103 档，原表同日为 104 档，两者不能按长度认证一致。

`broker_recommend` 的键是月份、券商、证券；`stk_alert` 的键是证券、起始日、类型，结束日允许修订或为空。`sge_daily` 的键是合约、来源交易日。公开读取和写入均拒绝旧物理键，不提前对外提供新键分页。

```sh
./gradlew run --args='plan-contract-table-key-repair --dataset broker_recommend'
./gradlew run --args='plan-contract-table-key-repair --dataset stk_alert'
./gradlew run --args='plan-contract-table-key-repair --dataset sge_daily'
```

这条命令只读全表结构、无效键、按新键分组的冲突和全部来源补数范围；范围日期以 `*_us` 表示 UTC 午夜载体的微秒数。核对原物理对象和稳定 WAL 后才返回计划，不执行 ALTER。冲突或空键要求重建；没有冲突也必须核对停写、账本、备份和消费者，再显式改键及重新补数。改键不能恢复旧键曾吞掉的数据。输出只覆盖当前已有日期；补齐新日期仍需独立来源范围。

本次五表结果、只读改键计划及原生数组私有验收见[第二批证据](audits/2026-10-21-cn-remaining-round2.json)。没有正式写入、日批启用或全市场持续更新验收。
