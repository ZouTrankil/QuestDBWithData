# D101 · etf_market_overview_daily_cache

状态：**verified，协调器 accepted_for_serial_progress**。人工复核仍为 pending_review；按序准许 D102。

当前 Python caller 仍使用 ETF 读穿缓存。Java 提供五列 typed READ，单数据管理任务与 typed WriteGroup 已实现委托原 `MarketBarometerReadThroughCache.read()`。缓存及 D094 coverage 保持同一原 publisher；写入前必须可核验 SQLite SUBMITTED 的真实持久记录。

完整键 `(trade_date, source_version)`，MONTH/WAL/DEDUP。计数为必填非负 LONG；两个总量为可空有限 Double，分别保留万份和亿元；日期载体必须为 UTC 精确午夜。ETF basic 是全局 timeless 版本依赖，不进入业务 JOIN，也不按类型、市场或代码后缀过滤。

正式库只读诊断：5 既存代次中 3 一致、2 摘要不一致，原失败证据保留；没有修复正式库，没有认证当前命中或 latest。实际来源选定 9/17、9/18、9/21，含 daily 6412、share 2297、全部 basic 2958 个完整真实键。保留一条未匹配 share 记录的 INNER JOIN 排除语义。

D101 专用隔离服务固定 `var/d101-isolated-questdb`、PID 23388、QWP 19020、PG 18832，启动时已只读确认空库。初始来源夹具已通过：share 1532、daily 4274、basic 2958，合计 8764 真实行、136072 全字段位值核对；5 DDL 和 19 来源 INSERT 全部 ACK。新身份 FIRST 实际 2miss/4ACK、cache及coverage各2行，完整五列、回执、原PG double位值及真实txn2游标验证通过。增量将重验 bootstrap anchor 到请求结束的完整前缀，最多 31 天；来源年度分区及 timeless basic 世代不能被日期 checkpoint 跳过。

首批 77 个唯一 Java 映射/reader/持久提交回归用例通过；来源夹具 20 个纯测试通过（包含前16个），增量前拒绝活动/UNKNOWN任务及保留锁。先前沙箱临时目录清理权限错误日志独立保留，不计为业务验收。

canonical 管理账本测试现为23项（包含多日UNKNOWN边界），网关/端口64项、prepared write8项通过；184唯一Java纯测与6个现场方法（进程预览、显式只读恢复、FIRST/HIT/增量/typed）共190唯一Java通过；Python75项为桥43+夹具32，旧20项是子集。typed 写入测试初次依赖和Mockito桩失败原日志及XML保留。四个canonical现场阶段及最终独立证据复核均通过，协调结论已登记。

QuestDB `tables()` 混合 TIMESTAMP/TIMESTAMP_NS 元数据的三个日期字段有错误载体；保留原始失败预览及只读SQL诊断。桥接只在原 `SELECT * FROM tables()` 的这三个元数据字段使用 LONG 载体并按已核验的主时间类型解码，原业务SQL、partition状态与版本算法不变。适配后的只读预览通过。空目标 `table_txn=null` 保留原始值，桥接与 Java 使用独立实际 COUNT0 与 WAL0 证明；来源 txn 仍必须非空。

首次现场前，发布协议/管理/映射/注册/组合测试97项通过，Python桥接43项通过；测试依赖与Mockito桩问题的失败日志保留。2026-10-06首次 Java 验收在只读预览后停止：Windows虚拟环境启动器PID15328与实际Python桥PID15144不同，进程身份守卫拒绝。这是 known prepublication failure：owner_invoked=false、没有intent/claim/canonical ledger、没有owner ACK/DDL/源写入。缓存和回执的预览 COUNT 前后均为0，原始目标 txn/row_count 的 null 与 WAL0 保留；五表 after 状态来自 PREVIEW_VERIFIED 的稳定性校验，不声称独立 postfailure SQL。随后权威CIM确认两PID均已结束、既有私有库两监听仍属于PID23388。失败JSON、日志、XML和SHA均保留在 [首次失败复核](commands/coordinator-first-readonly-failure-review-20261006.json)。

