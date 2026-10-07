# D105 · v_macro_core_monthly

**实际有界 VIEW 读取已验证，最终协调登记待完成。** root 已完成 private/formal 两次 Java 只读验收和最终依赖闭包；人工复核保持 `pending_review`，本文不准入 D106。本文作者只读取保存的文件并编写实现/交付文档，没有执行测试、QuestDB 查询、native 检查、DDL/DML 或 Python owner。

本页是 DTO/mapper/repository 作者的交付说明，不代替独立实现审查。逐行来源见 [source contract](source-contract-20261007.json)，逐字段映射和冻结代码 SHA 见 [mapping contract](mapping-contract-20261007.json)。

## 实际来源与普通 VIEW 边界

原声明位于 `D:/work/fund_2/back-monitor/sql/questdb/derived/create_monthly_derived_views.sql:13`：

```sql
CREATE OR REPLACE VIEW v_macro_core_monthly AS (
    SELECT * FROM macro_core_monthly
);
```

这是 D104 基表的九字段别名，没有 WHERE、JOIN、聚合、填补、缩放或舍入。自然身份只有 `month`；普通 VIEW 的物理分区、WAL publication、DEDUP、UPSERT、直接 writer 和 checkpoint 均为 N/A。Dataset 为 `VIEW / NONE / READ`，仅登记逻辑依赖 `macro_core_monthly`。刷新引用现有 canonical `data.macro_core_monthly` v1，不新建竞争 job；管理/plan/run/status/resume/reconcile 复用 [D104 有界 owner 入口](../D104/README.md)。

root 已记录 [formal SELECT-only metadata](commands/root-formal-view-metadata-20261007.json)，SHA `9ce66ed64413732c8577e19bb53b72b44d2e35a11c9a040320e9009a9866059f`：正式 alias id1806、directory `v_macro_core_monthly~1806`、原 SQL、status valid、完整九列，所有 upsertKey=false，month designated=true。native metadata 仍返回 `partitionBy=N/A, walEnabled=true, dedup=false, matView=false`。这个 **WAL=true 是真实 metadata 观察**，不使普通 VIEW 获得物理持久化或写入能力；Java 契约 WAL=false、写入 N/A。

Python `MacroQueryService.fetch_view_first` 捕获任意 Exception 后查询 base，现有 unit caller 确认这条 fallback。Java view 读取要求实际 alias/schema/base 版本成立，失败传播到调用方，不转成 base page 或 empty。DQ 中 `pit_compliant/backtest_safe=true` 是历史配置事实，本项不据此认证 PIT 或回测可用性；没有全量生产 caller 切换证明。

## 九列映射

| 顺序 | 输出 / accessor | 类型、空值与单位 |
| --- | --- | --- |
| 1 | `month` / `month()` | `YearMonth`；DatasetValues 为首日 `LocalDate`；storage 为首日 UTC 午夜 TIMESTAMP micros；非空，JSON `YYYY-MM` |
| 2 | `cpi_yoy` / `cpiYoy()` | nullable finite Double；原存储值，历史来源声明 % |
| 3 | `ppi_yoy` / `ppiYoy()` | nullable finite Double；原同比值，不换算 |
| 4 | `pmi_mfg` / `pmiMfg()` | nullable finite Double；制造业 PMI 原水平值 |
| 5 | `gdp_yoy` / `gdpYoy()` | nullable finite Double；原 GDP YoY，只在 report_date 本月；不 forward fill |
| 6 | `m2_yoy` / `m2Yoy()` | nullable finite Double；原存储值，历史来源声明 % |
| 7 | `social_financing_stock` / `socialFinancingStock()` | nullable finite Double；原 stock，历史本地规格声明万亿元 |
| 8 | `new_rmb_loan` / `newRmbLoan()` | nullable finite Double；兼容名称实际为**社融总 inc_month**，历史本地规格声明亿元 |
| 9 | `social_financing_yoy` / `socialFinancingYoy()` | nullable finite Double；base 当前 stock / 前第12个物理观测 stock −1 的 fractional ratio，不乘100 |

全部 alias sourceName 显式为 `macro_core_monthly.<field>`。NULL、signed zero 和 finite binary64 值原样保留；拒绝 NaN/Infinity、字符串和其他数字 carrier 的隐式 coercion。历史 required-source 指标为空的 base 行仍可 READ，VIEW 不重新套用 D104 新发布 required6 门限，也不补0。月份拒绝非首日、非 UTC 午夜、subsecond、year0 或 year>9999；月份是观察期间，不是发布时间。

generated `domain/view/MacroCoreMonthlyView` 未手改；新业务 DTO 位于 domain 根包。`mapper.toStorage` 仅为纯 projection 转换，没有数据库写入。

