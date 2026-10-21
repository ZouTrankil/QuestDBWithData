# BCR、BRCD、BMS 与旧 BID 历史

本轮新增四家原发行人的 12852 条 Tiingo 日线及四条永久映射。价格二十列、映射十八列均经独立原文投影、prepared、HTTP 和 PGWire 核对，五个原 Native 直接通过，未重发或恢复补写。四份价格原文及并购边界之外的 23 条报价全部保留。

| 原代码 | 永久价格键 | 行数 | 首日 | 本批末日 |
| --- | --- | ---: | --- | --- |
| BCR | `US000000000255` | 3019 | 2006-01-03 | 2017-12-28 |
| BRCD | `US000000011784` | 2991 | 2006-01-03 | 2017-11-16 |
| BMS | `US000000001169` | 3381 | 2006-01-03 | 2019-06-10 |
| 旧 BID / Sotheby’s | `US000000001139` | 3461 | 2006-01-03 | 2019-10-02 |

所选记录全部价格为正、量为正，与同范围 SPY/Tiingo 实有日期逐日一致，无重复键或参照外日期。参照不是独立的官方完整历史日历。本批保留供应商原始和调整 OHLCV、现金分红及拆股字段，不重算或拼接其他供应商的价格。

## 并购与代码边界

- BCR：[Bard 的 SEC 8-K](https://www.sec.gov/Archives/edgar/data/9892/000119312517383532/d489045d8k.htm)确认 2017-12-29 完成收购、股票已停止 NYSE 交易。本批只保存至 28 日的正量来源；29 日零量平价记录留在原文。每股 222.93 美元及 0.5077 股 BD 是并购对价，不填成分红。
- BRCD：[Brocade 的 SEC 8-K](https://www.sec.gov/Archives/edgar/data/1009626/000119312517347092/d490894d8k.htm)明确 2017-11-17 开市前停止 NASDAQ 交易，因此截止 16 日。来源 17 日仍报告 1169333 股正量，不能仅凭正量越过已确认停牌边界；该行及 20 日零量报价保留待核，没有改成零或删除原文。每股 12.75 美元并购现金不填成分红。
- BMS：[Bemis 的 SEC 8-K](https://www.sec.gov/Archives/edgar/data/11199/000110465919034809/a19-11365_18k.htm)确认 2019-06-11 开市前暂停 NYSE 交易。本批截止 10 日，其后十二条零量平价报价保留。每股旧股换 5.1 股 New Amcor 是并购交换，不当成旧 BMS 拆股。
- 旧 BID：[Sotheby’s 的 SEC 8-K](https://www.sec.gov/Archives/edgar/data/823094/000082309419000038/a8-kclosingxbrl.htm)确认 2019-10-03 完成并购，已要求 NYSE 在生效后尽快暂停交易。本批选择至 2 日的正量来源，其后八条零量平价原报价不发布；每股 57 美元并购现金不填成分红。

当前 `BID` 的既有 Yahoo 序列属于 Tribeca Strategic Acquisition Corp.，本次独立保护其 64 行全部字段。Tiingo 完整搜索同时返回当前公司、旧 Sotheby’s 和一个澳大利亚主体，不能按代码相同合并。旧 Sotheby’s 使用上述永久价格键；当前 BID 原价格、原任务和来源均保持。

## 正式写入和保护范围

原主 PID 39928 在年度边界正常退出。原组 `broad-371ba503-2aaf-476d-854a-2e10c410f883` 的前十二段、160451 行，写前及新增完成后均从 128 份原 Yahoo 响应独立重建，逐字段核对 prepared 与 PGWire；原 Native 条目、事件、21 个窗口边界和来源摘要保持。

四个原价格运行分别为 `market-7df5ec95-1d42-4fd2-b357-ad2cc9da03cd`、`market-aecbf2ba-143c-49f2-83ab-6689d1167511`、`market-6ad7ade4-9a29-45c6-80d1-b3b9c347cb26`、`market-65ed11ca-4b7e-4374-95db-cbec85c0258b`。每个新运行按已有预算接口冻结 1000 行、4 MiB 小页，合计十五个分页；等待上限及完整键值、WAL 门槛保持。原主队列的发送页不改写。发送前核对主写入方已退出、无未决锁且四个永久价格键均为空，发布沿原全局写入锁执行。

原 79990 条核心 Tiingo 日线、207448 条既有补录价格、2510 条近期 OTC 报价、当前 BID 的 64 行全部字段保持。四条新标识沿已验收的完整价格运行登记，原 45 条标识保持，总计 49 条；原 `openFIGIComposite=null` 没有补造。原 Yahoo 失败任务及新 BID 的成功任务不因本批导入而推进或替换。

`us_equity_daily~3205`、`us_security_identifiers~3214` 和原 v27 的 47 个 JAR 摘要保持。恢复前两个 WAL 分别结清于 `3291/3291`、`26/26`，缓冲为零、未挂起，无未决条目或区间锁。

## 消费和证据

[只读查询](../../tools/sql/us-historical-b-pre-acquisition.sql)通过永久标识、原价格摘要和明确日期关联，返回二十个价格字段、原代码及发行人名称，共 22 列。已实际执行全部 12852 行，并逐列与独立原文预期、HTTP 和 PGWire 核对；旧 BID 不拼到当前 BID 序列。

本批行情与身份共八次 Tiingo 请求，均走原 Starter 共享预算，只新增四个价格请求键。BRCM 本地目录只覆盖 2016-01-04 至 29 日，不足五年，本轮没有再请求该极短价格来源；其原 Yahoo 失败任务保持。客户端预算不能代表账户在其他应用中的服务端余额。

00:25 UTC 本地滚动账本为最近一小时 8 次、一天 224 次、31 天 112 个非空标的请求键；累计接收 217513754 字节，另有原预留 58785799 字节。本批请求均已收到响应，没有借清零账本或取消旧预留扩大额度。

本地证据在 `var/market-data/scope-review/historical-b-20261021/`：来源卡和原文、官方网页核读记录、原窗口保护快照、五个原 Native、`price-independent-verification.json`、`identifiers-independent-verification.json`、`saved-query-verification.json` 及恢复前证明。原大文件、SQLite 和编译文件不加入 Git。

价格、映射及保存查询证明的 SHA-256 分别为 `061ae4b38e9c5118c05b774cd4a6b5b9fad96d84e98635c66de7b99db4951af0`、`01ad53cb4cde7344435ab91af58962bddb07d0fa3cc773162934589aa0cd83b5`、`b656f0b9fd0f0bb5935e3718278ed67c86765a2fb978eb45cd812b6142e55ca4`。SQL 本身的 SHA-256 为 `75d368788f6e7ec249b1eaa4ff76289a8e79c8ec2c7d83dd39f4c6bc6e266ba4`。

补录后以同一 v27、47 个 JAR、两 GiB 堆和原截止日 `2026-10-16` 恢复，启动回执为 `var/market-data/us-broad-launch-v27-historical-b-resumed.json`。新 PID 37092 于 00:17:47 UTC 启动；00:22 UTC 又独立确认原 2018 年窗口 22058 行二十列与原文、prepared、HTTP 和 PGWire 一致，原十二段及全部新旧保护组保持，无未决写入。原运行 `market-4810ed1b-0f59-4c8f-a7e8-5eff8094332f` 和原队列继续使用冻结边界，没有用新请求替代原窗口。证明为 `resumed-publication-independent-verification.json`，SHA-256 为 `1ab5251ca86bb393035246deff66ef80ec2a77014d6b161101d2e60bab8a4cf2`。

此前主采集的另一个原组 `broad-af7314d4-1b13-47f1-8a19-8e2f21cf25c7` 已完成 21 段、397167 行二十列独立核对，来源、Native 和组级证明摘要全部通过；它不计作本批新增的 12852 行。完整全市场目标仍有待采集、来源失败及身份待核范围。
