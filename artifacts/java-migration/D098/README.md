# D098 · mv_retail_sentiment_daily_v1 证据索引

**实际验收状态：VERIFIED_ISOLATED。** Java 材料化流程、实际 typed read 和任务登记已完成，独立只读复核无阻断问题。人工复核保持 `pending_review`；正式 MV 仍 invalid，正式写入为 0。

最终两轮 Java 测试分别为 116 和 161 个，去重后 **162 个用例，0 failure、0 error、0 skip**；Python 纯 mock 护栏 **31 个用例**全部通过。三次失败尝试均留档：日历检查在 ledger 创建之前失败；VERIFIED resume 被既有恢复契约拒绝；真实增量在提交前失败，随后通过实际 CLI 对原 FAILED run 恢复成功。主账本 11 runs 为 6 VERIFIED、1 VERIFIED_EMPTY、3 FAILED、1 CANCELLED，0 IN_DOUBT、0 残留锁。

## 契约与边界

- 自然业务键为 `trade_date`，粒度为 `l2_daily_features.ts` 的 `SAMPLE BY 1d ALIGN TO CALENDAR` 日桶。存储为 UTC 精确午夜 TIMESTAMP 载体，Java 使用 `LocalDate`。
- 输出按任务卡顺序包含 13 列：日期、8 个可空 Double、4 个可空 Long。金额由源元在 SQL 中除以 `100000000.0` 转为亿元，Java 不再缩放；熵单位为 bit，比例和 MFI 保持源指标量纲。
- SUM/AVG 的 null 保留，不能填成 0。操纵次数严格采用 `sum(fake_support_count + fake_pressure_count)`：任一操作数为 null 时该行表达式为 null，不能改成 COALESCE 或两个独立 SUM。整数来源核验使用精确累加防止溢出；Double 必须有限。
- MV 为 MONTH 分区、WAL、无 DEDUP/UPSERT KEY。Dataset 仅 READ，拒绝 WRITE 与 replacement；原生刷新入口为单一 `data.mv_retail_sentiment_daily_v1` job。
- 每个冻结窗口最多 **31 天**，完整有界基表来源最多 **200000 行**，聚合输出最多 31 行、单 page/单 batch、64 KiB。异步可见性等待最多 3 分钟，并受 job 5 分钟总超时约束。完整 MV 的排他锁覆盖 allDates，取数范围仍为冻结日期窗口。
- INCREMENTAL 冻结并前后核验实际 `calendar_version`，完整 SSE open sessions 必须与源日桶日期一致；源、MV 物理身份与事务版本也必须稳定。checkpoint 只由同 bootstrap、同实际 calendar_version 的连续已验证 INCREMENTAL 覆盖推进，保留 3 天有限修订窗口。
- 私有目标固定为 `127.0.0.1:19010/18822`、工作区 `var/d098-isolated-questdb`。每次实际写入前核验 Windows 监听进程、PID、`-d` 数据根、配置与 fixture marker。

完整逐列来源、Java accessor、单位、null 与已核实源码行号见 [mapping-contract-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/mapping-contract-20261006.json)。

## 来源夹具范围

正式 `l2_daily_features` 有 **110 个物理列**。D098 夹具只复制该 MV 必需的 **15 列**：完整 `(ts,symbol)` 键、8 个 DOUBLE 指标和5 个 LONG 指标。它用于相对于冻结真实基表的 MV 聚合/刷新验收，不认证 D086 的完整 110 列模型、上游 provider 或全 symbol universe。

来源范围为首次两日 **2026-09-17、2026-09-18 的 15828 行**，增量日 **2026-09-21 的 7945 行**。正式来源以 SELECT 提取，物理/WAL 元数据前后冻结；第三日私有 INSERT 后逐完整键严格核对全部 15 列，实际比较 **119175 个字段值**；合并三日全部 23773 行共 **356595 个字段值**均严格匹配。最终三个聚合业务键核对全部 13 列，共 **39 个字段值**匹配；实际 typed read 也核对 39 值。

依赖夹具包含真实 SSE 日历 **2026-09-10 至 2026-09-29 的 20 日**，包含 open 和 closed days。Java 读取组合只查询 D098 MV；注册 L2、manifest、calendar 依赖定义，不对私有 15 列源夹具执行完整 110 列 typed read。

## 证据文件

