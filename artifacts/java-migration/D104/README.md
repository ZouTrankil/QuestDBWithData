# D104 · macro_core_monthly

**有限隔离验收已通过：初始两月与 August 增量均已实际验证，交付等待最终协调 gate。** 人工复核保持 `pending_review`，D105 尚未准入。

本页由 DTO/storage 作者整理映射与证据索引，不代替独立实现审查。冻结语义见 [来源契约](source-contract-20261007.json)、[逐字段映射](mapping-contract-20261007.json) 和 [设计契约](design-contract-20261007.json)。独立审查见 [domain/storage supplement](commands/coordinator-domain-storage-static-review-supplement-20261007.json)、[current Java supplement](commands/coordinator-current-java-static-review-supplement-20261007.json) 与 [其他作者 Source/Adapter/Job 最终补充](commands/coordinator-source-owner-static-review-supplement-valid-20261007.json)；最后一份明确排除本页作者的 DTO/storage 实现。

## 字段、月份与单位

| 顺序 | 输出 / Java accessor | 来源与规则 | 空值 / 单位 |
| --- | --- | --- | --- |
| 1 | `month` / `month()` | Python YYYYMM → `YearMonth`；DatasetValues 为首日 `LocalDate`；storage 为首日 UTC 午夜 TIMESTAMP micros | 必须为 1..9999 年，JSON `YYYY-MM`；不是发布时点 |
| 2 | `cpi_yoy` / `cpiYoy()` | `cn_cpi.nt_yoy` | READ nullable；发布必需；保存原值，源声明 % |
| 3 | `ppi_yoy` / `ppiYoy()` | `cn_ppi.ppi_yoy` | READ nullable；发布必需；保存原同比值 |
| 4 | `pmi_mfg` / `pmiMfg()` | `cn_pmi.pmi010000` | READ nullable；发布必需；制造业 PMI 原水平值 |
| 5 | `gdp_yoy` / `gdpYoy()` | `cn_gdp.gdp_yoy` 只进入 `report_date` 所属月；quarter 与季度末日期一致 | nullable；源声明 %；**无 carry / forward fill** |
| 6 | `m2_yoy` / `m2Yoy()` | `cn_m.m2_yoy` | READ nullable；发布必需；保存原值，源声明 % |
| 7 | `social_financing_stock` / `socialFinancingStock()` | `sf_month.stk_endval` | READ nullable；发布必需；历史本地规格声明万亿元，原值不换算 |
| 8 | `new_rmb_loan` / `newRmbLoan()` | `sf_month.inc_month` | READ nullable；发布必需；历史本地规格声明亿元；兼容名称实际表示**社融总增量** |
| 9 | `social_financing_yoy` / `socialFinancingYoy()` | 当前 stock / 前第 12 个有序物理观测 stock − 1 | nullable；fractional ratio，**不乘 100** |

八个指标均为 nullable finite `Double`，保留 NULL、binary64 原值与 signed zero，不舍入、不缩放、不加未经证实的正数或比率范围限制。READ 可读取历史必要指标为空的行；新 typed WRITE/source publication 要求 `cpi_yoy, ppi_yoy, pmi_mfg, m2_yoy, social_financing_stock, new_rmb_loan` 六项齐全。GDP 与 SFYoY 不参与此 completeness 门限。

SFYoY 沿原 `pct_change(12, fill_method=None)`：排序后、原 dedup 前的 **12 个观测**，不要求中间是连续 12 个日历月；不补缺月。前项缺失/NULL 保留 NULL；分母为零或结果非有限拒绝。原 Python 同月份 `tail(1)` 没有 tie breaker；Java 拒绝重复/乱序物理月份，不任意挑选。

原 Python owner 的 loader、rename、outer merge、九列顺序与 completeness 代码见 [source contract 的逐行引用](source-contract-20261007.json)。历史本地规格说明单位，不构成当前 provider 全历史统一单位认证。

## 六个物理来源与注册图

| 物理来源 | 原模型完整列数 | 业务依赖任务 |
| --- | ---: | --- |
| `cn_cpi` | 13 | D065 |
| `cn_ppi` | 31 | D066 |
| `cn_pmi` | 60 | D067 |
| `cn_m` | 10 | D068 |
| `cn_gdp` | 10 | D070 |
| `sf_month` | 4 | D069 |

Java Source 从固定六表读取完整物理列，并核对 YEAR/WAL/DEDUP、精确 schema、ID/directory/physical txn/WAL 与 all-raw hash。六来源 Java upstream owners 尚未注册：Dataset/job 的 registered `dependencies=[]`，实际业务依赖保留在 `SOURCE_TABLES`、rationale、来源契约和本表。没有伪造 upstream 注册，也没有本卡 provider API 同步/自动来源刷新。

