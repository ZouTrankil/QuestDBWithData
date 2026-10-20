# ARRXF 与 BARUF 五年报价

两份原 Yahoo 响应的长区间因早期缺失字段而失败。本次按用户允许的五年范围，正式补入 `2021-10-16` 至 `2026-10-16` 的 2510 条原报价；首个实有日期为 2021-10-18。十二个年度 Native 均直接通过，未重发或恢复补写。原失败任务、完整原文及其中更早的缺口保持，没有把五年验收改写成完整二十年成功。

| 代码 | 五年报价行 | 正量报价 | 零量报价 | 原响应 SHA-256 |
| --- | ---: | ---: | ---: | --- |
| ARRXF | 1255 | 883 | 372 | `8b353ddea48e159777d017c3f095a5c4b2668c28901d44302b0e39d2ffc7cb38` |
| BARUF | 1255 | 871 | 384 | `74dfa4404fa0a3881147cf1b2a2d2b371b6bcf1331bf52dd2eae9c729320c2e7` |

两组日期均与已验收 SPY/Tiingo 的同范围实有日期一致，没有重复键或参照外日期。这个参照不是官方完整历史日历。全部原始及调整 OHLCV 均为有限值，价格为正、量非负，OHLC 范围一致；零量原报价保留，不补造成交。

## 身份与公司行动边界

Yahoo 元数据均为相同代码、USD、`America/New_York` 和 `EQUITY`；保留的 Tiingo 股票目录也列为 USD 股票。目录只作来源身份参照，没有据此新增永久证券映射。

- ARRXF：OTC Markets 的[当期一级来源搜索结果](https://www.otcmarkets.com/stock/ARRXF/disclosure)列为 Archer Materials Ltd 普通股、Pink Limited。直接打开的页面仅提示启用 JavaScript，因此没有宣称完整页面下载成功，也不据当前名称证明全部历史身份或持续挂牌。
- BARUF：[发行人公告](https://barugold.com/news-releases/baru-gold-announces-otcqb-listing/)确认自 2021-01-22 开市在 OTCQB 使用 BARUF，并区分加拿大 TSXV 的 BARU。本次选定区间在该日期之后，不混入 BARU 的加元报价。
- Archer 的 [2021-10-08 ASX 公告](https://announcements.asx.com.au/asxpdf/20211008/pdf/451fm0x48t3xrg.pdf)披露 iTech 股份实物分派安排：登记日 10 月 13 日、安排发行日 14 日、寄发持股通知为 18 日。安排日期不当成已独立确认完成日，实物分派也不填成现金分红。

本次窗口内，原 Yahoo 没有分红或拆股事件；实际保存原 `dividend=0`、`split_factor=1`，不改复权值。它们只表示来源在该范围所报的现金分红及拆股，不证明全部公司行动或总回报历史完整。原响应里 10 月 20 日的未完成空值不属于冻结至 16 日的范围。

## 发布与消费

Java 沿现有发布器、写入锁和原物理表执行，每只证券按六个年度窗口发送，避免把多年的 O3 写入塞进同一等待窗口。启动意图及每个原 Native 回执分别保留；不确定结果沿原运行核对，不从头重发。全市场主采集不因本批请求停止；两种写入通过同一锁串行执行。本批复用原文，没有新增行情 API 请求。

[只读查询](../../tools/sql/us-otc-five-year-quotes.sql)固定两个代码、来源和日期，返回二十个原字段及 `has_positive_volume`。查询保留全部零量报价；研究可按该标志选择实有正量记录，不能把完整报价网格解释为每日都有成交。

23:39 UTC 独立复核确认，全部 2510 行二十列与原 Yahoo 投影、prepared、HTTP 和 PGWire 一致。原 79990 条核心 Tiingo 日线、207448 条既有补录价格、45 条标识的全部字段摘要保持；原主运行已成功前缀的 Native 条目和事件保持。原物理对象、v27 的 47 个 JAR 摘要及原失败任务保持，无未决记录。各年度在其 WAL 验收后才成功；主采集仍在向同一行情表写入其他股票，不把表当前整体静止作为结论。

23:40 UTC 实际执行保存查询，2510 行全部二十一列均与独立预期一致，无重复或额外键。`has_positive_volume=true` 为 1754 行，其余 756 条零量原报价仍在结果中。查询 SHA-256 为 `b00fcbd717a10b365e16c69f60f75a294830250509595ead4f7011a8c7032771`。

独立验收文件 `independent-verification.json` 的 SHA-256 为 `021dedd1244638239d7e3630f7b15a681756b13aae0afe2101cdd74c5e601160`；十二个原运行 ID 和 prepared 摘要均在该文件中，`saved-query-verification.json` 保存查询逐字段证明。

本地证据保存在 `var/market-data/scope-review/otc-five-year-20261021/`，包括明确范围决定、发行人网页核读记录、写前保护快照、启动意图、逐年 Native 回执及独立回读证明。大原文、SQLite、编译文件和全值快照不加入 Git。完整全市场目标仍由剩余来源和逐段验收决定。