| 范围 | 文件与用途 |
| --- | --- |
| 映射 | [mapping-contract-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/mapping-contract-20261006.json)：13 列语义和源码行号 |
| 正式只读审计 | [commands/mv-readonly-audit-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/mv-readonly-audit-20261006.json)：实际 schema、物理/WAL、基表范围与正式 invalid MV 诊断 |
| 真实来源捕获 | [commands/real-source-initial-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/real-source-initial-20261006.json)、[commands/real-source-increment-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/real-source-increment-20261006.json)：有界真实 15 列输入 |
| 私有来源准备 | [commands/source-fixture-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/source-fixture-20261006.json)：初次真实执行的整阶段 ACK、完整源重复写回读、真实日历与 missing-only DDL；后续新增逐 batch UNKNOWN→ACK 持久标记仅经纯 mock 验证，未重跑初始 fixture；真实第三日的 16 batch ACK 由 Java 证据保存 |
| 进程证明 | [commands/private-server-startup-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/private-server-startup-20261006.json)：私有进程与数据根 |
| 单元 | [commands/unit-test-summary-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/unit-test-summary-20261006.json)、[commands/unit-tests-20261006.log](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/unit-tests-20261006.log) |
| 脚本护栏 | [commands/python-script-guards-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/python-script-guards-20261006.json) |
| Java 材料化流程 | [commands/java-materialize-acceptance-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/java-materialize-acceptance-20261006.json)：首次、重复、真实增量、取消与恢复、空窗口、缺失交易日、invalid 拒绝及私有 FULL 修复 |
| 实际 CLI 恢复 | [commands/java-cli-increment-resume-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/java-cli-increment-resume-20261006.json)：原 FAILED increment 的冻结请求恢复、VERIFIED3、reusedRows=0 |
| Java 实际读取 | [commands/java-mv-read-acceptance-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/java-mv-read-acceptance-20261006.json)：三页、39 值、configured typed ReadGroup、取消、正式 invalid 拒绝及稳定物理版本 |
| 最终测试 | [commands/target-tests-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/target-tests-20261006.json)：116/161 两轮，162 个唯一 Java 用例；Python 31 个护栏用例 |
| 增量提交前失败留档 | [commands/increment-preflight-failure-java-materialize-acceptance-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/increment-preflight-failure-java-materialize-acceptance-20261006.json)：原 FAILED run 保留，未提交刷新；仅记录 IllegalStateException，元数据变化是推测，非已证实异常原因 |
| 日历前置失败留档 | [commands/calendar-preflight-failure-java-materialize-acceptance-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/calendar-preflight-failure-java-materialize-acceptance-20261006.json)、[commands/calendar-preflight-failure-live-materialize-20261006.log](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/calendar-preflight-failure-live-materialize-20261006.log) |
| VERIFIED resume 拒绝留档 | [commands/verified-resume-rejected-java-materialize-acceptance-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/verified-resume-rejected-java-materialize-acceptance-20261006.json)、[commands/verified-resume-rejected-live-materialize-20261006.log](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/commands/verified-resume-rejected-live-materialize-20261006.log) |

## 对应实现与验收入口

- 类型/定义/映射：`src/main/java/com/zoutrankil/data/domain/RetailSentimentDailyV1.java`、`RetailSentimentDailyV1Dataset.java`、`mapper/RetailSentimentDailyV1Mapper.java`。生成的 `domain/materializedview/RetailSentimentDailyV1MaterializedView.java` 保持不手改。
- 读取：`repository/RetailSentimentDailyV1ReadRepository.java`，由 `QuestDbBoundedReader` 核验固定源/SQL、MV valid/caughtUp、稳定物理版本及分页游标；`ReadGroupConfiguration` 注册实际业务类型。
- Job：`repository/RetailSentimentDailyV1MaterializationPort.java`、`service/RetailSentimentDailyV1MaterializeAdapter.java`、`service/RetailSentimentDailyV1JobService.java`、`cli/RetailSentimentDailyV1Commands.java`；复用共享 runner、SQLite ledger 与 allDates exclusion。
- 实际 job 测试：`src/test/java/com/zoutrankil/data/service/RetailSentimentDailyV1LiveMaterializeAcceptanceTest.java`，初始模式仅显式 `D098_LIVE_MATERIALIZE=true` 启用；后续实际完成使用固定原失败证据及 CLI 恢复结果的 `D098_LIVE_MATERIALIZE_CONTINUE=true`。当前 source 已是 9:36，两种历史模式均不能直接重跑。
- 实际 read 测试：`src/test/java/com/zoutrankil/data/config/RetailSentimentDailyV1LiveReadAcceptanceTest.java`，仅显式 `D098_LIVE_READ=true` 启用；启用后缺少三日 23773 行或 MV 尚未 valid/caughtUp 都必须失败。
- 只读审计与来源准备：`tools/audit_d098_mv_readonly.py`、`tools/accept_d098_mv_isolated.py`。来源准备脚本不是另一个业务刷新 writer，不提交原生 REFRESH。

