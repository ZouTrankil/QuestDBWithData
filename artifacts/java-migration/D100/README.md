# D100 · retail_sentiment_daily_cache 证据索引

**私有发布及 Java 实际读取验收：VERIFIED_STORED_GENERATIONS。** 本项按 `retained_compatibility` 验证原 Python 唯一 publisher 的历史缓存 generation 和 Java typed READ；不认证 current cache hit、生产 latest 或 provider/universe readiness。人工复核保持 `pending_review`，由用户决定。

协调者已运行 126 个唯一 Java 用例：124 个 unit/read regression、1 个实际 cursor capture、1 个实际 final read，均为 0 failure/error/skip。35 个唯一 Python 纯 mock 护栏通过；初始 31 个用例报告另存，不重复相加。原 Python first/replay/increment、实际首分页游标捕获和 Java final 全链路均有真实回读证据。

## 保留责任与读取范围

D100 采用 `retained_compatibility`。实际 Python caller 的 native 分支直接查询 `v_retail_sentiment_daily`，其父对象是 D098 的 `mv_retail_sentiment_daily_v1`；非 native read-through 分支仍调用原 `MarketBarometerReadThroughCache`。没有完成旧调用方切换或删除证据，因此保留 Python `_publish` 为缓存数据和 coverage receipt 的唯一发布责任。

Java 提供历史缓存的 typed READ，Dataset 仅登记 READ；不新增缓存 writer、job 或 receipt publisher。D098 的 canonical job 继续负责原生 MV 刷新。读取已存缓存行并不自动证明 coverage 已发布、当前来源完整、缓存命中或生产“最新”。实际目录已登记 49 个 Dataset、39 个 job，缓存 Java job 为 0。

## 十四列映射

完整业务键和物理 DEDUP KEY 均为 `(trade_date, source_version)`；TABLE/MONTH/WAL，主时间列 `trade_date`。日期映射为 `LocalDate`，TIMESTAMP 必须是 UTC 精确午夜的业务日载体；日期不会转换为上海时区或截断非法时刻。`source_version` 必须为完整小写 64 位十六进制 SHA-256。

字段为日期、8 个可空 `Double`、4 个必填 `long`、版本 `String`。实际 Python cache model 的四个计数是必填 `int`，发布前 `BaseDataModel.prepare_questdb_dataframe` 逐行 `model_validate`；这与 D098/D099 可空 SUM 输出不同。Java 遇到历史非法 NULL 计数显式失败，不改成 0。非空计数必须非负且使用精确 LONG，拒绝溢出后负值和错误数字类型；Double 保留 null、符号和存储位值，拒绝非有限数。

金额单位为亿元，原 SQL 从元除以 `100000000.0` 一次，mapper 不再缩放。净流入可为负，熵单位为 bit；比例、相对攻击性和 MFI 沿实际来源量纲，不增加未经证明的 0..1 限制。`total_manipulation_count` 沿用 `sum(fake_support_count + fake_pressure_count)`：任一操作数 null 时该行表达式为 null，不做 COALESCE、不拆成两个 SUM；最终 NULL 不能被必填计数缓存模型发布。

全十四列、原 SQL、模型与 publisher 的核实行号见 [mapping-contract-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/mapping-contract-20261006.json)。金额与底层特征含义另见 [D098 映射证据](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/mapping-contract-20261006.json)。生成的 `domain.table.RetailSentimentDailyCacheRow` 未手改。

## 版本与分页

行内业务 SHA 使用原 Python `_versions`：产品 SQL identity、真实源 table id、各日相关 partition payload。Java 不另造业务版本，也不使用全局 WAL txn 替换它。

实际分页版本由共享 reader 从缓存物理 table id/directory/txn 和已结清的 WAL frontier 生成，读取前后核查一致。`page.sourceVersion` 和 cursor 的 `sourceVersion` 是物理版本，`row.sourceVersion` 是业务 generation；显式版本过滤使用完整 SHA。页间 source version 或查询范围变化必须拒绝，WAL pending/suspended 不能返回成功页。

Repository 支持完整键 `findKey`、显式 `findVersion`、同一日期的所有历史版本 `findForDate`、有限递增 `[from,to)` 范围和可选明确版本过滤，不猜测 latest。通用页预算是 1..10000；本次实际样例固定 `[2026-09-17,2026-09-22)`，每页 1 行。

```java
var page = repository.findRange(LocalDate.of(2026, 9, 17),
        LocalDate.of(2026, 9, 22), 1, null);
var next = page.nextCursor(); // 仅沿同一查询及实际物理版本继续
var exact = repository.findVersion(date, fullLowercaseSha256);
```

## 隔离验收流程与当前进度

复用 D098 私有 QuestDB `127.0.0.1:19010/18822`、数据根 `var/d098-isolated-questdb`。原进程退出后的恢复证据单独保留；恢复使用同一数据根，源 frontier 仍为 `9:36`、真实源 23773 行、父 MV id11、alias id12。Java Port 在读取前后重新核对实际 listener PID、root 和 fixture marker，不固定旧 PID37904。