## 存储、READ 与 WRITE

- TABLE / YEAR / WAL / DEDUP(`month`)，唯一自然身份为观察月份。已有 generated `MacroCoreMonthlyRow` 保留，业务 DTO 和 mapper 独立实现。
- READ 默认 `macro_core_monthly`；隔离 read/job 同用 `app.sync.macro-core-monthly.target-table`。Job target 默认空白，必须明确 `java_d104_macro_core_monthly_[a-z0-9]{1,64}`；正式表不能作为本 task writer target。
- READ 完整有序九列，精确首月键，或 from inclusive / to exclusive 的首月范围，最多 12 月、pageSize≤12。cursor 固定实际 table/schema/WAL token，覆盖同月份后旧 cursor 拒绝。
- 新空表 raw `table_txn` / `wal_txn` / metadata rowCount 的 NULL 原样保留。只在独立 COUNT0、writer/sequence/pending/buffer 全0、其余 raw counters 为 NULL/0、physicalTxn 为 NULL/0 且 bracketing metadata 完全稳定时允许；不把 NULL 当 txn0。
- WRITE 最多 12 个唯一 month / 1 MiB；required6 在任何来源查询/ledger intent 前先验证。CODEC 用月份、NULL bitmap 和全部八项 raw bits 精确比对。
- HTTP 同步**一次 flush**，autoFlush 关闭、retry timeout0；finally reset/close 丢弃缓冲。ACK 后按全键实际 SELECT/完整九值与 WAL 验证才记 VERIFIED。Prepared WriteGroup 验证 caller payload，Source/oracle 派生证明属于 canonical job。

## Canonical job 与失败边界

`data.macro_core_monthly` v1，owner `macro_core_monthly_owner`。默认 **INCREMENTAL**，支持 MATERIALIZE / INCREMENTAL / RECONCILE；manual、enabled、非 daily eligible。共享 `SyncJobRunner`、SQLite ledger、full-key verification 和 dataset **allDates** 锁；单页/单批，无独立旁路 writer。

Source window 最多 12 个闭合月，每来源至多 12 行并以 LIMIT13 拒截断；SF 另取此前最多 12 个真实观测，合计至多 **84 raw rows**。输出最多 12 行 / 1 page / 1 slice / 1 MiB。一次完整 Source read deadline 20 秒，目标 SQL/HTTP ACK/visibility 各有限 20 秒，job timeout5分钟；这些返回额度不认证数据库内部扫描量。

INCREMENTAL 从 bootstrap 复核全部闭合月 required6 completeness。Checkpoint 仅依赖 VERIFIED 原 full-prefix source/context hash 与 target exact bits；旧 prefix 未变时从旧 checkpoint 月 overlap1月，旧源 prefix 修订回退有限 bootstrap，旧 target 漂移拒绝。整个 bootstrap prefix 在 adapter 返回、shared runner 记 VERIFIED 前实际回读；空输出可留下一个空 FETCHED page/slice 并记 VERIFIED_EMPTY，但无 SUBMITTED publication/HTTP，也不推进 checkpoint。

SUBMITTED durable intent 先于 writer。UNKNOWN 保留 IN_DOUBT / allDates lease，阻止新 prepared write/job，不自动重发。FAILED/CANCELLED/PARTIAL resume 使用原 frozen request，已成功 run 不可 resume 重写。未知批次 reconcile 需要原 **same service instance** 的真实 sender reset/close 停止证明，加原冻结 source/全 prefix target/WAL 验证；新 JVM 没有该证明，CLI 没有 caller boolean 解锁开关。RECONCILE 模式只 SELECT 验值，无 publisher。

## 当前实际证据

| 阶段 | 当前事实 |
| --- | --- |
| Formal SELECT-only preflight | 六来源 June..August + SF 前12观测，共28行 /412全字段；正式既存3行 /27字段 exact0tol；正式0写入 |
| 原 source initial | CPI DDL/DML 已知2ACK；随后可见性检查失败，FAILED 回执/claim/raw响应保留，没有重发 CPI |
| Remaining-only continuation | 五来源5DDL+5DML、21真实行；加既存 CPI2行=23；old2+new10=12已知ACK；CPI resend0 |
| Complete-source readonly review | 23行 /294字段 /270 nullable DOUBLE slots /240非空 bits exact，formal/source稳定 |
| Java initial actual | first2/replay2/canonical cancel→resume2/readonly RECONCILE2/typed READ+WRITE 实测；目标2行 /18字段 /16 slots /15非空 bits exact0tol |
| Java initial terminal | 原 JVM47260 已结束；SQLite8runs /18entries /72events /0leases，原成功来源与 foreground sender 停止证据独立记录 |
| August source increment | 五来源各1条真实 August INSERT、已知5ACK；GDP0 / DDL0 / output0 / formal0；来源共28行 /412字段 /383 nullable slots /338非空 bits，45NULL，完整只读复核 exact0tol |
| Java August increment | `VERIFIED_PREFIX_APPEND`，从 July overlap1月到 August，parent 为既有 readonly RECONCILE；本次 verified2 /reused0，完整目标3行 /27字段 /24 slots /22非空 bits exact0tol，GDP July/August 两项NULL保留 |
| Java final terminal | 已知 JVM37064 已结束；SQLite9runs /21entries /85events /1group /0leases，原8runs历史保留；final target id15、physical/WAL txn5，source revision 实测为false |