## 有界 canonical CLI 示例

先将启动进程明确配置到已证明的私有实例与专属 ledger。以下配置是目标条件，密码仍从已授权本地配置读取，不写入证据。

| Spring 配置项 | 私有目标值 |
| --- | --- |
| `app.questdb.host` | `127.0.0.1` |
| `app.questdb.pg-port` / `app.questdb.qwp-port` | `18822` / `19010` |
| `app.questdb.database` | `qdb` |
| `app.sync.ledger-path` | 专属、明确的私有验收 SQLite 路径 |
| `app.sync.retail-sentiment-daily.mutations-enabled` | 执行原生私有刷新时显式 `true`；默认 `false` |
| `app.sync.retail-sentiment-daily.expected-target-id` | 私有路径保持空，使用 PID/root attestation；本次不准入正式写目标 |

在完成以上私有配置的 shell 中，预览和有界执行使用同一 canonical owner：

```powershell
.\gradlew.bat run --args='plan-retail-sentiment-daily-job --from=2026-09-17 --to=2026-09-18 --logical-date=2026-10-06 --mode=INCREMENTAL'
.\gradlew.bat run --args='run-retail-sentiment-daily-job --from=2026-09-17 --to=2026-09-18 --logical-date=2026-10-06 --mode=INCREMENTAL'
.\gradlew.bat run --args='retail-sentiment-daily-job-status --run=<runId-from-result>'
```

第三日真实源已安装且核验完成后，有限增量示例为 `--from=2026-09-17 --to=2026-09-21 --logical-date=2026-10-06 --mode=INCREMENTAL`。`--from` 是显式 bootstrap anchor；owner 依据连续已验证 checkpoint 计算有限 overlap，预览显示实际冻结范围和 calendar/source/target 身份。

恢复仅用于同定义的 terminal FAILED/CANCELLED/PARTIAL run；来源/目标冻结身份必须仍成立。IN_DOUBT 必须先提供 writer 已停止的证明并做实际只读值/版本核验，不能直接重放。

```powershell
.\gradlew.bat run --args='resume-retail-sentiment-daily-run --run=<failed-or-cancelled-or-partial-runId>'
.\gradlew.bat run --args='reconcile-retail-sentiment-daily-run --run=<in-doubt-runId> --writer-stopped=true'
```

`install-retail-sentiment-daily-isolated` 仅建缺失 MV 或校验已有契约。`repair-retail-sentiment-daily-isolated` 通过同一 runner/ledger 记录 `FULL_ISOLATED` 意图，要求整个私有真实非空源在 31 天/200000 行预算内，不能把有限子区间当作完整 FULL 来源。本次正式库不执行 FULL、不修改基表、不修复正式 MV。

## 现场保留与后续验收

完整 LiveMaterialize 测试严格要求初始源只有两日 15828 行。第三日安装后私有源变成三日 23773 行，**不能自动重跑完整 live 流程或自动 reset/truncate 夹具**；再次运行应显式报 `RESET_REQUIRED`，保留现场和 ledger。当前失败/未知 ACK 也不应通过盲目重跑掩盖。

故障场景只改一个真实 `(ts,symbol)` 键的 `retail_total_amount`。若过程中失败，`finally` 在目标证明后恢复原值并实际回读，保留 FAILED 诊断，不自动 FULL 后把失败改成成功。正常的私有 FULL 恢复阶段由 canonical job 单独记录并逐键值验证。

正式 MV 当前 invalid，Java typed read 与 ReadGroup 必须拒绝并不给成功 page。私有有效 MV 验收成功也不代表正式 MV 已恢复、正式数据就绪或上游来源 universe 已认证。实际 live 和协调复核已完成，结果与 gate 已登记；人工复核由用户决定。