夹具仅包含正式 L2 110 个物理列中聚合需要的 15 个真实列与完整 `(ts,symbol)` 键。三日为 2026-09-17、09-18、09-21。D100 对冻结基表的聚合和缓存 generation 作验证，不认证 D086/provider/full symbol universe 就绪。D098 原生任务的 31 日/200000 源行预算仍保留；D100 样例限定上述五个日历日、23773 行来源和 3 个缓存完整键，私有 cache 全量 census 上限 200 行。

实际流程按以下顺序完成；首次失败及显式恢复证据均保留：

1. 原 Python `_source_state`、`_versions`、`_publish` 发布 9/17 和 9/18。只允许原 cache/coverage 两个模型，缺表时按原 schema 建表，每次写前重新 attestation；UNKNOWN ACK 不重放。
2. `D100_CAPTURE_CURSOR=true` 运行独立 `RetailSentimentDailyCacheLiveCursorCaptureTest`。实际 repository 第一次读取 pageSize1，保存真实返回的 `nextCursor`、十四字段、物理 snapshot、查询、reader fingerprint 原 payload/SHA 和不可变 first artifact SHA。
3. Python continue 严格绑定实际 Java capture 和旧物理版本，重放同两日 generation，再发布真实 9/21。不更新 source/MV/alias；完整键值与原 digest 验证后才确认 coverage。
4. `D100_LIVE_READ=true` 运行 `RetailSentimentDailyCacheLiveAcceptanceTest`。加载真实旧游标，重新核对查询 fingerprint，证明物理版本变化使旧游标在 row query 前拒绝；再按三个完整键和三页读取全部 42 个字段。Java expected 是原 Python publisher 保存的权威 PGWire binary64：日期/SHA/计数/null 精确，八 DOUBLE 与 typed JDBC、独立实际 JDBC storage 回读的 raw bits 均须严格相等，零容差。Java 输出 `reference_*` 比较证据；QWP 显示及舍入与 PG 的比较由 Python 另录，不作为 Java numerical reference。
5. 使用实际 `ReadGroupConfiguration`、strict request JSON 和业务 DTO 读取；取消返回无 page，非法版本/未知属性/坏 SHA 在 JDBC 前拒绝，WRITE/两种 replacement 均拒绝。源/MV/cache snapshot 前后保持。

两个 Java live 测试均只读数据库，只写自身 workspace evidence。发布由原 Python owner 完成；不在正式库建表、更新来源或执行 FULL。已有三日源夹具不 reset/truncate、不重复执行 D098 初始化材料化测试。

首次 first 在写入数据前失败：两条建表 DDL 已 ACK，`tables().table_row_count` 对空 cache 返回 null，旧 guard 因 `cache_rows=null` 拒绝进入 publisher；owner ILP intent 为 0。失败原路径 `cache-isolated-first-20261006.json` 保留。只读 recovery audit 实际 `SELECT COUNT` 和全 14+5 列均为空、真实物理/WAL已结清、source/MV/alias/formal未变，保留原 null metadata。协调者据此显式准入新 first，使用新路径 `cache-isolated-first-resume-20261006.json`；不是未知写自动重放，没有 reset 和新的 DDL。

实际新 first 已通过：两键、cache2+coverage2 原 owner flush ACK 和 PG 字段/digest一致。真实 Java capture 检查 14 字段、8 个 PG/JDBC Double 位值、查询 payload/SHA，绑定不可变 accepted first SHA `707aeb85e01a02ab3237f63c790a210da7533c7f73cf3bdf0d6d773684f51f5c`。真实旧游标为 table13/cache~13/txn1/seq1，capture SHA `83618e659cb945c240a3991779c3fcd274d315630485adf3e950b19d11109ab0`。

原 publisher continue 已通过 first2 → replay2 → increment1，实际完整键 rows2 → 2 → 3、table txn/WAL seq1 → 2 → 3。原 owner 总提交 cache5+coverage5，均 ACK；这记录提交数量，不冒充物理 inserted/updated 计数。最终 PG 权威 42 字段及原 digest 一致，QWP24个 DOUBLE 也观察到与 PG 位值相同，来源 `9:36`、MV11、alias12 和正式元数据保持。

Java final 已通过三个完整键、三页和 **42 个唯一字段值** 对照。完整键和 range 两条读取路径共 84 次字段比较，PG reference raw-bit 与独立 JDBC storage raw-bit 比较各 48 次，即 24 个唯一 Double 值各读两次；没有容差比较。真实旧游标 txn1/seq1 在最终 txn3/seq3 下于 row query 前拒绝。实际 typed ReadGroup、取消无 page、坏 JSON/业务 SHA 在 JDBC 前拒绝、WRITE/replacement 拒绝均通过；Java snapshot 前后相同，publisher invocation、数据库写入、正式写入均为 0。

