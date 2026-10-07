# D099 · v_retail_sentiment_daily 证据索引

**实际读取验收状态：VERIFIED_ISOLATED_READ。** 90 个唯一 Java 用例和 23 个 Python 纯 mock 护栏均无失败、错误或跳过。实际三页39字段 alias-parent 精确一致，39字段 alias-base 对照通过；Python三方对照共117字段一致。Dataset48/job39，刷新仍由D098唯一canonical job负责。人工复核保持 `pending_review`，正式父MV仍invalid，正式0写。

## 数据契约

普通 alias 的唯一允许绑定为 `SELECT * FROM mv_retail_sentiment_daily_v1`。自然业务键为 `trade_date`，Java 类型 `LocalDate`，存储 TIMESTAMP 必须是 UTC 精确午夜的 calendar-day 载体。生成 projection 保持不手改，独立业务 DTO、Dataset、mapper 和 repository 提供显式绑定。

13 列与 D098 的顺序、类型和 null 完全一致：日期、8 个可空 Double、4 个可空 Long。金额已由父 MV 的 SQL 从元除以 `100000000.0` 转为亿元，alias 不再次缩放；熵为 bit；比例、相对攻击性和 MFI 保持来源量纲，不增加 0..1 限制。计数 null 与 0 不同，非空 Long 必须非负且精确，Double 必须有限。

`total_manipulation_count` 沿用 `sum(fake_support_count + fake_pressure_count)`。任一操作数为 null 时该行表达式为 null，不改成 COALESCE，也不拆成两个 SUM。逐列单位、SQL 语义和核实的 Python 调用行号见 [mapping-contract-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/mapping-contract-20261006.json)；上游金额和特征含义沿用 [D098 映射证据](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D098/mapping-contract-20261006.json)。

普通 VIEW 没有独立物理分区、WAL 或 UPSERT KEY：定义为 VIEW/NONE、`wal=false`、无 DEDUP，仅 READ，直接 WRITE、STATIC_REPLACE、WAL_REPLACE 均拒绝。卡片旧快照所列 WAL 不能作为普通 alias 的独立写入能力。父 MV 仍为 MONTH/WAL，其基表更新和原生刷新由 `data.mv_retail_sentiment_daily_v1` 的 D098 job 处理。

## 实现与实际调用

- [业务 DTO](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/domain/RetailSentimentDailyView.java)、[Dataset](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/domain/RetailSentimentDailyViewDataset.java)：所有 sourceName 显式指向 `mv_retail_sentiment_daily_v1.<field>`，依赖只登记父 MV。
- [mapper](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/mapper/RetailSentimentDailyViewMapper.java)、[bounded repository](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/repository/RetailSentimentDailyViewReadRepository.java)：完整日期等值键、显式全列、有限递增 `[from,to)` 范围及游标分页。
- [映射与边界测试](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/test/java/com/zoutrankil/data/mapper/RetailSentimentDailyViewMappingTest.java)：11 个用例覆盖 13 列往返、null/零、单位/符号、Long 边界、非有限 Double、日期载体、缺列/错误类型、VIEW 契约、父子值一致和查询预算。
- [实际读取测试](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/test/java/com/zoutrankil/data/config/RetailSentimentDailyViewLiveReadAcceptanceTest.java)：仅 `D099_LIVE_READ=true` 启用，使用实际 `ReadGroupConfiguration` 和 strict JSON parser；启用后目标缺失或前置不满足必须失败。
- Python 原调用从 `MarketBarometerService.get_retail_sentiment()` 到 `QuestDbMarketBarometerRepository.fetch_view()`，native 分支查询普通 alias。Java 保留字段语义，同时显式拒绝失败或 invalid 状态，不把异常转换为空的成功结果。

`QuestDbBoundedReader` 负责 alias 精确 SQL、父 MV valid/caughtUp、基表和 MV 的物理/WAL 版本检查。caller 的 `sourceVersion` 保持 null，由实际 metadata 生成分页版本；业务 DTO 不伪造刷新状态。ReadGroup 注册依赖链为 alias → MV → L2 → manifest → calendar，验收只读取 alias 和父 MV，不执行私有 15 列源的完整 110 列 typed read。

## 实际验收范围

复用已经被 D098 验证的私有 QuestDB：`127.0.0.1:19010/18822`，数据根 `var/d098-isolated-questdb`，监听进程 PID 37904。测试先后通过 D098 Port 的私有进程/root/fixture attestation，只执行 SELECT，不建 alias、不更新源、不提交原生刷新。