关键回执：

- [formal bounded source/oracle](commands/macro-core-readonly-preflight-20261007.json)，AST oracle 保留原算法，未调用 Python public writer。
- [原 initial FAILED](commands/source-fixture-initial-20261007.json)、[known CPI readonly proof](commands/coordinator-known-initial-cpi-readonly-review-20261007.json)、[remaining-only receipt](commands/source-fixture-initial-after-known-partial-20261007.json)。
- [complete initial source readonly review](commands/coordinator-complete-initial-source-readonly-review-20261007.json)，SHA `7a69e5bc874ad824d200a61fe55d1fc75952c446920d81c1520034e99180ed7b`。
- [Java initial acceptance](commands/java-initial-acceptance-20261007.json)，SHA `2ef5ded567bf02de76170516e0acd4fedd3a4b3242133a79bafac4986bc559b1`；[terminal process/ledger review](commands/initial-java-process-and-ledger-review-20261007.json)，SHA `738744cd9f782ae0f47faa1a76064cc80b8fa7772aebb3f173fcd0798831f1e2`；其内绑定 [actual SQLite snapshot](commands/initial-java-terminal-ledger-20261007.json)。
- [catalog startup](commands/java-catalog-startup-final-20261007.json)，实际53 datasets /42 jobs，D104九字段 READ+WRITE、manual/default INCREMENTAL；[initial live XML](commands/java-initial-result-20261007/TEST-com.zoutrankil.data.config.MacroCoreMonthlyLiveAcceptanceTest.xml)。
- [August source 工具独立静态评审](commands/coordinator-source-increment-static-review-20261007.json) 和 [最终49 guard log](commands/python-source-increment-guards-frozen-20261007.log)；[实际五条来源写入](commands/source-fixture-increment-20261007.json)，SHA `a42dfaf524efa873173ee2f896d223057fb54ce251fbb585870f3aa36bb10fcc`；[完整增量来源只读复核](commands/coordinator-complete-increment-source-readonly-review-20261007.json)，SHA `501e3cebf4b9118f50178234a23117faa219e0c3ce6ca4f6065f48dd7abf7d00`。
- [Java increment acceptance](commands/java-increment-acceptance-20261007.json)，SHA `b1aec9b83be584059cbe2aad5d18240a14167915b5c37ad9f02568e586c318ca`；[final terminal process/ledger review](commands/increment-java-process-and-ledger-review-20261007.json)，SHA `9242676d38e609336efd4b2c0276e4d43fa5ea1d981e1e993adc5689625251db`；[actual final SQLite snapshot](commands/increment-java-terminal-ledger-20261007.json)；[increment live XML](commands/java-increment-result-20261007/TEST-com.zoutrankil.data.config.MacroCoreMonthlyLiveAcceptanceTest.xml)。
- [唯一测试库存](commands/validation-inventory-20261007.json)，SHA `16b78883f5d9add95ba76b3f8ed8ad622e4c45af1280836d1046269ca5173b08`。

增量第一次 SELECT-only 审计已完成28行/412字段比较，随后保存 executor 证据时发现预期日志名缺少 `-actual`，因此该次审计退出失败。原 [失败 log](commands/coordinator-complete-increment-source-readonly-review-actual-20261007.log) 保留；新的 [补充 audit log](commands/coordinator-complete-increment-source-readonly-review-supplement-20261007.log) 对真实日志做 CREATE_NEW 字节绑定并重新完成全量只读审计，另存 native-stop supplement。此问题不是数据不一致或源 ACK 未知，五条已成功源写入没有重发，原工具和失败证据没有被替换。

实际隔离实例为 `var/d104-isolated-questdb`，HTTP19040 / PG18852；每次实际 write 前保存 listener PID/birth/root/module 的 native attestation，并核 frozen source/oracle。没有正式/参考项目/provider 写入。已完成源复制不表示六个 upstream Java jobs 已迁移。

## 有界命令与 typed group 示例

