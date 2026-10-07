# D103 — equity_style_monthly Java 映射与隔离验收证据

本页记录当前 Java 实现、初始隔离数据、新的只读恢复及实际 August 增量。**隔离 source append 与 canonical Java INCREMENTAL 已通过；最终 coordinator gate、人工验收仍 pending，下一任务未准入。** 原 initial live JUnit 失败与原 source INSERT 的 UNKNOWN ACK 保留。

机器可读初始 cutoff 契约见 [mapping-contract.json](mapping-contract.json)，该原件保持不变；当前实际增量以 [mapping-contract-increment-update-20261006.json](mapping-contract-increment-update-20261006.json) 补充。冻结的 [原 source contract](source-contract-20261006.json) 保留；单位语义以 [stored-units 补充](source-contract-stored-units-20261006.json) 和 [来源链核验](commands/source-stored-unit-lineage-review-20261006.json) 为准。

## 类型、键与物理表

| 项目 | 当前契约 |
| --- | --- |
| Dataset | `equity_style_monthly` v1，TABLE，READ / WRITE |
| Domain / key | `EquityStyleMonthly` / `EquityStyleMonthlyKey`，`YearMonth` 单键 |
| 月份存储 | `month TIMESTAMP`，该月第一日 exact UTC midnight，微秒；逻辑 JSON 为 `YYYY-MM` |
| 数值 | 29 个 nullable finite `Double`，全 30 列显式映射；保留 NULL、signed zero、原始 binary64 位值 |
| 物理策略 | `TIMESTAMP(month) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(month)` |
| 可写目标 | 必须显式配置 `java_d103_equity_style_monthly_[a-z0-9]{1,64}`；job 的 target 默认空白 |
| 默认读取 | 正式 `equity_style_monthly`；配置同一 target 属性可切至隔离表 |

`month` 是月份标签，不是交易月末、发布时间或数据可用时间。非首日、非午夜及非法年份拒绝。READ 可以保留历史全 NULL 数值行；typed WRITE 拒绝 timestamp-only 的全 29 NULL 行，批次上限 12 个唯一月份、1 MiB。

missing-only DDL 由 `EquityStyleMonthlyWritePort.createIsolatedTarget()` 构造全部 30 列。现有表按 schema/layout 原位校验；实际验收写入均在独立私有实例。源码的 PGWire / ILP endpoint 一致性检查与实际进程 attestation 是分别记录的证据。

## Python 映射与单位

原 owner 是 `data/adapters/materializers/equity_style_monthly.py::build_equity_style_monthly`，由 month-end task 链调用。Java Source 从 D022 `index_monthly` 精确 14 列读取：`ts_code, trade_date, close, open, high, low, pre_close, change, pct_chg, vol, amount, layer, bucket, update_time`。源必须 YEAR / WAL / **DEDUP=false**；输出的 month DEDUP 不改变源契约。

原 Python 按日期排序后 `pivot_table(..., aggfunc="last")` 取月内最后非 NULL 的 `pct_chg`。Java 保持该规则，后来的 NULL 不抹除较早值；同 `(ts_code, trade_date)` 重复因缺少确定性 tie breaker 而拒绝。全窗口缺失或全 NULL 的必需 code 不能作为完整非空输出；历史单月缺格保持 NULL，整月全空被省略。

| 直接字段 | 冻结完整 code |
| --- | --- |
| hs300 / zz500 / all_a / cs1000 | `000300.SH` / `000905.SH` / `000985.SH` / `000852.SH` |
| growth / value | **`000921.SH` / `000920.SH`**，保持原 owner 的旧绑定 |
| energy / materials / industrials | `000986.SH` / `000987.SH` / `000988.SH` |
| consumer_discretionary / consumer_staples / healthcare | `000989.SH` / `000990.SH` / `000991.SH` |
| financials / it / telecom / utilities | `000992.SH` / `000993.SH` / `000994.SH` / `000995.SH` |

16 个直接字段消费 **source-stored `pct_chg` 原值**；3 个风格差值为 `cs1000-hs300`、`zz500-hs300`、`growth-value`，10 个行业差值为各行业减 `all_a`，全部保持同一存储单位。**没有 ÷100、×100、close-return 重算或舍入。**

