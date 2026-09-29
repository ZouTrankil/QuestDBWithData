# F002 日期与时刻契约

实现 `domain/temporal/TemporalValues.java`，已接入 StockBasicMapper 的来源 list_date 和 QuestDbStockBasicRepository 的日期读写。物理列名暂时保持兼容；语义不从 timestamp/time/trade_date 名字推测。自动生成 record 未修改。

- businessDate：显式 BASIC（八位）或 ISO（十位），严格 LocalDate，拒绝无效日期、偏移后缀和空白；可空策略由调用者声明。
- offsetInstant：输入必须有偏移；localInstant 必须指定 ZoneId，DST 重复和不存在时间拒绝，要求来源给出明确偏移。
- epoch / epochValue：显式 SECONDS/MILLIS/MICROS/NANOS 双向转换，包括负 epoch 的正确换算；不按数字长度猜单位。数值写出时拒绝精度损失和 long 溢出。
- Precision：目标毫秒、微秒、纳秒显式选择，丢失精度时拒绝，不截断。
- CalendarTimestamp：LocalDate 的零点物理载体，支持声明单位的数值编码/解码；UTC 仅用于固定存储编码，不能作为业务事件发生时刻；非零点拒绝。
- TechnicalTimestamp：单独类型且必须写明原因，不能作为真实业务新鲜度证据。

来源核对：`D:/work/fund_2/back-monitor/src/quant_platform/common/persistence/questdb_temporal.py`。参考其 BusinessDate/UTC_INSTANT 划分；没有照搬 legacy 名称猜测和 allow_naive_utc 默认兼容逻辑。月份/季度必须由对应数据任务声明映射，公共层不猜报告期或公布日。

## 验收

`TemporalValuesTest` 三项测试覆盖严格日期、闰年、偏移跨日、纳秒往返、数值编码往返、精度及溢出拒绝、缺少单位、负 epoch、DST 缺口与歧义、技术原因必填。`gradlew test` 成功后，另以 `QUESTDB_TEMPORAL_READ=1` 运行 `--tests '*Temporal*Test'`：四项通过、无跳过。补充数值编码后重跑 `--tests '*TemporalValuesTest'`，通过。

真实只读 HTTP 查询 daily.trade_date、etf_daily.timestamp、exchange_calendar.cal_date 各三行，Java 解析和物理载体往返共九行全部一致。查询、原始时间值、转换结果和时间保存在 `live-read.json`。这不是业务数据全量完整性证明。纳秒精度丢失分支由明确非零纳秒单元样例证明，实际日期样本均为零点，不声称实际来源含非零纳秒。

复现：设置 JAVA_HOME 为 `C:/Users/zouqiang/.jdks/jdk-24.0.2`，设置 `QUESTDB_TEMPORAL_READ=1`，执行 `.\gradlew.bat test --tests '*Temporal*Test' --rerun-tasks --console=plain`。测试仅 SELECT，不触发迁移或 sync。

本项是纯转换契约，source sync / DB 写入 / checkpoint / 取消恢复 N/A；写入 transport 完整验收由 F008 后续承担。保留 StockBasic 快照日现有语义。人工复核 pending_review。
