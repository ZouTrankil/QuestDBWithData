# E*TRADE、Heinz 与 Forest 历史验收

本批新增 7711 条 Tiingo 日线和三条永久映射，映射总数由 54 增至 57。价格二十列、映射十八列均与原文、prepared、HTTP 和 PGWire 独立一致，发布前保护的旧价格及 54 条映射保持。全市场仍有未验收任务。

| 原代码 | 永久价格键 | 行数 | 本批窗口 | 日期覆盖 |
| --- | --- | ---: | --- | --- |
| ETFC | `US000000000090` | 3713 | 2006-01-03 至 2020-10-01 | 与 SPY 参照逐日一致 |
| HNZ | `US000000009599` | 1861 | 2006-01-03 至 2013-06-07 | 2006 年缺九个参照日；最近五年 1259 行完整 |
| FRX | `US000000001760` | 2137 | 2006-01-03 至 2014-06-30 | 与 SPY 参照逐日一致 |

参照来自已验收的 Tiingo SPY 数据。HNZ 缺少 2006 年的 03-21、04-11、05-24、06-20、06-22、06-27、07-05、09-19、12-19；这些缺日保持。2007 年起的 1619 行日期完整，最近五年窗口为 2008-06-07 至 2013-06-07。供应商实有行全部保留，字段验收与完整历史范围分开说明。

## 来源与边界

