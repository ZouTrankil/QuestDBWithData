# 模型、时间与主键约定

## 分清三种模型

同一组数据经过外部 API、业务逻辑和数据库时，不建议强行共用一个 Java 类：

1. **API DTO** 表示 Pydantic/Tushare 对外字段和 JSON 表示法，负责入站字段名、必填项及格式校验。
2. **Domain model** 表示 Java 业务语义，不依赖 JSON、Spring 或 QuestDB。当前 `StockBasic` 用 `LocalDate` 表示上市日期。
3. **Persistence row/schema** 表示 QuestDB 表字段、designated timestamp 与 UPSERT KEYS。当前快照记录由 `StockBasicSnapshot` 表示，列名和 QuestDB 类型在 Flyway SQL 中定义。

DTO 到 domain、domain 到 persistence 的转换放在 client/service/repository 边界，不把数据库专用注解放进 domain model。

## 时间类型

| 语义 | Python / Pydantic | JSON Schema | Java domain | QuestDB |
| --- | --- | --- | --- | --- |
| 仅日期，例如上市日期 | `datetime.date` | `string`, `format: date` | `LocalDate` | `DATE`；当前 QWP Java Sender 无 `DATE` 写入 setter，本项目暂存为 `STRING` (`yyyyMMdd`) |
| 全球事件时间 | `AwareDatetime` / 带时区的 `datetime` | `string`, `format: date-time` | `Instant` | designated `TIMESTAMP` (UTC，微秒精度) |
| 无时区本地营业时间 | `datetime` + 明确业务时区 | 通常也是 `date-time`，需额外约定时区 | 先解析成 `ZonedDateTime`/`OffsetDateTime`，再转 `Instant` | `TIMESTAMP` |