旧 Python 模型注释与 D022/早期契约声明 percent；实际保留的 D022 provider JSON 与当前源 raw bits 一致，48 个样例值呈 ratio-like 且与 `round(change/pre_close,4)` 吻合。这是局部来源链与数学诊断，不能认证所有历史/provider 的统一单位。D103 元数据已采用 unchanged stored units；旧 920/921 命名冲突也保留，consumer UI 的 ×100 显示行为不进入 mapper。

## 实现与统一入口

| 层 | 文件 / 接线 |
| --- | --- |
| Domain / metadata | `EquityStyleMonthly.java`、`EquityStyleMonthlyKey.java`、`EquityStyleMonthlyDataset.java` |
| 映射 / storage carrier | `EquityStyleMonthlyMapper.java` ↔ 独立 `EquityStyleMonthlyRow.java` |
| Typed READ | `EquityStyleMonthlyReadRepository` + `QuestDbBoundedReader` / `QuestDbEquityStyleReadGuard` |
| Typed WRITE | `EquityStyleMonthlyWritePort` + exact null-bitmap/raw-bits CODEC |
| Source / adapter / job | `EquityStyleMonthlySource`、`EquityStyleMonthlyMaterializeAdapter`、`EquityStyleMonthlyJobService` |
| ReadGroup | `ReadGroupConfiguration` 注册 domain/mapper，physical version 由共享 reader 护栏固定 |
| WriteGroup | `StockBasicWriteGroupService` 注册 `PreparedWriteAdapter`，要求真实 owner、target identity 与 pending-publication 准入 |
| CLI | `EquityStyleMonthlyCommands` 经 `CommandLineRunner` 分派 |

canonical job 是 **`data.equity_style_monthly` v1**，owner `equity_style_monthly_owner`，依赖 **`data.index_monthly` v1**。支持 MATERIALIZE、INCREMENTAL、RECONCILE；默认 INCREMENTAL，manual，非 daily eligible。使用 `questdb.materialize` / `equity_style_monthly.month_window` / `questdb.full_key_values`，共享 `SyncJobRunner`、durable ledger、batch verification 和 dataset allDates 冲突锁。

CLI 已实现：

- `install-equity-style-monthly-isolated`
- `plan-equity-style-monthly-job` / `run-equity-style-monthly-job`：`--from YYYY-MM-01 --to YYYY-MM-01 --logical-date YYYY-MM-DD [--mode MATERIALIZE|INCREMENTAL|RECONCILE]`，from/to 为 inclusive 月首。
- `equity-style-monthly-job-status` / `cancel-equity-style-monthly-run` / `resume-equity-style-monthly-run` / `reconcile-equity-style-monthly-run`：精确 `--run`。

source 属性为 `app.sync.equity-style-monthly.source-table`（默认 `index_monthly`）；target 属性为 `app.sync.equity-style-monthly.target-table`，job 默认空白；ledger 使用 `app.sync.ledger-path`。catalog 实际启动有 canonical 注册，**0 DB connections、无 ledger 创建**，见 [catalog evidence](commands/java-catalog-startup-final-20261006.json)。Prepared WriteGroup 核验显式 caller payload；source-oracle 证明属于 materialize job。

## 有界读取、source 与 checkpoint