冻结源 frontier 为 `9:36`，父 MV id 为 11，来源 23773 行。业务日为 2026-09-17、09-18、09-21，alias 共 3 行；页大小 1，共 3 页。实际核对通过：

- alias 与 typed 父 MV 的 **39 个字段值精确一致**，包含日期、可空 Long、Double 位值和 null。
- alias 与直接冻结基表聚合的 **39 个字段值一致**：日期和 Long 精确，Double 使用 `atol=1e-8, rtol=1e-10`，null 显式一致。
- 同一实际物理版本的三页游标、完整唯一日期键、按键读取和 closed Sunday 的空结果。
- alias SQL/13 列 schema、源/MV snapshot 前后保持；configured typed ReadGroup 一致，取消返回 CANCELLED 且无 page；直接写与 replacement 拒绝。
- 正式 alias 保持绑定到正式 invalid MV：typed read 拒绝，实际 ReadGroup FAILED/IllegalStateException 且无 page，正式 alias 与源/MV 物理/WAL 元数据前后不变。正式写入为 0，不修复正式 MV。

夹具仍只包含正式 L2 110 个物理列中该 MV 所需的 15 列，来源是实际完整 `(ts,symbol)` 键和真实字段值。本项证明相对该冻结基表的 MV/alias parity，不认证 D086/provider/full symbol universe 就绪，也不把私有成功当作正式数据已经恢复。

## 证据文件

| 文件 | 内容 |
| --- | --- |
| [mapping-contract-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/mapping-contract-20261006.json) | 13 列显式 parent 绑定、语义、Python 行号、准备的验收范围 |
| [commands/strictread-group-request-D099.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/strictread-group-request-D099.json) | 2026-09-17 至 09-22 exclusive、13 列、pageSize31、30 秒 request |
| [commands/java-dataset-registration-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/java-dataset-registration-20261006.json) | 实际 Dataset catalog |
| [commands/java-job-registration-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/java-job-registration-20261006.json) | 实际 job catalog，无独立 alias publisher |
| [commands/alias-readonly-audit-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/alias-readonly-audit-20261006.json) | 正式SELECT只读诊断，保留invalid父MV和缺失日期；正式目标不通过freshness验收 |
| [commands/alias-readonly-preflight-failure-column-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/alias-readonly-preflight-failure-column-20261006.json) | 已保留的脚本前置失败诊断 |
| [commands/java-alias-read-acceptance-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/java-alias-read-acceptance-20261006.json) | 实际 live 已通过：三页、alias/parent/base 全字段值、版本、元数据、group/取消/正式拒读及零写入 |

## 有界读取示例

定义和管理入口仍使用已有 catalog：

```powershell
.\gradlew.bat run --args='show-dataset-definitions'
.\gradlew.bat run --args='list-sync-jobs'
```

上述 [strict ReadGroup request](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/strictread-group-request-D099.json) 通过实际 `group.readRequest(path)` 解析，再调用 `group.read(request, cancelled)`。范围上界是 exclusive；Python 原 caller 的 `end_date <=` 与这里的显式 `[from,to)` 已在 API 中区分。

单数据分页使用同一 repository：

```java
var page = repository.findRange(LocalDate.of(2026, 9, 17),
        LocalDate.of(2026, 9, 22), 1, null);
var next = page.nextCursor(); // 后续页必须沿同一查询与实际物理版本
```

协调者在已核实 alias 安装和完整三日 D098 来源后启用 `D099_LIVE_READ=true`，运行专属 LiveReadAcceptanceTest。缺少 private alias、版本漂移、正式状态与冻结证据不符时显式失败并保留诊断。D099 不运行 D098 的初始化材料化测试，不 reset/truncate 现有 23773 行夹具。若业务确需刷新，引用现有 D098 的有限 plan/run/status/resume 流程；D099 不另建刷新 job。

实际读取结果和任务登记已完成，serial gate 经独立证据复核后记录。人工复核状态由用户决定。

最终测试见 [target-tests-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/target-tests-20261006.json)，私有alias CREATE及三方对照见 [alias-audit-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D099/commands/alias-audit-20261006.json)。DDL UNKNOWN 同输出阻止重跑、历史证据归档和字段防护共23项纯mock通过；无自动DDL重试。
