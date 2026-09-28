# QuestDB View、WAL 与数据工程改造调研

本文针对当前项目（Java 命令行同步 Tushare `stock_basic`，通过 QWP 写入、PGWire/JDBC 管理和查询）整理材料化视图、WAL、重复数据治理以及 Python 到 Java 的改造建议。仓库目前没有生产行情表定义或生产 QuestDB 版本信息，因此 SQL 是验证模板；落地前先记录 `SELECT build()` / 部署版本，并在同版本实例验证功能和语法。

## 结论摘要

- **优先用 QuestDB 官方内置 SQL**：普通 `VIEW` 是查询时展开的虚拟查询；`MATERIALIZED VIEW` 是 QuestDB 内置、异步维护的持久化聚合结果；`ASOF JOIN` 也是原生时序连接。当前项目已有官方 `questdb-client`，无须再引入第三方物化视图库来做这些能力。
- **Materialized view 不是任意衍生表机制**：查询必须有 `SAMPLE BY` 或受支持的时间分组聚合，适合 K 线、分钟/日统计等重复聚合；不适合保留原始行做 1:1 字段补充。Join 右侧维表更新不会触发刷新。
- **WAL 的“已提交”与“可查询”是两个时点**：客户端成功提交后，由后台 WAL apply 异步应用。先查积压/挂起和错误，再决定 `RESUME WAL`、跳过损坏事务并重放源数据，还是备份恢复/重建。单纯重启不是通用修复；重建也不是默认最快，需看根因、数据量和源数据可重放性。
- **重复数据必须先定义业务唯一键**：QuestDB dedup 是 WAL 表的 UPSERT last-write-wins，不会清理已经存在的重复行。必须同时核对时间戳语义、dedup key、各写入端列名/类型和值。
- **Java 迁移采用一个规范 schema**：字段清单同时约束 Python Pydantic 模型、Java record、QuestDB DDL、QWP 写入器和 JDBC 行映射；字段名在 DB 中统一 snake_case，应用层可用 Java camelCase，通过明确映射转换。

## 1. View 类型和当前支持边界

| 类型 | 保存结果 | 更新方式 | 适用场景 |
| --- | --- | --- | --- |
| `VIEW` | 否，每次引用时运行定义查询 | 查询时读当前基础表 | 简化查询、复用 SQL、可接受查询开销的衍生逻辑 |
| `MATERIALIZED VIEW` | 是，保存预聚合结果 | 基表新增数据后异步增量刷新；也可定时或手动刷新 | `SAMPLE BY` 聚合、K 线、常用 dashboard 汇总 |
| `LIVE VIEW` | 是，维护流式窗口结果 | 随新输入增量处理 | 文档所述逐输入行窗口计算，如移动平均/运行累计；需按具体版本确认功能与约束 |
| 普通 QuestDB 表 + Java/SQL 作业 | 是 | 由同步/派生作业写入 | 非聚合数据整形、需要明确重算/回补控制的复杂衍生数据 |

官方 Materialized view 约束和行为：

- 定义必须包含 `SAMPLE BY` 或带 designated timestamp 的时间分组；支持的聚合/查询语法有边界。
- 默认 `REFRESH IMMEDIATE`，基表每次事务后触发增量刷新；刷新异步执行，因此读者会短暂读到旧结果。
- `EVERY` 的最小周期为 1 分钟；`MANUAL` 适合批次结束后显式刷新；`PERIOD` 适合固定间隔数据并降低频繁事务刷新开销。
- Join 物化视图需声明 `WITH BASE`；只有 base 表变化触发刷新，其他 join 表变化不触发。维表修正后要安排 refresh/backfill，或者查询时再 join。
- `UPDATE`、`TRUNCATE`、删分区，以及影响依赖的 schema 变更等会使视图失效；检查 `materialized_views()`，失效后评估 `REFRESH MATERIALIZED VIEW ... FULL`。
- 若 base 表启用了 dedup，物化视图的非聚合输出列受 UPSERT KEYS 约束；先核对 key 再设计视图。

因此建议：聚合类衍生行情先试官方物化视图；ASOF enrichment / 原始行扩列 / 需要重算整段历史的衍生输出，用普通 view 或 Java 同步作业，不要把它误当成通用物化表。

### 定义和使用的推荐流程