Windows启动器身份问题已修复：实际桥进程必须匹配观测到的子进程OS启动时间和完整父链，所有观测进程均须有退出证明；未知身份不能按PID猜测终止，旧wrapper-only标记不能用于证明新的发布已停止。修复通过实际只读预览、FIRST及独立数据复核。HIT 随后发现已退出 PID 的晚采样 UNKNOWN 重复身份边界，未来采样已修复并提供严格显式调查；当时 D101 保持 **in_progress**。

HIT 原 run `d101-1be2292a-c0b0-4810-a22b-9747bef68071` 仅实际调用首日 owner，hit1/miss0，cache及coverage提交0，第二日未提交。两个已完整观测且已退出PID被晚采样重复追加为UNKNOWN，当时Java保留IN_DOUBT与1全表lease。旧失败文件均保留；当时保存的CIM记录证明7个unique PID全不存在和监听未变，但不能凭此布尔直接解锁。显式恢复须生成独立进程调查证据、fresh回读两日完整来源/缓存/回执/版本/WAL，再由已有管理reconcile核验原SLICE。即使恢复成功，此run仍只算1实际unit，不能记成HIT2通过；之后须新run完整HIT2，才准许第三日来源追加。

多日请求发生 UNKNOWN 时，剩余日期未提交并不等于其当前完整缓存和回执不存在。若任一日期缺少当前世代的完整缓存/回执，或完整来源、版本、WAL及停止生产进程的证明不足，只读 reconcile 必须 fail closed，保留 **IN_DOUBT** 与 **allDates lease**。若已有发布覆盖完整冻结窗口，则显式调查之后仍须 fresh 全窗口逐日核验来源、完整键、全部五列缓存及回执、同世代和稳定WAL；通过后，已有Service才可更新SQLite结果并释放lease。这是显式恢复，禁止自动 resend，不宣称自动恢复能力。

本次两日窗口已有FIRST存储的完整当前发布，但不能仅凭历史FIRST记录或首日匹配完成恢复。实际显式恢复已执行并独立复核：fresh两日READ核对80字段、16个PG参考和8个独立JDBC双精度原位值；RUN/ATTEMPT证明2个已匹配发布单位，原实际SLICE仍仅记录1个提交单位，status units仍为1，0保留lease，0新owner调用、0缓存/回执提交。旧失败文件和false进程标记SHA未变；未补造第二日SUBMITTED或第二次owner hit，不将恢复记为HIT2成功。独立新HIT2、第三日来源增量、Java完整前缀增量及typed断言WRITE均通过；最终协调gate已接受串行推进D102。缺少第二日完整实际发布的情形仍须拒绝并保锁。正式库仍只有只读诊断，5个既存代次的3项一致和2项摘要不一致均保留，正式写入为0。

## 有界请求、管理 CLI 与 typed JSON 示例

以下只说明调用格式，未执行这些命令。本次已完成的夹具和原 ledger 不应重跑验收；来源世代变化后须重新 plan。管理窗口的 `--from`/`--to` 均含当日，最多 31 个日历日、31 个发布单位，每次来源预览最多 50000 行。INCREMENTAL 始终从显式 bootstrap anchor 重验完整前缀，checkpoint 不跳过旧日。

### 专用隔离配置

在环境变量或独立 Spring 配置文件中设置下表，凭据沿用已授权的本地配置。不能把这些 Spring 配置项追加到业务 CLI 参数中；CLI 只接受其明确列出的选项。

| 配置项 | 本次隔离值 |
| --- | --- |
| `app.questdb.host` / `app.questdb.pg-port` / `app.questdb.qwp-port` | `127.0.0.1` / `18832` / `19020` |
| `app.questdb.database` | `qdb` |
| `app.sync.ledger-path` | `C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/d101-java-45e5e7ce-b273-4510-8d15-e1abe5397550.sqlite3` |
| `app.sync.etf-market-overview-cache.python-executable` | `D:/work/fund_2/back-monitor/.venv/Scripts/python.exe` |
| `app.sync.etf-market-overview-cache.bridge-script` | `tools/d101_etf_cache_owner_bridge.py` |
| `app.sync.etf-market-overview-cache.artifact-root` | `artifacts/java-migration/D101/commands/java-owner-bridge` |
| `app.sync.etf-market-overview-cache.private-root` / `.expected-pid` | `var/d101-isolated-questdb` / `23388`（完整前缀仍为 `app.sync.etf-market-overview-cache`） |
| `app.sync.etf-market-overview-cache.process-timeout-seconds` / `.max-source-rows` | `90` / `50000`（同一完整前缀） |