以下是明确隔离配置下的命令用法，**本页没有执行命令**。不要自动重跑本页已完成验收 fixture、setup claim 或已提交 batchId；新来源代次应新 plan，UNKNOWN 不以重启/再次 run 自动恢复。连接凭据继续由本地配置或环境提供，本页不列 secret。

```powershell
$env:SPRING_MAIN_WEB_APPLICATION_TYPE = 'none'
$env:APP_QUESTDB_HOST = '127.0.0.1'
$env:APP_QUESTDB_PG_PORT = '18852'
$env:APP_QUESTDB_QWP_PORT = '19040'
$env:APP_SYNC_MACRO_CORE_MONTHLY_TARGET_TABLE = 'java_d104_macro_core_monthly_acceptance'
$env:APP_SYNC_LEDGER_PATH = 'var/d104-java-acceptance.sqlite3'
./gradlew.bat run --args='plan-macro-core-monthly-job --from 2026-06-01 --to 2026-07-01 --logical-date 2026-10-07 --mode INCREMENTAL'
./gradlew.bat run --args='macro-core-monthly-job-status --run d104-ca014c1b-aa36-4914-9320-215b9d744502'
./gradlew.bat run --args='read-dataset-group --request artifacts/java-migration/D104/commands/read-group-initial-20261007.json'
```

管理入口参数严格匹配当前 parser：

| 命令 | 有界参数 / 行为 |
| --- | --- |
| `plan-macro-core-monthly-job` / `run-macro-core-monthly-job` | `--from YYYY-MM-01 --to YYYY-MM-01 --logical-date YYYY-MM-DD [--mode MATERIALIZE\|INCREMENTAL\|RECONCILE]`；from/to inclusive、≤12闭合月；plan SELECT-only，run 只有新明确准入后调用 |
| `macro-core-monthly-job-status` | 精确 `--run RUN_ID`，账本状态与当前 target observation error 分开返回 |
| `cancel-macro-core-monthly-run` | 精确 `--run RUN_ID`，terminal run 不当作新取消执行 |
| `resume-macro-core-monthly-run` | 精确 `--run RUN_ID`，仅适用 frozen FAILED/CANCELLED/PARTIAL，不能重发 UNKNOWN/VERIFIED |
| `reconcile-macro-core-monthly-run` | 精确 `--run RUN_ID`；same-instance 真实停止证据/全 prefix proof，不支持新 JVM 伪造停止 |
| `install-macro-core-monthly-isolated` | 无参数；missing-only DDL setup，实际验收另有 CREATE_NEW once claim，不能自动重试未知 setup |

Typed group 通过 `read-dataset-group --request PATH` / `write-dataset-group --request PATH` 读取真实生成请求。九字段输入使用完整十进制 binary64 transport 值，不能抄显示舍入值；READ month carrier 为 `2026-06-01`，domain JSON month 为 `2026-06`。WRITE 只接收明确 private target 的完整 required6 DTO，拒绝 STATIC/WAL_REPLACE 和全空必要字段。实际请求为 [read-group initial](commands/read-group-initial-20261007.json)、[read-group increment](commands/read-group-increment-20261007.json) 和 [write-group initial](commands/write-group-initial-20261007.json)，对应两份 live receipt 的 configured groups；WRITE 文件属于已完成批次的历史 schema/数据证据，**不可重新提交该 batchId**。新操作必须生成独立输入并重新冻结 target/source 准入。实际管理账本是 [d104-java-acceptance.sqlite3](../../../var/d104-java-acceptance.sqlite3)，增量 run 为 `d104-04c432e7-4096-4f09-b56d-35cedfa4d8e4`。

## 测试与限制

root 执行并归档 **123 unique passing Java methods**：122 pure（最终九类121 +此前独立 catalog1）+1个 live 方法。该 live 方法分别执行 initial 与 increment 两阶段，故共有 **124 PASS invocations**、2次实际 live stage；不能算成两个唯一方法。初始120中一项 Source 测试夹具错误及失败 XML/log 保留，不重复累计。**159 unique pure Python** = preflight44 + initial35 + remaining-only31 +最终 increment49；旧41/47等 rerun log 保留，不额外叠加计数。当前库存0fail/error/skip；本页作者只读/整理已有 log，不宣称自行执行。

本任务仅认证有限 June..August 真实来源快照、SF 前12个观测与独立隔离目标。没有正式 FULL/替换、完整历史、provider universe、PIT availability、自动调度、跨代次写入切换或 consumer cutover 认证。Source revision fallback、UNKNOWN same-instance reconcile 等边界有纯测；当前实测仅追加 August，未做 source revision，也不把纯测记作现场 UNKNOWN 恢复。历史 FAILED、已知 ACK、日志保存失败和未准入操作保留原状态。最终 serial gate 与人工验收仍待协调器/用户决定，D105 不由本页准入。