1. **先写出消费者查询和正确性定义**：明确输出粒度、时间列、分组 key、迟到数据窗口、允许的最大陈旧时间，以及怎样与原始数据对账。比如“一分钟一只证券一行”就明确是 `(minute_timestamp, symbol)`。
2. **按是否需要持久化选择类型**：只想复用筛选/字段投影，先建普通 `VIEW`；频繁运行且代价高的时间聚合再建 `MATERIALIZED VIEW`；逐原始行的窗口运算评估 `LIVE VIEW`；普通 SQL 不支持的逐行衍生则由 Java 作业写结果表。
3. **把 DDL 当迁移管理**：SQL 放进版本控制和部署迁移，部署前在目标 QuestDB 版本测试；记录 `SHOW CREATE VIEW` / `SHOW CREATE MATERIALIZED VIEW` 或对应 metadata。`IF NOT EXISTS` 只避免重复创建，不会更新已有定义。普通 view 可用 `CREATE OR REPLACE VIEW` / `ALTER VIEW` 更新；物化视图定义变更需按目标版本支持的 DDL规划替换和回填。
4. **按新鲜度选刷新方式**：默认 `IMMEDIATE` 便于低延迟但会增加写入后的后台刷新负载；`EVERY` 至少 1 分钟，适合接受延迟的汇总；批量导入时可用 `MANUAL`，导入结束后触发 incremental refresh；固定周期且偏重写入吞吐时评估 `PERIOD`。先测 refresh lag 与基表写入吞吐，再定策略。
5. **明确回补/重算策略**：维护 refresh limit 和迟到数据的时间范围；少量已知历史修正用 `RANGE`，视图失效或全量不一致才 `FULL`。`RANGE` 不推进增量 checkpoint。全量刷新可能很慢，维护期监控状态、耗时和资源。
6. **持续监控并对账**：观测 `view_status`、`invalidation_reason`、`base_table_txn - refresh_base_table_txn`、最近开始/结束时间。按时间窗和分组 key 对比原查询与物化结果；刷新完成以 txn 追平为准，而不是 DDL/REFRESH 请求已返回。
7. **控制资源和复杂度**：物化视图会占磁盘并消耗刷新 CPU/native memory；分区应匹配查询范围且通常不小于采样窗口。把复杂处理拆成易验证步骤，避免过多层级；官方普通 view 文档建议层级保持浅（约 3–4 层）。只有压测出现 refresh 内存/并发瓶颈后才调整专用 refresh worker 或 memory limit。

普通 view 的基本使用示例：

```sql
CREATE OR REPLACE VIEW valid_trades AS (
    SELECT timestamp, symbol, price, quantity
    FROM trades
    WHERE price > 0 AND quantity > 0
);

-- 像表一样读取；结果每次查询时重新计算，不缓存
SELECT timestamp, symbol, price
FROM valid_trades
WHERE symbol = '600000.SH'
  AND timestamp >= '2026-09-01T00:00:00Z';

-- 检查定义和状态
SHOW CREATE VIEW valid_trades;
SELECT view_name, view_status, invalidation_reason FROM views();
```

Materialized view 的基本使用示例：

```sql
CREATE MATERIALIZED VIEW IF NOT EXISTS trades_ohlc_1m
REFRESH IMMEDIATE AS (
    SELECT timestamp, symbol,
           first(price) AS open, max(price) AS high,
           min(price) AS low, last(price) AS close,
           sum(quantity) AS volume
    FROM trades
    SAMPLE BY 1m
) PARTITION BY DAY;

-- 下游直接查询已持久化的聚合结果
SELECT timestamp, symbol, open, high, low, close, volume
FROM trades_ohlc_1m
WHERE timestamp >= '2026-09-01T00:00:00Z'
  AND timestamp <  '2026-09-02T00:00:00Z';

-- 确认初始/增量刷新已追上基础表
SELECT view_name, view_status, refresh_base_table_txn, base_table_txn
FROM materialized_views()
WHERE view_name = 'trades_ohlc_1m';
```

`CREATE MATERIALIZED VIEW` 会异步进行初始全量刷新；刚创建后可能暂时没有结果。使用端如有严格新鲜度要求，应在读取前核验状态或在服务监控中暴露刷新延迟，并明确超时/降级策略。

### 在本项目 Java 代码中自动创建和使用