PID 必须与当前 OS 启动身份、私有 root 和监听端口的实际证明一致；重启后不能照抄旧 PID。默认 PID0 拒绝 owner 操作。此处不配置正式发布目标。

### Plan / run / status / cancel / resume / reconcile

以下有限新请求用法需对应夹具和操作准入，不能作为自动重复运行本次现场的脚本：

```powershell
.\gradlew.bat run --args='plan-etf-market-overview-cache-job --from=2026-09-17 --to=2026-09-21 --logical-date=2026-09-22 --mode=INCREMENTAL'
.\gradlew.bat run --args='run-etf-market-overview-cache-job --from=2026-09-17 --to=2026-09-21 --logical-date=2026-09-22 --mode=INCREMENTAL'
# 有界 SELECT-only 全窗口核验；不调用原 publisher。
.\gradlew.bat run --args='run-etf-market-overview-cache-job --from=2026-09-17 --to=2026-09-21 --logical-date=2026-09-22 --mode=RECONCILE'
# 本次实际 FIRST / 新 HIT 的只读 status。
.\gradlew.bat run --args='etf-market-overview-cache-job-status --run=d101-3a2751d4-f3fa-473d-b5b7-bca65bfe39cd'
.\gradlew.bat run --args='etf-market-overview-cache-job-status --run=d101-66903d41-b564-4c6e-8a7d-f7b8422bad88'
```

下列 `<...>` 必须换为同一专属 ledger 的实际 runId，各命令按状态单独使用：

```powershell
.\gradlew.bat run --args='cancel-etf-market-overview-cache-run --run=<active-runId>'
.\gradlew.bat run --args='resume-etf-market-overview-cache-run --run=<FAILED-or-CANCELLED-or-PARTIAL-runId>'
.\gradlew.bat run --args='reconcile-etf-market-overview-cache-run --run=<IN_DOUBT-runId> --writer-stopped=true'
```

resume 恢复原冻结请求，重新核验来源和目标，不能用于 VERIFIED 或 IN_DOUBT。`--writer-stopped=true` 请求实际进程停止证明核验，不替代证明；显式 reconcile 还须 fresh 全冻结窗口来源/缓存/回执/版本/WAL 完整匹配，否则保留 IN_DOUBT 与全表 lease。cancel 记录取消请求，不证明已提交 publisher 停止。

### Typed READ JSON

单成员、有界日期范围示例；保存为独立请求文件。READ 的 `toExclusive` 不含 9/22，返回保存的完整代次键，不宣称 current hit。游标采用实际物理版本，不能用业务 `source_version` SHA 替代。

```json
{
  "timeoutMillis": 30000,
  "members": [{
    "memberId": "cache",
    "datasetId": "etf_market_overview_daily_cache",
    "definitionVersion": 1,
    "query": {
      "columns": ["trade_date", "etf_count", "total_share", "total_size_yi", "source_version"],
      "equalities": {},
      "rangeColumn": "trade_date",
      "fromInclusive": "2026-09-17",
      "toExclusive": "2026-09-22",
      "pageSize": 1,
      "cursor": null
    }
  }]
}
```

实际严格 JSON 示例为 [FIRST READ 请求](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D101/commands/strictread-group-request-D101-first.json) 和 [新 HIT READ 请求](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D101/commands/strictread-group-request-D101-hit.json)；其中还含 D094 的只读 receipt 成员。命令一次读取有限 page，后续页须带返回的真实 cursor 并保持同一查询：

```powershell
.\gradlew.bat run --args='read-dataset-group --request=artifacts/java-migration/D101/commands/strictread-group-request-D101-hit.json'
```

### Typed WRITE JSON