- Job 的 bootstrap..to 至多 **12 个月**；单页至多 **12 行、1 page / 1 slice**。Source 固定 16 codes 和明确月份 SQL 范围，完整 14 列最多 **6200 返回行**，`LIMIT 6201` sentinel 拒绝截断；query timeout 20 秒，job deadline 5 分钟。该返回上限不认证数据库内部扫描行数。
- Typed READ 需要完整有序 30 列、月首 `LocalDate` 精确键或 increasing 月份范围，范围为 from inclusive / to exclusive ≤12 个月、pageSize ≤12。cursor 固定 table id / directory / physical txn / WAL / schema；source_version 不能替代物理快照。同月份 DEDUP 覆盖后旧 cursor 拒绝。
- 新空表 `table_txn=null` 保留 `uninitialized` token；仅独立 COUNT0、WAL writer/sequence/pending/buffer 全 0、raw nullable metadata 为 NULL/0 且前后稳定时通过，不将 NULL 转成 txn0。
- Source 的 id / directory / physicalTxn / sequencerTxn / schema 与 settled writer 检查固定；**all14 raw hash** 包含不参与公式的 close 等字段、键、NULL、timestamp 与九个 DOUBLE 位值。plan、preflight、fetch、send/readback/WAL 前后及最终 prefix 都重新核验同一 generation。
- 所有 job 模式要求 to 月早于 logicalDate 所在月。MATERIALIZE 保持历史 nullable/缺月 pivot 语义；**INCREMENTAL** 额外要求 bootstrap..to 每个闭合月连续，16 个直接 cell 均至少一个 finite 非 NULL source 值，否则 send 前拒绝。
- checkpoint 候选仅 VERIFIED，不使用 VERIFIED_EMPTY；核 exact target、同 anchor、原 durable FETCHED/verification fingerprint、连续闭合 16-cell 旧 prefix，以及旧 target 全 30 字段原值。仅新增源且旧 all14 raw prefix 不变才从旧 checkpoint 月开始 overlap 1 月；旧 prefix 任意修订则有限 bootstrap 重建。旧 target 漂移拒绝。
- full bootstrap prefix 的所有键、NULL 和 DOUBLE raw bits 回读，以及 source/target 的稳定 WAL/version 检查在 **adapter.fetch 返回之前**完成，shared runner 的 VERIFIED 发生在这些检查之后。空输出不推进 checkpoint。

## UNKNOWN、取消与终态边界

共享 ledger 的 SUBMITTED durable intent 先于 writer。HTTP 单 flush，关闭 autoFlush 和 retry；UNKNOWN 保留 IN_DOUBT / allDates lease，禁止自动重发。FAILED/CANCELLED/PARTIAL 的 resume 使用原 frozen 请求；successful / UNKNOWN run 不可被当作新请求重送。

UNKNOWN reconcile 仅在原 same service instance 的真实 sender reset/close 已完成，且冻结 source、full-prefix target/WAL 全值吻合后处理。支持独立 SQLite transition 部分成功后的幂等收尾，包括 ATTEMPT RUNNING / IN_DOUBT；沿用 shared child guards。新 JVM 没有旧 sender 停止证明，CLI 没有 `--writer-stopped` 解锁开关。

后续 fixture 增量使用独立的 scoped terminal 协议，绑定原失败、只读恢复、executor/XML/native stop、实际只读 SQLite 全 map 与 source/target/formal 前沿。它不能替代未知 writer 的持久恢复准入，也不能重发原 UNKNOWN fixture claim。

## 当前真实证据与保留失败

| 阶段 | 结果 / 边界 |
| --- | --- |
| Formal SELECT-only | June/July/August 48×14 = **672 fields、432 DOUBLE values**；Aug 既存输出 **30 fields /29 bits** exact；June/July 缺失，formal parity=false，未修复 |
| Private source initial | June/July **32 rows /448 fields /288 DOUBLE bits** exact，source `index_monthly~9` txn1；Aug16在初始阶段为 held capture，后续增量独立一次 ACK |
| 原 Python fixture | source CREATE ACKNOWLEDGED；INSERT 的 DML response 解析失配留下 **UNKNOWN**，没有重发；独立 SELECT exact delivery proof 不升级 ACK |
| 原 Java initial pipeline | 实际 first/replay/resume/readonly reconcile/typed WriteGroup/cancel；SQLite **8 runs /18 entries /72 events /1 group /0 leases**，目标 **2 rows /txn4** |
| 原 live JUnit | **1 failure**：最后 receipt 的 YearMonth serialization；原 JVM PID/birth 未记录，原失败 XML/log 与 run/event 不改 |
| Fresh Java readonly recovery | **1 case passed**，source32 /target2，**60 fields /58 DOUBLE bits** exact；new DDL/DML/ILP/materialization runs 全0，ledger 原位未变 |
| Actual August increment | source 新增 **16 rows /1 ACK**，总 **48 rows /txn2**；Java July overlap→August **VERIFIED2**，target **3 rows /txn5**；全 prefix **90 fields /87 bits** exact0tol，最终 gate pending |