## 有界 typed READ 与快照

- 完整有序九列；按 first-day `month` 完整键等值读取，或递增的 first-day `[from,to)` 范围，最多12月，pageSize≤12。
- `findForMonth(YearMonth)`、`findKey(MacroCoreMonthlyViewKey)`、`findRange(fromInclusive,toExclusive,pageSize,cursor)` 和 `findPage(query)`；非法范围、部分列、错误日期 carrier 和超页预算在 reader 调用前拒绝。
- Repository 传 `sourceVersion=null`，由共享 D105 guard 生成真实 view/base physical version。cursor 绑定 query fingerprint、month key 与实际 source token；不存在业务 `source_version` 列。
- 仅准入 formal `v_macro_core_monthly → macro_core_monthly` 和明确 private `java_d105_v_macro_core_monthly_acceptance → java_d104_macro_core_monthly_acceptance`。
- VIEW 原 SQL/id/directory/status/schema 与 base id/directory/schema/physical txn/WAL 必须在读前后稳定；root 已在两个实际目标验证 typed ReadGroup、三页 cursor 和相应快照稳定。生产 READ 没有 logical-date/闭合月份限制；本次 June..August 只是闭合月份样例。

## 有界 request 示例

以下说明当前 parser 的 READ request 结构；实际输入分别保存在 [private request](commands/strictread-group-request-D105-private-20261007.json) 和 [formal request](commands/strictread-group-request-D105-formal-20261007.json)。本文没有提交请求，结构片段不能代替实际读取证据。

```json
{
  "timeoutMillis": 30000,
  "members": [{
    "memberId": "macro-view",
    "datasetId": "v_macro_core_monthly",
    "definitionVersion": 1,
    "query": {
      "columns": ["month", "cpi_yoy", "ppi_yoy", "pmi_mfg", "gdp_yoy", "m2_yoy", "social_financing_stock", "new_rmb_loan", "social_financing_yoy"],
      "equalities": {},
      "rangeColumn": "month",
      "fromInclusive": "2026-06-01",
      "toExclusive": "2026-09-01",
      "pageSize": 1,
      "cursor": null
    }
  }]
}
```

显式隔离配置：`APP_QUESTDB_HOST=127.0.0.1`、`APP_QUESTDB_PG_PORT=18852`、`APP_QUESTDB_QWP_PORT=19040`、`APP_SYNC_MACRO_CORE_MONTHLY_VIEW_TARGET_VIEW=java_d105_v_macro_core_monthly_acceptance`；连接凭据来自现有本地配置，不列 secret。配置后有界读取入口为 `read-dataset-group --request PATH`。按键改为 `equalities={"month":"2026-06-01"}` 且 range 字段为 null；后续页使用原 response 的完整 nextCursor，不生成假的 physical version。

本项没有 WRITE request、安装方法或独立 sync 命令。直接 WRITE/STATIC_REPLACE/WAL_REPLACE 必须拒绝；base 刷新须走已准入的 D104 owner，新操作另行冻结 plan/source/target，不能重跑既存 fixture 或已提交 batch。

## 已有 base 证据与 D105 实际验收

D104 已 [accepted_for_serial_progress](../D104/coordinator-review-20261007.json)，gate SHA `a5fd14527a068185f222abf54292c0f8532a50d8ae2eb49f14e4fc6b369d1bde`。此前实际 August 来源追加和 [canonical Java increment](../D104/commands/java-increment-acceptance-20261007.json) 已把明确 private base 刷到 June..August 三行 /27字段 /24 nullable DOUBLE slots /22非空 raw bits，0 tolerance；GDP July/August 为 NULL。

这属于 **跨任务复用的 D104 base 刷新证据**。D105 没有追加来源月份、重写 base、修订源值或推进 checkpoint；已有三月 fixture 的新读取不计为 D105 增量发布。实际步骤已完成 June/July first read→same-range replay→July/August later read window，并逐列对照当前 alias/base、全三月分页和真实 configured ReadGroup；September 空窗口正常返回空页。

