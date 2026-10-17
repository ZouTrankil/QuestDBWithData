# 模型和新增数据集

新增数据时，先把字段和业务键说清楚，再写 Java 模型、映射和数据库 SQL。跨市场规则见[时间规范](project-structure-and-standards.md#时间怎么处理)。

## 三种模型各管一段

| 模型 | 管什么 | 股票资料例子 |
| --- | --- | --- |
| 来源 DTO | 外部字段名和原始格式 | `TushareStockBasicDto`，上市日还是 `YYYYMMDD` 字符串 |
| 业务模型 | 程序里真正使用的含义 | `StockBasic`，上市日用 `LocalDate` |
| 存储模型和 SQL | 表字段、类型、时间列和去重键 | `StockBasicSnapshot` 和 Flyway SQL |

`StockBasicMapper` 做明确转换。Java 的 record 或 key 类型不替数据库实现去重，也不用加 JPA `@Entity`。项目用显式 SQL 和读写适配器，不靠 ORM 自动建表。

## 日期和时间别混

- 只有日期用 `LocalDate`，比如上市日、交易日、报告期。
- 真正发生的时刻用 `Instant`，存 UTC。
- 来源只给本地时刻时，先确定时区再转换。
- 明确精度、空值和超精度处理，不能悄悄截断。

股票快照的行身份是 `(snapshotTimestamp, tsCode)`。其中 `snapshot_ts` 是逻辑日期的 UTC 零点标记：`2026-09-29` 编码为 `2026-09-29T00:00:00Z`，不代表采集时刻，也不代表上海当天零点。

日期标记用 `TemporalValues.CalendarTimestamp` 编解码。直接同步入口由 `BusinessTime` 按 `app.time.business-zone` 选业务日期；任务入口使用原请求的 `logicalDate`，重试不重新取今天。

```java
businessTime.now();                  // 当前实际时刻
businessTime.today();                // 业务时区下的今天
businessTime.startOfDay(date);       // 业务日零点对应的真实时刻
businessTime.todaySnapshotMarker();  // 快照表使用的日期标记
```

`BusinessTime` 支持注入固定 `Clock`，方便检查跨日边界。现在从 `MarketTimes` 按市场取得 `MarketTime`；旧 `BusinessTime` 只适配 A 股时钟。

## 业务键和数据库去重

指定时间列用于排序、分区和时序查询，本身不保证唯一。需要防止重复时，在 WAL 表的 `DEDUP UPSERT KEYS` 里写完整业务键，并包含指定时间列。

例如 `StockBasicKey(tsCode)` 表示一只股票；快照键还要带逻辑快照日期。同一只股票的多日快照可以同时存在。名称、价格等会修正的字段不要随便塞进身份键。

代码保留前导零和市场后缀。null、空串和零是否相同，要由来源契约决定。当前示例通过 QWP 把上市日期存成 `STRING` 的 `yyyyMMdd`，业务模型仍使用 `LocalDate`。

## 新增数据集按这几步做

1. **定归属**：市场、来源、消费者、唯一写入方、正式存储。自己的策略订单和成交记录用本地 SQLite，不按行情表建到 QuestDB。
2. **写字段表**：来源名、Java 类型、存储列、单位、空值、时间和业务键。说清分页上限、结束依据、预期日期和证券范围。
3. **写模型和 mapper**：外部字符串在边界解析；非法日期、错范围、重复键要明确处理，不拿请求日期或当前时间补造缺失来源。
4. **写结构变更**：适用 Flyway 的表新增下一个迁移版本；不要改已执行的脚本。任务自带 DDL 按所属契约验证结构。见[迁移说明](flyway-schema-management.md)。
5. **接读写端口**：显式列映射，查询限定范围、排序、条数和超时，值参数使用绑定，动态表名用白名单。
6. **验收**：检查正常、缺失、重复、历史修订、重复运行和失败恢复；正式写入比较完整键和值，并确认 WAL 和版本稳定。

行数相同只能说明数量相同，不能证明旧值已更新。覆盖缩小时，UPSERT 也不会自动删除没有再次出现的证券；要单独说明删除语义。

## 生成模型时怎么做

现有生成工具是 [generate_domain_models.py](../tools/generate_domain_models.py)，需要用 `--catalog` 提供自己准备的 catalog JSON，并用 `--market cn/us/btc` 选择归属。catalog 或每个对象要明确写 `market`；没有归属就拒绝生成，不靠表名前缀猜。也可以对已经分好市场的 catalog 显式传 `--output-root`。生成的 record 只说明数据库字段，不自动包含单位、业务含义和源校验。

如果以后接入 Python/Pydantic，先提供真实模型和带版本的 JSON Schema，再确定 DTO 生成方式。当前没有完整的 Pydantic → Java 自动流水线，不要把建议写成已实现能力。

生成契约要明确别名、必填与可空、日期格式、精度和版本。必填不等于唯一；数据库时间列、分区和去重键另外维护。自定义 Python 校验也不一定能由 JSON Schema 完整表达，需要在 Java 边界补齐。