- **ETFC**：名称搜索只返回五只共同基金，另按原代码搜索后唯一匹配 E TRADE Financial Corp。采用其实际永久代码和 FIGI `BBG000N746D6`。[Nasdaq 通函](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2020-174)明确最后交易日为 2020-10-01，当日 20:00 盘后结束即停牌，10-02 开盘前并购完成，当日继续停牌，10-05 正式暂停挂牌。10-02 的平价、成交量为 1 的原报价留存待核。每股换取 1.0432 股 Morgan Stanley，未将并购对价写入分红或拆股列。
- **ETFC 拆股**：[2010 年报](https://www.sec.gov/Archives/edgar/data/1015780/000119312511042158/d10k.htm)确认 2010-06-02 生效 1 合 10，原 `splitFactor=0.1` 一致。九条拆股前 `adjVolume` 恰为原成交量乘 0.1 后向下取整，与精确乘积的差额均小于一股；保留供应商原值。
- **HNZ**：[2013-06-07 8-K](https://www.sec.gov/Archives/edgar/data/46640/000119312513258009/d555504d8k.htm)说明现金并购及申请在当日营业结束后停牌。06-07 的正量行情保留；身份与后来的 KHC 分开，FIGI 保留原搜索的 null。
- **FRX**：[2014-07-01 8-K](https://www.sec.gov/Archives/edgar/data/38074/000119312514259565/d749194d8k.htm)确认 NYSE 在当天开盘前已停牌。窗口止于 06-30，07-01 的零量平价报价留在原文。并购对价存在选项及比例分配，未将其补作分红或拆股；FIGI 保留 null。

原目录为 `tiingo-symbols-2026-10-16.json` 指向的完整 CSV；ETFC、HNZ、FRX 分别使用第 33638、51663、42368 行，原场所分别为 NASDAQ、NYSE、NYSE。搜索、目录行和实际永久价格键共同确定来源身份，没有用同名基金或继承方价格替代。

## 写入与验收

发布前按原主进程 PID 和启动时刻请求年度边界退出。PID 31380 于 2024 年窗口验收后正常结束；原组 `broad-ea371e54-122f-4156-bc76-4ac4519bb852` 的前 19 段、349306 行从原 128 份 Yahoo 响应独立重建，全部二十列与 prepared、原生回读文件及 PGWire 一致。

首次辅助发布在发送前因预检字段引用不匹配退出。已确认没有发布意图、没有候选原生运行和价格键；修正辅助程序后按原来源正常发布，没有重放正式写入。

三个新运行均使用已有的每页 1000 行、4 MiB 接口，共九页，直接通过原生验收：

| 来源 | 原生运行 |
| --- | --- |
| ETFC | `market-c2abe33f-942b-4c75-8119-87d0e387af26` |
| HNZ | `market-d35a0d55-6a69-401a-85e1-c1e2ef7871af` |
| FRX | `market-ac68b41d-8ed5-48f2-a2de-6a66b6223fe2` |
| 三条映射 | `market-695752fc-a5eb-4dbd-a87c-5700695ec366` |

写后日线 WAL 为 3541/3541，映射为 29/29，均无缓冲、未暂停；其他市场表的 WAL 与发布前一致。原物理目标 `us_equity_daily~3205`、`us_security_identifiers~3214` 保持。原 ETFC、HNZ、FRX 队列任务及主组成功前缀的原生记录保持，没有把替代来源导入当成原 Yahoo 任务已完成。

[只读查询](../../tools/sql/us-etfc-hnz-frx-source-history.sql)按原价格摘要及映射窗口关联，实测返回 7711 行、22 列，HTTP 和 PGWire 逐字段一致。

## 预算与恢复

本批四次身份、三次价格请求共七次，沿用 Starter 客户端限额：每小时 50 次、每日 1000 次、滚动 31 天 500 个请求键与 1 GiB，最少间隔两秒。05:22 UTC 的本地账本显示最近一小时 13 次、一天 221 次、31 天 123 个请求键，接收 225222873 字节，另预留 67174408 字节。失败请求也计数；这些是本地记录，服务端和其他客户端的用量仍未知。

验收后仅归档本轮停止标记，以 PID 39576、同一 v28 发行包的 47 个 JAR、两 GiB 堆和原 2026-10-16 截止日恢复主队列。恢复前没有活动或未决原生记录，旧 19 段与原来未发送的后两段分开保留。

恢复后只续接原来未发送的 2025、2026 年窗口，共 54507 行。2025 年原运行 `market-9917a31a-13cb-4438-a726-9cb6676652b1` 的 30251 行再次与原 Yahoo 投影、prepared、完整回读文件及 HTTP、PGWire 独立一致，原 19 段的记录保持。2026 年原运行是 `market-67909d32-1119-4a8b-bb1a-f6938405295d`，24256 行。

整组 128 个来源、403813 行、21 个年度窗口及成员均为 `VERIFIED`。随后重新从原 Yahoo 响应独立重建整组二十列，对照 prepared、原生完整证明及当前 PGWire 全部一致，原件和证明摘要、已完成队列前后稳定，没有 `IN_DOUBT`，主进程继续后续来源。整组行数没有重复计入本批手动补录的 7711 行。

本轮另对原 2017 年窗口做只读耗时测量：同一 5000 行通过本机及配置地址四次查询均在 0.094 至 0.203 秒返回，二十列一致；观测到主线程等待原生可见性核验，三个 WAL 样本均落后一笔。保留原表、索引和冻结预算，未据此迁表。

证据在 `var/market-data/scope-review/legacy-ehf-20261021/`：

- `identity-decisions.json`：身份、九个早期缺日、五年参照范围和两个边界后的原报价。
- `adjusted-volume-review.json`、`adjusted-volume-assessment.json`：拆股后成交量的独立算术核对。
- `before-publication.json`：原 19 段独立验收、旧价格与映射、原生记录及预算保护。
- `price-independent-verification.json`：7711 行二十列独立核对。
- `identifiers-independent-verification.json`：三条映射、旧 54 条映射、已执行查询和恢复前状态。
- `identifiers-manifest-v2.json`：摘要 `7cf73575f5016c46e363ef27894c2f93c1e54dad73a83ac79ff3d3f21bedf917`。
- `resumed-publication-independent-verification.json`：原前缀、恢复进程及 2025 年窗口的独立验收。

整组独立证明在 `var/market-data/scope-review/completed-ea371-20261021/completed-original-main-independent-verification.json`，原 ZIP 摘要为 `762f32a4e87d201a19930aa986f8dd0512bb0f800e0289f7d81042dfc158d6c6`。启动回执为 `var/market-data/us-broad-launch-v28-ehf-resumed.json`；查询耗时证据在 `var/market-data/scope-review/readback-performance-20261021/`。原件、批量回读、日志与编译文件继续留在本地忽略目录。