不要把 `LocalDate` 转成 UTC 零点的事件时间；它是日期语义。也不要把 `Instant` 存成不带偏移量的字符串。JSON Schema 的 `format: date-time` 表示字符串格式，并不自动决定业务时区规则；Python 端应使用 `AwareDatetime` 或显式校验时区，Java 端统一转为 `Instant`。Pydantic 对 `date` 和 `datetime` 分别生成 `date` 与 `date-time` 格式。[Pydantic JSON Schema 文档](https://pydantic.dev/docs/validation/latest/concepts/json_schema/)

`StockBasic.listDate` 已按语义建模为 `LocalDate`。Tushare `YYYYMMDD` 在 client 边界解析；导出 CSV 时再格式化回 `YYYYMMDD`。因为 QWP Sender 暂无 DATE setter，repository 边界把它序列化为 STRING；业务模型不因此降成字符串。

## 主键与 QuestDB 去重键

QuestDB 的 designated timestamp 是时间排序、分区和时序查询所需的时间列，不是关系型数据库的主键，也不保证唯一。QuestDB 表默认允许重复行；需要幂等写入时，用 WAL 表的 `DEDUP UPSERT KEYS` 定义去重规则，且 designated timestamp 必须包含在 UPSERT KEYS 中。[QuestDB designated timestamp](https://questdb.com/docs/concepts/designated-timestamp/), [QuestDB deduplication](https://questdb.com/docs/concepts/deduplication/)

本项目区分两种身份：

- `StockBasicKey(tsCode)`：一只股票在源系统中的自然标识，`ts_code`。
- `StockBasicSnapshotKey(snapshotTimestamp, tsCode)`：历史快照表中的行身份，对应 SQL `DEDUP UPSERT KEYS(snapshot_ts, ts_code)`。

`snapshot_ts` 是逻辑快照日期的 UTC 零点编码，不代表实际采集时刻或北京时间零点。同一逻辑日期中每个 `ts_code` 只保留一行，而不同日期的快照可以同时存在。直接同步入口按 `app.time.business-zone` 确定今天；任务入口使用冻结请求的 `logicalDate`，重试时不能重新计算今天。若改成“当前状态表”，去重语义和时间设计需要重新评估，不能直接套用这个组合键。

### 统一业务时间入口

通过构造器注入 Spring 管理的 `BusinessTime`，业务代码无需重复写时区：

```yaml
app:
  time:
    business-zone: Asia/Shanghai
```

```java
businessTime.now();                  // 实际当前时刻，Instant
businessTime.today();                // 配置时区下的今天，LocalDate
businessTime.startOfDay(date);       // 业务日零点对应的 Instant
businessTime.inBusinessZone(instant);// 转为业务时区供展示
businessTime.todaySnapshotMarker();  // 当前快照表专用的日期标记
```

日期标记统一使用 `new TemporalValues.CalendarTimestamp(date).storageCarrier()` 编解码。例如逻辑日期 `2026-09-29` 编码为 `2026-09-29T00:00:00Z`；北京时间当天实际零点则是 `2026-09-28T16:00:00Z`。两者用途不同，不可互换。实际事件时间应使用 `Instant`，不可截到零点。

本次保留历史日期标记编码和去重键形式。直接同步入口默认按北京时间选日期，因此凌晨 00:00–08:00 将选北京时间当天，不再选 UTC 前一天；历史记录不会自动重标日期。任务定义自己的时区属于任务契约，不随此默认配置修改；显式 `logicalDate` 始终优先。修改业务时区会影响未来默认日期的选择，应作为业务规则变更处理。

JVM/JDBC 的 UTC 设置继续保留，它与业务日期的时区配置职责不同。日期型字段（上市日、交易日）仍使用 `LocalDate`，不按展示时区平移。`BusinessTime` 接受 `Clock`，需要验证时间边界时可传入固定时钟。

## Spring `@Entity` 是否适用

`@Entity` 是 Jakarta Persistence/JPA 的 ORM 映射注解，不是所有 Spring 数据模型都要加的注解。本项目使用 QWP `Sender` 写入、`JdbcTemplate` 执行 SQL，没有使用 JPA/Hibernate；`StockBasic` 和 `StockBasicSnapshot` 是普通 Java domain record，Flyway 迁移 SQL 是 QuestDB 表结构定义。

即使 PGWire 能让 PostgreSQL JDBC 客户端连接，也不表示 QuestDB 等同 PostgreSQL。QuestDB 不支持传统 `PRIMARY KEY`、`FOREIGN KEY`、`NOT NULL` 约束；JPA 的 `@Id` 也不会生成或配置 QuestDB `DEDUP UPSERT KEYS`。因此本项目用显式的 `StockBasicKey` / `StockBasicSnapshotKey` 表达 Java 侧身份，并在 QuestDB DDL 中声明去重键。[QuestDB PostgreSQL compatibility](https://questdb.com/docs/schema-design-essentials/)

若将来确实引入 JPA，必须单独验证 Hibernate 生成 SQL 与 QuestDB 支持范围，并避免依赖自动建表、关系约束和 ORM 冲突更新行为。对当前时序写入路径，保留 domain record + 显式 repository 更清晰。

## Pydantic → JSON Schema → Java

数据库 schema 的版本演进交由迁移工具管理，参见 [Flyway schema 管理](flyway-schema-management.md)。模型契约可辅助生成 SQL，Flyway 负责执行和记录迁移历史。

建议把 Pydantic 作为数据契约来源，JSON Schema 作为可审阅、可校验、可生成 DTO 的交换格式；生成结果不要直接当作 domain 或 QuestDB entity。

```python
from datetime import date
from pydantic import BaseModel, ConfigDict, Field

class StockBasic(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ts_code: str = Field(json_schema_extra={
        "x-business-key": True,
        "x-questdb-type": "SYMBOL",
    })
    symbol: str
    name: str
    area: str | None = None
    industry: str | None = None
    list_date: date | None = None

schema = StockBasic.model_json_schema(mode="validation", by_alias=True)
```

Pydantic 可由 `model_json_schema()` 生成 JSON Schema。该 schema 可表达字段类型、required、nullable、约束和字段别名，但标准 JSON Schema 没有关系型主键、QuestDB designated timestamp、SYMBOL 或 UPSERT KEYS 语义。[Pydantic schema 生成](https://pydantic.dev/docs/validation/latest/api/pydantic/json_schema/)

把数据库元数据作为 JSON Schema vendor extension（`x-...`）或独立 manifest 维护，例如：

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "snapshot_ts": {
      "type": "string",
      "format": "date-time",
      "x-questdb-type": "TIMESTAMP",
      "x-questdb-designated-timestamp": true,
      "x-questdb-upsert-key": true
    },
    "ts_code": {
      "type": "string",
      "x-questdb-type": "SYMBOL",
      "x-business-key": true,
      "x-questdb-upsert-key": true
    }
  }
}
```

推荐流水线：

1. 用 Pydantic 定义并校验来源契约；选择 `mode="validation"` 或 `mode="serialization"`，并固定 `by_alias` 约定。
2. 导出带版本控制的 JSON Schema；检查时间格式、别名、required、nullable、`$ref` 和 `additionalProperties`。
3. 从 JSON Schema 生成 Java **DTO**，生成代码标记为不可手改。
4. 编写或生成 DTO → domain mapper；Java domain 使用 `LocalDate`/`Instant` 等语义类型。
5. 单独维护 QuestDB DDL/元数据；由 repository 负责 domain → QWP/JDBC 转换和写入。

主键不要从 JSON Schema 的 `required` 推断：必填不等于唯一。`x-business-key` 是源实体身份；`x-questdb-upsert-key` 是特定表的去重规则；二者可能相同，也可能不同。JSON Schema 负责描述数据契约，QuestDB DDL 负责持久化约束。

## 本项目当前对应关系

| 层 | 类型/定义 |
| --- | --- |
| 外部 API DTO | `client.dto.TushareStockBasicDto`，保留来源的 `YYYYMMDD` 字符串 |
| DTO 映射 | `mapper.StockBasicMapper`，将 `list_date` 解析为 `LocalDate` |
| Java domain | `StockBasic`、`StockBasicKey` |
| 快照 domain | `StockBasicSnapshot`、`StockBasicSnapshotKey` |
| QuestDB schema | `db/migration/questdb/V1__create_stock_basic_table.sql` 的 DDL 与 UPSERT KEYS |

拿到实际 Python/Pydantic 模型后，应以模型和真实响应为准生成 schema，再确定 DTO 生成器、字段别名和 vendor extension；不要凭当前 Java 类反推缺失的源契约。

新模型的逐步落地示例见 [新增模型与读写流程](model-development-workflow.md)。

## 当前实例 schema 快照

已从配置的 `qdb` 实例只读导出 QuestDB 10.0.1 schema，快照见 [`schema-export/questdb-qdb-2026-09-28/`](../schema-export/questdb-qdb-2026-09-28/)。导出分为 table、view 和 materialized view，并额外保留按依赖顺序排列的完整 `schema.sql`。该实例有 321 张表、10 个普通 view、2 个 materialized view。

`domain/table`、`domain/view`、`domain/materializedview` 下的 record 是从快照字段生成的 Java schema projections：分别生成 172 个非 backup/staging/WAL drill 表记录、10 个 view 读取模型、2 个 materialized-view 读取模型。149 个命名为备份、staging 或 WAL 演练的表仍在原始 schema 导出中，但不生成应用模型。生成器和分类规则见 [`generate_domain_models.py`](../tools/generate_domain_models.py) 与快照目录 README。

这些 projection 方便读取映射、schema 对照和后续迁移，不自动等于经过业务确认的 domain。DDL 无法还原可靠的 nullability、单位、字段业务含义和 Python Pydantic 校验规则；canonical domain 仍应由业务/API 契约审核，必要时通过 mapper 在 schema projection 与 domain 之间转换。