| 已执行阶段 | 实际证据与范围 |
| --- | --- |
| 独立 missing-only private setup | [唯一 CREATE receipt](commands/view-isolated-acceptance-20261007.json)，SHA `191475bf1496c53a78044139f865bd31f050c13dc6fe1701f4846eb5a832536a`；attempted/acknowledged DDL=1，alias id16；DML/ILP/base/formal writes=0，once claim 与原 raw ACK 保留 |
| 正式 Python SELECT-only audit | [formal audit](commands/view-formal-readonly-audit-20261007.json)，SHA `4b3aec1c690ae34a0b69e35df34d535d7a881454e2e549623e11724eb9be437d`；原 view/base 原值读取，无正式写入 |
| 实际 Java private | [private receipt](commands/java-view-read-private-acceptance-20261007.json)，SHA `84fe5329f4440c053da258da9f53b9c5a8b126aa2f3dd0d559b9262b5adf78a3`；`VERIFIED_PRIVATE_BOUNDED_VIEW_READ`，该阶段 DDL/DML/ILP=0 |
| 实际 Java formal | [formal receipt](commands/java-view-read-formal-acceptance-20261007.json)，SHA `bc60e0dc9fed69a599a33ed8dddf4ecc54ff821dba93a9b174acebc9c1d43e1c`；`VERIFIED_FORMAL_BOUNDED_VIEW_READ`，该阶段 DDL/DML/ILP=0 |
| 最终 SELECT-only/native/ledger 闭包 | [terminal review](commands/java-readers-process-and-final-readonly-review-20261007.json)，SHA `51573babd16c9d2af10a18a6732d541319df003f1c914a37f0e718bbc5823355`；两 reader JVM 与原 CREATE producer 的原 PID+birth 实例已停止；两目标七表/schema/view metadata 前后稳定；仅 private 完整回读28来源行/412字段（383 DOUBLE slots/338非空位值/45 NULL），formal 未做本次完整 raw 来源回读，保留原 D104 正式来源 capture 为历史前置证据；D104 SQLite 9runs/21entries/85events/1group/0leases 不变 |

每个实际目标 June..August 共3行、27唯一字段、24 nullable DOUBLE slots，其中22非空 raw bits、2个 NULL，view/base/原 owner oracle 比较均为 **0 tolerance**；GDP July/August 的 NULL 没有填补。各 scope 验证完整键、三页分页、真实 queryFingerprint/physical token、同窗重读、换窗 cursor 拒绝、真实 configured typed ReadGroup、非法月份/投影/预算在 reader 前拒绝、直接 WRITE 与 replacement 拒绝。取消证据仅为 **pre-cancelled ReadGroup 在任何 reader 调用前返回 CANCELLED，reader invocation=0**，没有 in-flight cancellation 实测。

private setup 的一次 CREATE 与后续 Java 两个零写入读取阶段分别计数。原失败/claim/raw response 不覆盖；UNKNOWN CREATE 禁止自动重试。没有 D105 owner 调用、base 发布、ledger 初始化或 mutation，原 D104 ledger 与来源均保留。

## 实现测试与限制

root 已实际通过 [唯一库存](commands/validation-inventory-20261007.json)，SHA `00a6af0e173b55b97824ab29484d93226675cd72bc5cca45c447a96884ce8da0`：60个 pure Java +1个唯一 live 方法=**61个唯一 Java 方法**；同一 live 方法执行 private/formal 两 scope，因此共62次 PASS 调用。Python 当前44个唯一 guards 全通过，旧43项是子集，不累加。当前库存 failure/error/skip 均为0；本作者未执行这些测试。

owned Mapping12/Repository8 已纳入实际60 pure，另有 Guard37/Catalog1/WriteRejection2。首次编译因两处 import 错误失败且没有执行测试，[原失败 log](commands/java-pure-tests-20261007.log) 保留；修正后 [实际 pure log](commands/java-pure-tests-import-correction-20261007.log) 与五份 XML 单独归档，不计重复 PASS。当前 mapping test SHA 为 `2e4e71b3256640e3a8811aca5507d1e18d273c8d9143890ac033ae3b70a2f975`；实际 live harness SHA 为 `b6aa1fc3646ef968dabb0bb3cf246cf054a57e18538302c5cdefb16668a0e0d1`。旧未执行 harness 和静态审查原件保留，import 修正另有 [独立 supplement](commands/coordinator-shared-guard-import-supplement-20261007.json)。实际 catalog 为54 datasets/42 jobs，仅引用既有 D104 base job，没有 alias job。

SQL timeout 为每条 guarded statement 20秒，有限调用数量不等于单一端到端20秒 deadline。实际月份使用原生 UTC timestamp scalar，view status 使用完整 micros/string carrier；不把 JDBC Timestamp 的本地 epoch carrier 当 native UTC 权威。生产范围限制只有首月日身份、递增≤12个月、完整九列和 page≤12，不附加 audit 的 closed-window 条件。

本项只认证两个目标各自的 June..August 有界读取，不认证 provider universe、完整历史、宏观六 upstream Java owners 已迁移、发布时间/PIT、FULL、自动正式刷新、全生产 caller cutover、真实 source revision 或全局跨查询原子快照。正式 base 窗口外仍有历史行，未验证其全部内容。D104 base UNKNOWN/reconcile/checkpoint 的限制沿用其交付，VIEW 读取不能解除旧写入疑义。最终协调登记和人工复核仍待完成，本文不准入 D106。