## 实际证据索引

| 文件 | 范围或当前状态 |
| --- | --- |
| [mapping-contract-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/mapping-contract-20261006.json) | 源码及 Java 十四列契约；不是 live receipt |
| [commands/java-dataset-registration-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/java-dataset-registration-20261006.json) | 已取得真实 Dataset catalog49 |
| [commands/java-job-registration-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/java-job-registration-20261006.json) | 已取得 job catalog39、缓存 job0、Python唯一 publisher |
| [commands/cache-readonly-audit-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/cache-readonly-audit-20261006.json) | 已取得正式 SELECT only：五个既存 generation、70 字段回读及 5 个原 digest 一致；未认证 current hit/latest |
| [commands/private-server-restart-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/private-server-restart-20261006.json) | 同数据根的实际进程恢复及所有权证明 |
| [commands/private-server-restart-state-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/private-server-restart-state-20261006.json) | 恢复后真实源/MV/alias 状态，无数据 reset |
| [commands/private-server-restart-runtime-module-failure-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/private-server-restart-runtime-module-failure-20261006.json) | 保留第一次错误 JVM options 启动失败诊断 |
| [commands/java-unit-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/java-unit-20261006.json) | 已通过 14 类124 个 Java unit/read regression，0 failure/error/skip |
| [commands/python-script-guards-resume-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/python-script-guards-resume-20261006.json) | 已通过35项 pure mock 护栏；初始31项历史结果不双算 |
| [commands/cache-isolated-first-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/cache-isolated-first-20261006.json) | 保留 FAILED：2DDL ACK/0 owner intents，空表 null metadata guard拒绝 |
| [commands/cache-isolated-first-recovery-readonly-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/cache-isolated-first-recovery-readonly-20261006.json) | 实际 COUNT0和完整14+5列空表、schema/WAL/source/MV/alias/formal稳定的 SELECT-only 恢复审计 |
| [coordinator-empty-ddl-recovery-review-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/coordinator-empty-ddl-recovery-review-20261006.json) | 显式准入已存在且实际为空的已ACK DDL目标，不自动重试旧失败输出 |
| [commands/cache-isolated-first-resume-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/cache-isolated-first-resume-20261006.json) | 已通过原owner first2，accepted FIRST新固定路径；0新DDL |
| [commands/java-cache-cursor-first-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/java-cache-cursor-first-20261006.json) | 已通过实际 Java 首页游标、14字段和8PGbits，真实 first SHA/queryFingerprint/payload检查 |
| [commands/java-cursor-capture-tests-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/java-cursor-capture-tests-20261006.json) | 实际capture1用例passed，0 failure/error/skip及独立Python fingerprint绑定检查 |
| [commands/cache-isolated-acceptance-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/cache-isolated-acceptance-20261006.json) | 已通过原owner replay2+increment1，3完整键/42PG字段/原digest；固定first与capture SHA |
| [commands/java-cache-read-acceptance-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/java-cache-read-acceptance-20261006.json) | 已通过3页/42唯一字段、84次key/range比较、各48次PG/JDBC bits、真实旧游标before-row-query拒绝及group/取消/只读证明 |
| [commands/java-cache-live-tests-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/java-cache-live-tests-20261006.json) | 实际final1用例passed，0 failure/error/skip |
| [commands/target-tests-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/target-tests-20261006.json) | 126唯一Java用例汇总，无重复运行或skip累加 |
| [commands/read-group-request.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D100/commands/read-group-request.json) | live测试生成并由实际strict parser读取的五日全14列/pageSize3/30秒请求 |

## 实现与测试文件

- [业务 DTO](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/domain/RetailSentimentDailyCache.java)、[完整 Key](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/domain/RetailSentimentDailyCacheKey.java)、[Dataset](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/domain/RetailSentimentDailyCacheDataset.java)。
- [mapper](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/mapper/RetailSentimentDailyCacheMapper.java)、[repository](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/repository/RetailSentimentDailyCacheReadRepository.java)。
- [MappingTest](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/test/java/com/zoutrankil/data/mapper/RetailSentimentDailyCacheMappingTest.java)：11 个字段、单位、null、数值、完整键、日期载体、只读契约和查询边界用例。
- [LiveCursorCaptureTest](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/test/java/com/zoutrankil/data/config/RetailSentimentDailyCacheLiveCursorCaptureTest.java)、[LiveAcceptanceTest](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/test/java/com/zoutrankil/data/config/RetailSentimentDailyCacheLiveAcceptanceTest.java)：协调者分阶段启用；作者未运行 Gradle 或实际数据库操作。

只读目录入口示例：

```powershell
.\gradlew.bat run --args='show-dataset-definitions'
.\gradlew.bat run --args='list-sync-jobs'
```

实际隔离 generation integrity 和历史读取已验证。完整 task result、implementation/data validation 状态与独立 serial gate 由协调者维护；本文不代填用户人工复核或宣称正式来源就绪。
