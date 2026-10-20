# 模型和新增数据集

新增数据时，先把字段和业务键说清楚，再写 Java 模型、映射和数据库 SQL。跨市场规则见[时间规范](project-structure-and-standards.md#时间怎么处理)。

## 三种模型各管一段

| 模型 | 管什么 | 股票资料例子 |
| --- | --- | --- |
| 来源 DTO | 外部字段名和原始格式 | `TushareStockBasicDto`，上市日还是 `YYYYMMDD` 字符串 |
| 业务模型 | 程序里真正使用的含义 | `StockBasic`，上市日用 `LocalDate` |
| 存储模型和 SQL | 表字段、类型、时间列和去重键 | `StockBasicSnapshot` 和 Flyway SQL |

`StockBasicMapper` 做明确转换。Java 的 record 或 key 类型不替数据库实现去重，也不用加 JPA `@Entity`。项目用显式 SQL 和读写适配器，不靠 ORM 自动建表。

## 模型目录怎么分

CN 的 `com.zoutrankil.data.domain` 按数据类别分包，参考 [Tushare 目录](https://tushare.pro/document/2)，只创建已有条目的分类。模型、业务键和 Dataset 契约放在对应类别；存储投影继续放在类别下的 `table`、`view`、`materializedview`，规则放 `policy`。

```text
domain/
├── stock/
│   ├── basic/       基础资料、交易日历、ST
│   ├── market/      日线、每日指标、涨跌停、停复牌
│   ├── financial/   财务报表、指标、分红和披露计划
│   ├── reference/   股东、质押、回购、解禁、大宗和异动
│   ├── feature/     筹码、技术因子、券商荐股
│   ├── margin/      两融汇总、明细及标的
│   ├── moneyflow/   资金流向
│   └── concept/     THS、DC 概念和行业板块
├── fund/            ETF 与公募基金
├── index/           指数目录、行情、成分和权重
├── futures/         期货合约、行情、持仓和结算
├── spot/            黄金现货
├── bond/            债券收益率；当前来自 ChinaBond
├── forex/           人民币指数历史模型，来源仍待确认
├── macro/
│   ├── domestic/    国内经济、社融和财经日历
│   └── interest/    Shibor、LPR、Hibor
├── derived/         本地衍生计算和回测投影
├── l2/              L2 特征、标签和归档契约
└── shared/          跨类别的任务身份与隔离目标规则
```

这是代码导航分类，不表示每个对象都来自 Tushare；基金合并 ETF 与公募条目，本地衍生计算和 L2 单列。`stock/market/DailyDataset` 是执行契约，`stock/market/table/DailyRow` 是存储投影，不是两张表。只迁模型包与引用，任务、Quartz 类名、物理表名和恢复协议保持原样。

## 日期和时间别混

- 只有日期用 `LocalDate`，比如上市日、交易日、报告期。
- 真正发生的时刻用 `Instant`，存 UTC。
- 来源只给本地时刻时，先确定时区再转换。
- 明确精度、空值和超精度处理，不能悄悄截断。

股票快照的行身份是 `(snapshotTimestamp, tsCode)`。其中 `snapshot_ts` 是逻辑日期的 UTC 零点标记：`2026-09-29` 编码为 `2026-09-29T00:00:00Z`，不代表采集时刻，也不代表上海当天零点。

日期标记用 `TemporalValues.CalendarTimestamp` 编解码。直接同步入口由 `BusinessTime` 按 `app.time.business-zone` 选业务日期；任务入口使用原请求的 `logicalDate`，重试不重新取今天。

已核定的六个交易日物理别名使用 `CnTradeDateLayout` 显式分版；不能根据当前列名自动改用另一版。当前 `etf_adj`、`etf_daily`、`etf_share` 的 v2 读写端口完成隔离验收，控制服务、配置与写入组可按原冻结版本执行和恢复；公开默认仍是 v1。日线保留 TIMESTAMP_NS 纳秒，复权和份额保留 TIMESTAMP 微秒；份额的独立 `update_time` 不随交易日迁移改值。ST 与停牌的显式发布、恢复和配置入口也已接通；L2 端口、Batch 独立来源、正式调度及正式迁移仍待完成。版本选择不改变 UTC 午夜日期载体或精度，见[布局分版](decisions/2026-10-21-交易日存储布局分版.md)。

```java
businessTime.now();                  // 当前实际时刻
businessTime.today();                // 业务时区下的今天
businessTime.startOfDay(date);       // 业务日零点对应的真实时刻
businessTime.todaySnapshotMarker();  // 快照表使用的日期标记
```

`BusinessTime` 支持注入固定 `Clock`，方便检查跨日边界。现在从 `MarketTimes` 按市场取得 `MarketTime`；旧 `BusinessTime` 只适配 A 股时钟。

## 数值单位

数值列可在 `DatasetDefinition.Column.numeric` 登记 `NumericContract`：来源单位、基准单位、原值到基准倍率、币种、分母、价格口径、缺失规则和依据。它描述现有原值，不执行换算。例如 `amount=2.5` 的单位为千元，元单位倍率为 `1000`，读取和模型输入仍保持 `2.5`。比例的百分数和小数须明确；未知尺度用 `sourceUnit=UNVERIFIED`、倍率为 null，不默认按百分数处理。非货币量用 `currency=NONE`，未知币种明确标为待核。

目录详情和字段 CSV 复用该契约；旧 `JobDefinitionJson` 的 Dataset 列编码不增加 `numeric`，避免改变保存过的恢复协议。v2 主策略证明单独发布数值契约，保留原模型数值。今后真正缩放或更改字段含义时，仍须升级输入/产物协议并重新验收模型，不能只修改倍率说明。当前覆盖和限制见[数值契约决定](decisions/2026-10-20-主策略来源数值单位契约.md)。

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

生成目录只保留采集字段资料需要或现有代码实际使用的投影，不把实库发现的每个对象都生成 Java 类。CN 已移除 47 个无人引用的旧快照投影壳；这不代表删除同名数据产品或数据库对象。`*Dataset` 是执行契约，`*Key` 是业务键，`*Snapshot` 可能是核验输入，它们不能当作临时表批量清理。

`java_d*` 多数是隔离验收目标，`java_tushare_stock_basic_*` 是 API/CLI 仍使用的旧示例。它们与正式同步目标分别核对，不能靠改名或删模型宣称已完成接管。stage/backup 的删除仍按发布和恢复归属判断。

## 采集字段的官方说明

CN 的来源字段说明维护在 `markets/cn/src/main/resources/model-docs/collected-fields.json`。更新后用生成器的 `--annotate-existing` 刷新代码注释，用 `--export-documentation 新目录` 导出 JSON 和 CSV。完整重生成也会读取同一份说明。接口、字段、单位与来源格式须有明确出处；本地补充列和文档缺项分别标记，不改动既有模型类型及业务规则。具体操作与未核实范围见[采集字段注释](collected-field-documentation.md)。

## 指数日线正式与隔离布局

`index_daily_market` 正式物理列为 `trade_date`，对应 `IndexDailyMarketDataset` v2。D019 现有作业仅允许隔离表，显式使用 `legacyIsolatedDefinition` v1 的 `timestamp`；不要将正式契约的版本改回 v1，也不要更改旧冻结请求来适配新布局。迁移状态见[执行记录](operations/cn-time-migration/README.md)。

主策略内部证券身份是 `ScoreRow.instrumentCode`，汇总身份是 `AggregationScope`；同一行只允许一种。ETF 持仓用 `etfCode/constituentCode` 表达两侧角色。旧产物仍有显式 v1 适配，新版对外字段尚未切换，见[设计决定](decisions/2026-10-20-主策略内部身份角色.md)。

主策略读取时间按角色绑定现有 Dataset 定义，不根据裸 `timestamp/ts` 猜测日期。情绪和市场状态的契约由 CN 模型库统一提供；v1 编码与新版迁移状态见[设计决定](decisions/2026-10-20-主策略按来源契约解码时间.md)。

CN 异动公告的只读 v2 使用 `announcement_date`，Shibor/Hibor/LPR 使用 `observation_date`；两者都解码为 `LocalDate`。物理列和旧来源投影仍保留，不能当作已完成迁移。数字开头的物理期限列必须显式引用，逻辑名与表名仍使用原标识校验，见[日期读取决定](decisions/2026-10-20-公告与利率逻辑日期读取.md)。

`stk_st_daily` 的显式存储布局 v2 使用 `trade_date` 逻辑键和物理列，v1 行编码仍保留 `timestamp`。新编码、完整键回读和快照摘要单独验证；名称变更原文及模型仍按旧编码解释。生产发布、stage/backup 与恢复尚未贯通 v2，不能只靠存储端口切换默认值，见[ST 存储分版](decisions/2026-10-21-交易日存储布局分版.md#st-存储分版)。

ST 的 v2 stage/发布日志显式固定 `datasetSchemaVersion` 和 `rowEncoding`，SQLite 新发布记录另有协议包装；旧日志保持原形。恢复按原冻结版本建立目标作用域，空摘要也不能跨布局复用。当前生产入口仍为 v1，实际范围及区间锁回调修复见[发布分版](decisions/2026-10-21-交易日存储布局分版.md#st-显式发布与恢复分版)。


停牌的逻辑日期已是 `trade_date`，新布局只更换物理列；原逻辑行编码及摘要不变，不能据此借用旧布局凭证。显式 writer、读取与快照保留 DAY/WAL、TIMESTAMP 微秒和 `suspend_d` 正记录含义；来源执行和配置入口已按显式版本接通，发布恢复范围见下文及[停牌分版](decisions/2026-10-21-交易日存储布局分版.md#停牌存储分版)。


停牌新布局的 stage 与发布 scope 单独绑定物理 schema v2，逻辑行编码仍是 v1；不能把相同行摘要当作同版证明。恢复从原账本选作用域，先核验原来源、stage SHA-256 与全窗，再补原 rename；原路径只做安全核验，不换写 `/var` 别名。公开配置默认仍为 v1，见[停牌发布决定](decisions/2026-10-21-交易日存储布局分版.md#停牌显式-staging发布与恢复)。


停牌来源和控制流程现可显式按冻结布局执行及恢复，原始来源和逻辑行编码不改。追加完成证明须关联页面、全窗及 WAL；旧缺证记录不能自动升级。再验证固定原物理代际或原运行自身的已验证发布代际，重复核对沿用原完成证明，不重写原件。配置与公共注册已接通，默认 v1；读写表名独立保留原默认，见[来源与控制决定](decisions/2026-10-21-交易日存储布局分版.md#停牌来源执行与原版本控制)。