原 first/replay/exact resume/readonly reconcile 的 ledger 状态为 VERIFIED，并不把原 JUnit 文件提升成 PASS。新 receipt 为 [java-initial-readonly-recovery-20261006.json](commands/java-initial-readonly-recovery-20261006.json)，SHA **`59dcdd9257c198c72944828f67226e74be05d760fb0bb37475f032b162aec7b4`**。新 JVM47280/birth2026-10-06T15:31:02.250865Z 的成功 executor exit0、XML 与 native stopped 单独绑定；原 JVM 身份仍 unknown。原 source INSERT ACK 继续 UNKNOWN。

关键证据：

- [Formal source/oracle preflight](commands/equity-style-readonly-preflight-20261006.json)；早期 percent 文案已由 stored-units 来源链补充纠正，数值 capture 没有变化。
- [原 fixture FAILED](commands/source-fixture-initial-20261006.json) 与 [只读 source delivery reconciliation](commands/source-fixture-initial-readonly-reconciliation-20261006.json)。
- [原 Java terminal ledger](commands/original-initial-java-terminal-ledger-20261006.json)、[原 JUnit log](commands/java-initial-live-20261006.log) 与 [限定只读恢复 admission](commands/coordinator-initial-java-receipt-failure-recovery-20261006.json)。
- [新只读 terminal ledger](commands/readonly-recovery-java-terminal-ledger-20261006.json)、[executor completion](commands/readonly-recovery-java-executor-completion-20261006.json)、[native stopped](commands/readonly-recovery-java-native-stop-20261006.json)。

## 实际 August 增量补充

[Source increment receipt](commands/source-fixture-increment-20261006.json)，SHA `b04f0d4b5dac39a6d1defc879ad182113a79d170ebecffc7743e63883ef8d6b3`，记录 sole August16 INSERT 一次 ACK，DDL/formal/output writes/initial resubmissions 全0。源由32/txn1变48/txn2，完整672 fields /432 DOUBLE bits与原真实 capture exact；fixture 阶段两月 target 全60 fields /58 bits和 formal 前后原位不变。

[Java incremental receipt](commands/java-increment-acceptance-20261006.json)，SHA `a13875847e736ba3848190071800fb8968fb6dfde879344e0338fbda647549e5`：`d103-9a1915a5-31f0-4e0c-9412-ae4fc64c366b` VERIFIED2，`bootstrap=2026-06-01`，effective `from=2026-07-01` / `to=2026-08-01`，`VERIFIED_PREFIX_APPEND`，checkpoint parent 为原 readonly RECONCILE `d103-dee9e136-8495-4baf-b5f3-8f34a20fdbae`。实际 overlap/recompute两月，没有 reused rows；全 June..August prefix3rows/90fields/87bits exact0tol，target相同id/directory，txn4→5，formal不变。

实际 increment JVM43596/birth2026-10-06T15:41:06.753605Z 有三次 native target attestation，独立成功 XML、executor exit0、原 JVM identity absent 的 [native stopped](commands/final-increment-java-native-stop-20261006.json) 证据。此 phase证明实际 append/overlap；source-prefix revision fallback 仍是 pure-test 覆盖，不宣称已做 live revision。原 initial JUnit FAILED / source INSERT UNKNOWN / 原 JVM birth unknown 均未改写。

## 测试记录

当前去重 **96 个 D103 pure Java cases**（原92 +新增4个 YearMonth JSON 往返/拒绝/nested Batch/Result），另有 **27 个 shared regressions**，pure Java 合计 **123**；加 fresh readonly recovery1 与 actual increment live1，共 **125 个 unique passed Java cases**。**122 个 pure Python cases**，均0fail/error/skip。XML/log SHA manifest 在原 mapping-contract 与增量 supplement 中；guard 的新版19例替代旧13例，重复执行不重复累计。

Original initial live JUnit 仍 1 failure，原编译失败 log 也保留；fresh readonly recovery 与 actual increment 是独立的 passed cases，不能改写原测试成功。最终协调验收、人类验收、完整历史/provider readiness、PIT availability 与下游 caller 切换均未由本页认证。