本项目已将建表与 latest view DDL 移至 `src/main/resources/db/migration/questdb/`，由 Spring 管理的 Flyway 通过现有 PGWire DataSource 执行。HikariCP 管理 JDBC 连接，QWP Sender 单独负责批量数据写入。

- `./gradlew run --args='migrate-questdb-schema'` 显式迁移；`create-questdb-schema` 保留为别名。
- 数据库同步与 latest 查询会先迁移，成功后再读写；CSV 和 SELECT 1 探活不迁移。
- 变更已有 view 时新增迁移文件，不修改已执行脚本。旧库接管、版本锁定和真实实例验证状态见 [Flyway 规范](flyway-schema-management.md)。

需要区分“模型/视图的业务契约”和“数据库对象的物理实现”。Java domain record 与 repository interface 是可跨数据库复用的标准契约；QuestDB DDL、`LATEST ON`、WAL、dedup、`SAMPLE BY`、ASOF 等是适配器中的方言/特性。换数据库时应保留领域 record、字段名、类型、空值约定、key 和 view 输出契约，只替换 repository adapter、DDL migration，并为新数据库实现相同语义。不要把 QuestDB 特有注解或 SQL 放进 domain model。若要求 SQL 文本完全不变，只能限制到双方共同支持的 SQL 子集；时序语义（如 last-write-wins、late-data refresh、ASOF tolerance）仍须在适配层显式实现。

当前 `stock_basic` 是快照表，所以项目实际创建的是普通 view（用标准窗口函数 `ROW_NUMBER()` 选每个代码的最新快照），而不是聚合型 materialized view。该查询语义可由其他支持窗口函数的关系数据库复用。若在 QuestDB 专用实现里优先追求时序查询性能，可以把 view SQL 换成 `LATEST ON`；这是方言优化，需要测量并保留通用实现作为语义基准。等真实分钟行情表就绪且聚合满足 QuestDB 约束后，可新增 Flyway 迁移文件定义物化视图，并确保基础表 DDL 先执行：

```java
private static final String CREATE_MINUTE_BARS_SQL = """
        CREATE MATERIALIZED VIEW IF NOT EXISTS trades_ohlc_1m
        REFRESH IMMEDIATE AS (
            SELECT timestamp, symbol,
                   first(price) AS open, max(price) AS high,
                   min(price) AS low, last(price) AS close,
                   sum(quantity) AS volume
            FROM trades
            SAMPLE BY 1m
        ) PARTITION BY DAY
        """;

public void initialize() {
    jdbcTemplate.execute(CREATE_TRADES_TABLE_SQL);
    jdbcTemplate.execute(CREATE_MINUTE_BARS_SQL);
}
```

代码创建 materialized view 后，首次全量刷新仍异步运行；`initialize()` 不会等待结果就绪。Java 可查询 `materialized_views()`，轮询到 `view_status = 'valid'` 且 `refresh_base_table_txn = base_table_txn` 再声明数据就绪。回补使用同一 PGWire 连接执行 `REFRESH MATERIALIZED VIEW ... RANGE FROM ... TO ...`；时间边界使用应用校验后的 UTC 值，避免把用户输入拼进 SQL。

### Materialized view 示例

```sql
CREATE MATERIALIZED VIEW bars_1m
WITH BASE trades REFRESH IMMEDIATE AS (
    SELECT timestamp, symbol,
           first(price) AS open,
           max(price) AS high,
           min(price) AS low,
           last(price) AS close,
           sum(quantity) AS volume
    FROM trades
    SAMPLE BY 1m
) PARTITION BY DAY;
```

具体列、分区和刷新策略必须按真实源表和业务定义调整。若分钟内有迟到数据，确定 `REFRESH LIMIT`/迟到数据回补方案；超出增量窗口的数据通过 `REFRESH MATERIALIZED VIEW ... RANGE FROM ... TO ...` 重算对应范围，或在可接受的维护窗口 full refresh。

## 2. 如何验证物化视图的性能和更新

先准备可重复的基线：固定 QuestDB 版本、硬件/配置、源数据时间范围、行数、并发、查询参数。清理或固定缓存策略后分别跑原始聚合和物化视图查询，重复多轮，比较 p50/p95/p99 延迟、扫描行数/查询计划、CPU、磁盘读写、写入吞吐、刷新延迟和存储占用。分别测冷/热查询以及并发读；也测物化视图开启前后的 base 写入吞吐和 WAL apply lag。不要把文档中的示例延迟当成本机验收指标。