以下五列对象直接保留 FIRST 的完整十进制值，展示 `members[0].rows` 的历史结构；来源修订后其旧 SHA 必须拒绝，不能拿此片段执行当前 WRITE：

```json
{
  "trade_date": "2026-09-17",
  "etf_count": 765,
  "total_share": 117458005.02090001,
  "total_size_yi": 16500.887115112106,
  "source_version": "c61e15b6474623ef4a8b25be1b81bf1c3367fd1da2754fbcdf497b86756c6cc1"
}
```

WRITE 的完整实际 JSON 由 typed 阶段生成在 [strictwrite-group-request-D101-typed-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D101/commands/strictwrite-group-request-D101-typed-20261006.json)。该文件已由通过的现场阶段产出；可查看冻结的原 JSON，不手抄 SHA、不重新格式化 binary64 值：

```powershell
Get-Content -Raw -LiteralPath 'artifacts/java-migration/D101/commands/strictwrite-group-request-D101-typed-20261006.json'
.\gradlew.bat run --args='write-dataset-group --request=artifacts/java-migration/D101/commands/strictwrite-group-request-D101-typed-20261006.json'
```

其严格根属性仅 `batchId`、`logicalDate`、`members`；单成员仅 `memberId`、`datasetId`、`definitionVersion`、`batchId`、`rows`，dataset 为 `etf_market_overview_daily_cache`、version1。`rows` 是 1..31 个不同日期的完整五列对象，跨度最多31日，日期使用 `YYYY-MM-DD`，LONG 非负必填，两个 DOUBLE 可空且有限，SHA 为小写64位。请求不含 mode；已注册任务冻结为 INGEST。调用者每行必须与 fresh 原 owner 当前世代五列逐值一致，任意伪造值、旧代次、replacement 或独立 D094 receipt writer 均拒绝。匹配后仍委托原 Python owner，实际 hit 时缓存/回执提交均为0。该请求 batch 已执行时应查看原组合结果；新的授权操作使用新 batch 身份和当前精确值，不能把原请求当成自动重试脚本。

### 实际证据与专属账本

| 阶段 | 输出与范围 |
| --- | --- |
| FIRST | [java-readthrough-first-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D101/commands/java-readthrough-first-20261006.json)：实际两日 miss / 4 ACK |
| 新 HIT | [java-readthrough-hit-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D101/commands/java-readthrough-hit-20261006.json)：独立两日 hit / 0缓存及回执提交 |
| 完整前缀增量 | [java-readthrough-increment-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D101/commands/java-readthrough-increment-20261006.json)：实际阶段已通过，详见冻结输出 |
| typed WRITE | [java-readthrough-typed-write-20261006.json](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D101/commands/java-readthrough-typed-write-20261006.json)：实际阶段已通过，详见冻结输出 |
| 实际 SQLite | [d101-java-45e5e7ce-b273-4510-8d15-e1abe5397550.sqlite3](C:/Users/zouqiang/IdeaProjects/QuestDBWithData/var/d101-java-45e5e7ce-b273-4510-8d15-e1abe5397550.sqlite3)：FIRST、新 HIT 和后续阶段共用的专属账本 |

## 最终隔离数据核对

真实来源初始8764行，加9/21的2903行/7ACK，合计11667行/164180完整字段值匹配。FIRST两日2miss/4ACK；新HIT两日2hit/0发布；YEAR来源修订后全前缀3miss/6ACK，cache与coverage各5个历史键、25个字段值及5个原digest匹配，PG参考与独立JDBC各20个double原位比较、零容差。真实旧游标txn2在新txn5前拒绝，checkpoint9/18→9/21。配置typed组合3实际hit/0发布，四类错误均在创建新run或调用owner前拒绝；0保留lease。正常ACK账本proof的writerStopped=false默认值如实保留，实际停写由独立Gateway进程证据证明。未现场验证同键源值更正、全历史、正式current freshness或生产切换；正式5既存代次的3一致/2摘要异常保持原样。

最终准入记录：[coordinator-review-20261006.json](coordinator-review-20261006.json)。接受本卡隔离迁移交付，D102 可按序开始；正式库历史摘要异常、全历史/生产当前有效性及同键源值修订现场限制保留，人工复核 pending_review。
