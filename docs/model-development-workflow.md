# 新增模型与读写流程

新增数据集按“字段契约 → Java model/mapper → Flyway SQL → 写入/查询映射”落地。模型定义负责数据含义，Flyway 管理数据库结构的版本；两者不能互相替代。

## 1. 先写字段契约

每个字段记录来源名、Java 类型、数据库类型、可空性、转换规则和是否参与身份。以股票日快照为例：

| 来源/业务字段 | Java 字段及类型 | QuestDB 列及类型 | 规则 |
| --- | --- | --- | --- |
| `ts_code` | `tsCode: String` | `ts_code SYMBOL` | 非空自然标识；属于快照去重键 |
| `symbol` | `symbol: String` | `symbol SYMBOL` | 保留前导零，不能转整数 |
| `name` | `name: String` | `name STRING` | 名称可修改，不参与 key |
| `area` | `area: String` | `area SYMBOL` | 当前 client 将源 null 转为空串；新契约需显式决定是否改用 null |
| `industry` | `industry: String` | `industry SYMBOL` | 同上 |
| `list_date` | `listDate: LocalDate` | `list_date STRING` | 源 `yyyyMMdd` → 日期；存储格式仍 `yyyyMMdd`；缺失时 null |
| 任务快照时间 | `snapshotTimestamp: Instant` | `snapshot_ts TIMESTAMP` | UTC 日快照，微秒精度；designated timestamp 和去重键 |

这份表明确“约定”不等于所有校验均已实现；当前限制见 [读写规范](questdb-usage.md#规范覆盖与当前限制)。

时间应先确认是日期、事件时间、采集时间还是快照时间。日期用 `LocalDate`，绝对时间点用 `Instant`，同时固定数据库精度及超精度的拒绝/截断规则。默认值不能随意使用每次执行的当前时间，否则同一个源事件重放可能产生不同 key。

## 2. 定义 Java model

当前已有 `client.dto.TushareStockBasicDto` 表示源字段；`domain.StockBasic` 使用语义类型；`StockBasicMapper` 完成显式转换。外部格式的时间字符串只保留在 DTO 边界。

```java
public record StockBasic(
        String tsCode,
        String symbol,
        String name,
        String area,
        String industry,
        LocalDate listDate) {
    public StockBasicKey key() {
        return new StockBasicKey(tsCode);
    }
}
```

一条持久化快照由 `StockBasicSnapshot(Instant snapshotTimestamp, StockBasic stock)` 表达，行身份是 `(snapshotTimestamp, tsCode)`。Java key record 的相等性只用于应用逻辑，数据库去重仍由 DDL 实现。新增简单模型不要求机械复制多层 key 包装类；只有身份确实需要独立传递时才建立 key 类型。

## 3. 用 Flyway 定义表

现有 V1/V2 已代表初始 stock_basic 表/view。新增实体或字段创建下一个版本文件，例如 `V3__create_trade_table.sql`，不要改已经执行过的 V1/V2。

```sql
-- 示例契约：同一时刻同一 trade_id 表示同一笔交易。
CREATE TABLE trades (
    event_ts TIMESTAMP,
    trade_id STRING,
    symbol SYMBOL,
    price DOUBLE,
    quantity DOUBLE
) TIMESTAMP(event_ts) PARTITION BY DAY WAL
  DEDUP UPSERT KEYS(event_ts, trade_id);
```

这里的数值类型只是示例：需要精确十进制时必须另行确定金额类型、精度和客户端映射。QuestDB designated timestamp 必须包含在去重键中；不要把价格等可修正值放入 key。原生 SQL 与源契约一起审阅。

## 4. 定义写入与查询映射

- QWP 按列名选择 setter；`SYMBOL` 用 `symbol()`，字符串用 `stringColumn()`，指定时间列用 `at(Instant)`。不能依赖 Java 字段顺序与数据库列顺序相同。
- nullable 字段在写入边界明确处理：QWP 缺失 setter 表示 null；空字符串是否等于 null 由契约决定。
- JDBC 查询列清单固定，用户条件参数绑定；RowMapper 按列名读取并转为同一套日期、时间和空值语义。
- 新查询需要确定返回范围、排序、条数限制与超时。涉及写后读时核验本批业务 key 和内容，不能只凭历史行数相同判定成功。
- schema 迁移入口在 service，数据访问在 repository。迁移失败不得继续写入。

## 5. 接入 Pydantic 契约

如果 Python 模型是来源，先导出并提交 JSON Schema，再生成 Java DTO。固定 schema dialect、字段别名、required/nullable、date/date-time、数值精度及版本。Pydantic 自定义 validator 不一定能完整表达为 JSON Schema，Java mapper 需要补齐对应业务规则。

标准 JSON Schema 不包含 QuestDB 表名、分区和 UPSERT KEYS。数据库元数据使用明确的扩展字段或独立 manifest。建议先审阅生成的模型与初始 SQL，再通过 Flyway 管理数据库变更；生成器不应自动改写已应用迁移。

当前项目尚未提供 Pydantic 源文件，因此 DTO/mapper 是手写实现，生成流水线仍待接入。详细约定见 [模型规范](model-schema-conventions.md)。

## 6. 新模型交付检查

需要检查正常数据、缺失字段、非法日期/时区、重复 key、重复提交、旧值修正，以及空库迁移和迁移重跑。数据库相关结果必须在目标 QuestDB 版本上验证；Java 编译和模块加载测试不能替代这些检查。