验证更新正确性：

1. 新建空视图后确认初次全量刷新完成；`CREATE MATERIALIZED VIEW` 返回并不代表初次填充完成。
2. 向 base 表追加带已知时间戳和数值的批次，等待基表可见，再查看 view 的事务进度并对比原始聚合结果。
3. 同一批次重放，确认 dedup 配置下结果仍正确；测试新数据落在旧时间桶、迟到数据超出 refresh limit、base dedup 更新、维表变更等边界。
4. 在测试环境测试 `UPDATE`/`TRUNCATE`/删分区后的 view 状态以及 full/range refresh 的恢复时间。
5. 每个时间范围按 bucket+业务维度比较行数和聚合值。浮点字段用业务容差；整数/精确字段按精确相等校验。

```sql
-- 刷新状态、滞后事务数；相等表示追平 base 表事务
SELECT view_name, view_status, invalidation_reason,
       refresh_base_table_txn, base_table_txn,
       base_table_txn - refresh_base_table_txn AS lag,
       last_refresh_start_timestamp, last_refresh_finish_timestamp
FROM materialized_views()
WHERE view_name = 'bars_1m';

-- 仅在适当时间范围对比原始聚合和物化结果
SELECT timestamp, symbol, sum(quantity) AS volume
FROM trades
WHERE timestamp >= '2026-09-01T00:00:00Z'
  AND timestamp <  '2026-09-02T00:00:00Z'
SAMPLE BY 1m;

SELECT timestamp, symbol, volume
FROM bars_1m
WHERE timestamp >= '2026-09-01T00:00:00Z'
  AND timestamp <  '2026-09-02T00:00:00Z';
```

增量刷新/重建操作示例：

```sql
REFRESH MATERIALIZED VIEW bars_1m INCREMENTAL;
REFRESH MATERIALIZED VIEW bars_1m
  RANGE FROM '2026-09-01T00:00:00Z' TO '2026-09-02T00:00:00Z';
REFRESH MATERIALIZED VIEW bars_1m FULL;
```

刷新命令是异步的，执行完命令后轮询 `materialized_views()` 状态，不要将命令返回当成已经刷新完毕。

## 3. WAL 问题的定位与恢复

WAL 将提交和落入主表存储解耦。QWP flush/发送成功表示客户端已发送/提交，不等于 PGWire 查询立即可见。仓库现有 `awaitSnapshotVisibility` 的轮询设计方向正确，但建议生产核验按批次 ID/业务键统计，而非只依赖全表总数；还需对超时输出 table、batch、已提交行数、已见行数和等待时长。

先收集状态和日志：

```sql
-- 列名需按部署 QuestDB 版本核对 tables() 输出
SELECT table_name, walEnabled, wal_txn, table_txn,
       wal_txn - table_txn AS pending_txns,
       wal_pending_row_count
FROM tables()
WHERE walEnabled
ORDER BY wal_pending_row_count DESC;

SELECT name, suspended, writerTxn, sequencerTxn,
       errorTag, errorMessage
FROM wal_tables()
WHERE suspended;
```

排查顺序：

1. 确认磁盘空间、文件系统/权限、内存压力、服务日志中的首个 apply 错误；检查 `wal_tables()` 是否挂起，以及 `tables()` 的待应用 txn/row 是否持续增长。
2. 如果是磁盘满等外部条件，先修复原因，再执行 `ALTER TABLE <table> RESUME WAL;`，观察是否追平。恢复 WAL 不是盲目重试。
3. 如果日志显示 WAL segment/transaction 损坏，按官方 `wal_transactions()` 诊断找出问题事务区间；只有在确认这些数据可从 Kafka/源文件/上游接口重放时，才考虑从问题事务之后 resume 跳过损坏事务，然后按业务键补数。
4. 源数据无法重放、表本身不可用或有一致性风险时，停写并保全数据目录/备份，选择可靠备份恢复或隔离表后从权威源重建。重建通常是可控的恢复策略，但可能需要完整回灌，未必比修复挂起事务更快。
5. 验证行数、时间范围、唯一键重复、关键指标和 WAL pending 归零；不要只凭服务重启后“看起来正常”结案。

用户观察到“备份重建最快、重启常常没用”应作为具体事故经验记录，补上表名、QuestDB 版本、日志错误、数据量、重建耗时和恢复完整性。重启不会自动修正损坏事务或 apply 的根因。

