# 三个市场的时间怎么用

统一做法是：**真实时刻存 UTC，业务日期按市场判断。** 不修改现有数据库时间值，也不把整库统一加八小时。

| 市场 | 业务时区 | 日期与调度 |
| --- | --- | --- |
| A 股 | `Asia/Shanghai` | 今天、收盘、主策略和 L2 会话按上海时间 |
| 美股 | `America/New_York` | 按纽约地区规则处理夏令时，开市日看实际交易日历 |
| BTC | 默认 `UTC` | 日线切分遵守具体交易所或数据源契约 |

NYSE 的会话使用美东时间，不能全年固定成一个 UTC 小时；地区时区会按日期计算偏移。参考 [NYSE 交易时间](https://www.nyse.com/trade/hours-calendars) 和 [Java ZoneId](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZoneId.html)。

## 配置在哪

统一在根 `config/application.yml`：

```yaml
app:
  time:
    business-zone: Asia/Shanghai
    markets:
      cn: Asia/Shanghai
      us: America/New_York
      btc: UTC
```

`business-zone` 是旧 A 股入口的兼容值，与 `markets.cn` 冲突会拒绝启动。A 股和美股不接受改成 UTC 或固定美东偏移量。BTC 可以按具体来源约定使用不同切分时区。

实时 L2 的 `l2.session.zone` 也必须与 A 股配置一致。它仍自己判断交易日和会话范围，不因换目录合并进主策略进程。

## 代码怎么取时间

core 提供 `MarketId`、`MarketTime`、`MarketClock`、`MarketTimes`。`runtime-support` 的 `MarketClocks` 读取配置；应用服务取所需市场，别切换 JVM 默认时区。

```java
MarketTime cn = marketTimes.require(MarketId.CN);
Instant now = cn.now();                 // 同一个真实时刻，UTC
LocalDate day = cn.today();             // 上海业务日期
Instant start = cn.startOfDay(day);     // 上海当天零点对应的 UTC 时刻
Instant end = cn.endOfDay(day);         // 下一业务日零点，范围右侧不包含
```

旧 `BusinessTime` 继续适配 A 股时钟。关键因子流程、物化视图任务和正式 L2 日期入口支持传入 `MarketTime`；旧构造器保留兼容，明确使用 A 股系统时钟。

测试时传 `Clock.fixed(...)`，不要修改服务器时钟。新业务不要裸写 `LocalDate.now()`、`LocalDateTime.now()` 或 `ZoneId.systemDefault()` 来决定交易日。

## 三类字段分开处理

- `event_time`、`received_at`、`available_at`：真实时刻，使用 `Instant`；输出时保留 UTC 或明确偏移量。
- `trade_date`、`report_period`：业务日期或报告期，使用日期语义，不加减时差。
- UTC 零点日期标记：保留原来的 `TemporalValues.CalendarTimestamp` 编码，不当作事件时间平移。

例如“2026-10-19”的旧日期标记仍是 `2026-10-19T00:00:00Z`，上海当天实际零点是 `2026-10-18T16:00:00Z`。改用市场时钟不重写这两种存量字段。

现有本地因子产物中还有无偏移的 `computedAt` 兼容字段，继续按原产物格式处理：股票面板明确使用 UTC 表示，ETF/L2 的旧本地时间按上海规则。它们不替代带时点含义的 `available_at`，新增跨市场产物使用明确的 `Instant` 契约。

## 夏令时和日历的边界

纽约本地时间有时会缺失或重复。`toInstant(localTime)` 遇到这种时刻会拒绝猜测；重复时刻需要调用带 `ZoneOffset` 的版本，并核验偏移确实有效。

一个纽约业务日可能是 23 或 25 小时。计算结束边界用下一天的市场零点，别给开始时间固定加 24 小时。

市场时钟不推断节假日和提前收盘。A 股使用已保存的交易日历；美股完整日历和 BTC 来源接入尚需真实数据，不能用工作日或空模块冒充已接通。

恢复继续使用原请求的逻辑日期、日历版本和时区。已经持久化的旧请求不改身份、不重算“今天”、不自动释放未决写入。