### 写入模型与“列对不齐”

项目 QWP 例子通过 `sender.table(TABLE).symbol("ts_code", ...)` 等按**列名**写入，setter 调用顺序不应被当成数据库列位置映射。但以下问题确实会导致报错或错误数据：

- 字段拼写、大小写或目标列名不一致，造成漏列/写入错误列；动态 SQL 未白名单校验。
- Java 值与 QuestDB 列类型不匹配，例如把时间/数值序列化成 STRING，或把 `YYYYMMDD` 当 timestamp。
- 不同 Python、Java 写入端对 nullable、空字符串、时区、精度、枚举编码处理不一致。
- JDBC `INSERT ... VALUES` 省略列清单时依赖 schema 顺序；schema 变更后位置错位。始终声明列名。
- 时间戳单位（秒/毫秒/微秒/纳秒）、UTC/本地时区、时间舍入精度不同，造成逻辑上相同事件落成不同 key。

建议写入层统一字段名、字段类型和 timestamp 规范；启动时或部署迁移时检查 `table_columns()` 与规范 schema 对应关系。QWP 逐列用具名 setter，JDBC insert 必须列出目标列。每批写入先做字段映射/类型校验，记录被拒绝/空值转换计数。

## 4. 重复数据、主键语义和冲突检查

QuestDB 不提供传统关系库式任意主键约束。WAL 表的 `DEDUP UPSERT KEYS(...)` 用于写入时按键覆盖；designated timestamp 必须是 UPSERT key。启用 dedup 不会清理历史已有重复。确定业务语义再设 key：

- 时序事实通常是 `(event_timestamp, instrument_id, source/venue)`；加上区分独立事件的必要维度。
- 周期快照可以是 `(snapshot_timestamp, entity_id)`。当前 `stock_basic` 示例使用 `(snapshot_ts, ts_code)`，适合同一日全量重跑覆盖；如果需要保存盘中多次快照，就不能只用日期级时间戳。
- 不要把可修正的数值列、非稳定名称或接收时间放入业务唯一键；否则修正不会覆盖旧数据。
- 先区分“同一事件重放”“同一时间同一标的多条合法事件”“不同源冲突”。last-write-wins 的胜者是写入顺序最后者，若上游修订有版本/更新时间，应纳入冲突策略并确保乱序覆盖不会让旧记录赢。

```sql
-- 检查既有重复：将 timestamp / instrument / source 替换为真实业务键
SELECT timestamp, instrument, source, count() AS n
FROM market_data
GROUP BY timestamp, instrument, source
HAVING count() > 1
LIMIT 1000;

-- 查看现有 dedup 和 key 配置
SELECT dedup FROM tables() WHERE table_name = 'market_data';
SELECT "column", upsertKey FROM table_columns('market_data');
```

抽取同一个小时间窗，对 Python 写入、Java 写入、数据库最终数据按规范化 business key 做全外连接/哈希比对；重点查看字段为空率、distinct、最小/最大时间、同 key 多版本，以及同一源跨系统 key 的时区/单位差异。

## 5. Python 数据工程改造成 Java

先建立一份字段契约（可用版本控制下的 YAML/JSON Schema），再生成或校验各层模型，不要分别手工维护多套字段名：

| 契约项 | 内容 |
| --- | --- |
| 字段 | 稳定的 DB snake_case 名、Java camelCase 属性、Python 属性/alias |
| 类型 | QuestDB 类型、Java 类型、Python 类型、单位/精度 |
| 空值 | 可空与默认值规则，空字符串是否等同 null |
| 时间 | UTC、精度、事件时间/采集时间含义和 designated timestamp |
| 唯一性 | 业务 key、是否 dedup、冲突胜出规则 |
| 来源 | API 字段、转换/清洗规则、枚举值字典 |

映射建议：Pydantic 对外 API alias 明确指向规范字段；Java 用不可变 `record` 表达传输/领域值对象，并将 JSON 序列化配置（如 Jackson naming/显式 `@JsonProperty`）与 DB 命名分开；由 mapper 显式执行 API DTO → domain → QWP/JDBC 行，不靠反射字段顺序。Java 字段在编译期固定，但跨语言字段一致性仍需契约校验和样例数据比对。

工程边界建议：

1. `client`：外部源访问和响应 DTO。
2. `domain`：规范领域 record/value object 与 key。
3. `mapper`：Python/API 字段与 Java/DB schema 的明确映射、时间和枚举标准化。
4. `service`：同步批次、重试、幂等、补数和批次审计。
5. `repository`：官方 QWP 写入、PGWire 查询/DDL；所有 SQL 和 QuestDB 特有语法集中管理。
6. `derived`：Java 负责调度、依赖、回补和核验；可聚合 SQL 优先使用 QuestDB 原生 materialized view，复杂/非聚合变换才使用 Java 派生写入。

## 6. Java sync、衍生数据和 ASOF 分工

- **sync**：Java 调上游、校验 DTO、规范化、批量 QWP 写入；批次携带稳定 ID 或明确时间范围，完成后通过 JDBC 轮询可见性和业务 key 覆盖率。
- **衍生聚合**：QuestDB 原生 materialized view（聚合符合约束时）；建 view 的 DDL 和 refresh 策略作为版本化迁移脚本管理，Java 读取状态并在批处理/回补时调用官方 SQL。
- **非聚合衍生数据**：Java 实现确定性 mapper/job，写入隔离的结果表；明确重算窗口、upsert key、checkpoint 和失败重试。避免 Java 逐行 JDBC 写 QuestDB。
- **ASOF**：优先由 Java 仓储用 PGWire 执行官方 `ASOF JOIN` SQL，映射成 Java record。ASOF 以左侧每行匹配右侧 timestamp 不晚于左侧的最新行，可用 `ON (symbol, venue)` 限定键并以 `TOLERANCE` 限制过旧匹配。两侧 designated timestamp、键类型和时间单位要统一，按真实查询测计划和延迟。

```sql
SELECT t.timestamp, t.symbol, t.price, q.bid, q.ask
FROM trades t
ASOF JOIN quotes q ON (symbol, venue) TOLERANCE 5s
WHERE t.timestamp >= '2026-09-01T00:00:00Z'
  AND t.timestamp <  '2026-09-02T00:00:00Z';
```

## 7. 当前项目下一步

1. 获取目标 QuestDB Server 版本、启动参数、数据目录/备份方式及实际事故日志；确认物化视图在目标版本的可用性、查询约束和运维权限。
2. 盘点 Python 工程的 Pydantic models、字段、实际 DDL、写入 API/批大小/重试方法和关键 key；建立统一字段契约。
3. 选一个高频且纯时间聚合的衍生查询做 proof of concept；同时保留原始 SQL 作为正确性基线，按本文指标测读写开销和刷新延迟。
4. 选一张重复/冲突多的真实表，统计既有重复并核定唯一键，再评估 `DEDUP ENABLE` 和历史去重/重建窗口。不要在 key 未确认前直接改生产 dedup。
5. 把 WAL 监控查询、挂起恢复步骤、批次可见性校验与数据源重放方式写入部署运维手册。

## 官方资料

- [QuestDB Views](https://questdb.com/docs/concepts/views/)
- [CREATE VIEW](https://questdb.com/docs/query/sql/create-view/)
- [QuestDB Materialized Views](https://questdb.com/docs/concepts/materialized-views/)
- [CREATE MATERIALIZED VIEW](https://questdb.com/docs/query/sql/create-mat-view/)
- [REFRESH MATERIALIZED VIEW](https://questdb.com/docs/query/sql/refresh-mat-view/)
- [SHOW CREATE VIEW / MATERIALIZED VIEW](https://questdb.com/docs/query/sql/show/)
- [Materialized view refresh configuration](https://questdb.com/docs/configuration/materialized-views/)
- [Official guide: How to create a materialized view](https://questdb.com/blog/how-to-create-a-materialized-view/)
- [QuestDB Write-Ahead Log](https://questdb.com/docs/concepts/write-ahead-log/)
- [ALTER TABLE RESUME WAL](https://questdb.com/docs/query/sql/alter-table-resume-wal/)
- [QuestDB Monitoring and Alerting](https://questdb.com/docs/operations/monitoring-alerting/)
- [QuestDB Deduplication](https://questdb.com/docs/concepts/deduplication/)
- [QuestDB ASOF JOIN](https://questdb.com/docs/query/sql/asof-join/)
- [QuestDB Java Client](https://questdb.com/docs/connect/clients/java/)
