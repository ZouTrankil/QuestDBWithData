# BHI、STJ 与 SIAL 历史验收

## 已完成的来源窗口

补入三家原发行人的 8153 条美国日线和三条永久身份映射。价格二十列、映射十八列与原文、prepared、HTTP、PGWire 独立一致；全部价格为正量来源记录，实有日期与各自窗口内的 SPY/Tiingo 参照逐日一致。旧保护组 199295 条价格、42 条映射，以及单独保留的 AETUF/Yahoo 休市日原记录保持；保护组现为 207448 条价格、45 条映射。

| 原代码 | 永久价格键 | 已验收窗口 | 行数 |
| --- | --- | --- | ---: |
| BHI | `US000000000378` | 2006-01-03 至 2017-07-03 | 2895 |
| STJ | `US000000002692` | 2006-01-03 至 2017-01-04 | 2771 |
| SIAL | `US000000002595` | 2006-01-03 至 2015-11-17 | 2487 |

三组均按唯一 Tiingo 搜索、原美国 `Stock/USD` 历史目录和官方公司行动核对，空 FIGI 保留为 JSON null。原目录行分别为 16887、94265、90866；目录结束日不直接当成最后成交日。[只读查询](../../tools/sql/us-legacy-industrials-history.sql)固定原发行人及窗口，8153 行、22 列已从两个查询接口独立核对。

## 公司行动和短来源

- [Baker Hughes 完成组合公告](https://investors.bakerhughes.com/news/press-releases/news-details/2017/Baker-Hughes-and-GE-Oil-Gas-Complete-Combination-Creating-the-Worlds-First-and-Only-Fullstream-Oil-and-Gas-Company-07-03-2017/default.aspx)确认原 BHI 交易至 2017-07-03 收盘，BHGE 从 7 月 5 日开始。最后一天报价仍含每股 17.50 美元特别分红的权利，公告支付日为 7 月 6 日；Tiingo 将该金额标在 7 月 3 日，保留来源日期和原报价。
- [Abbott 完成收购公告](https://abbott.mediaroom.com/2017-01-04-Abbott-Completes-the-Acquisition-of-St-Jude-Medical)明确 STJ 的最后交易日为 2017-01-04。其后不续填平价，也不拼接 ABT 行情。
- [OCC 公司行动通知](https://www.miaxglobal.com/sites/default/files/alert-files/SIAL_Merger_37832.pdf)说明 SIAL 的现金收购在 2015-11-18 开盘前完成，本次只接受至前一日的来源。每股 140 美元收购对价未补成现金分红；指数成分删除日不替代成交边界。
- [SIAL 年报](https://www.sec.gov/Archives/edgar/data/90185/000119312507041029/dex13.htm)记录每一股获发一股新股，于 2007-01-02 发放；价格原件在 1 月 3 日记录一拆二因子。两种日期含义分别保留，价格、股数及供应商调整值不改写。

LLTC 的唯一永久代码为 `US000000000901`，请求 2006 年以来价格却只返回 2016-01-04 至 2017-03-10 的 298 行，缺少 2518 个请求窗口内参照日，未达到五年要求，未发布价格或映射。[原 8-K](https://www.sec.gov/Archives/edgar/data/791907/000119312517078946/d340243d8k.htm)证明公司早已存续、合并后于 3 月 10 日收盘停止交易；供应商短窗口不解释为新上市。其收购现金及 ADI 换股比例也未加工成分红。

所有 `adj_*` 保留本次供应商当前调整值，不作为历史当时已经可用的证明。官方事实通过网页核对，本轮没有声称本地保存完整 HTML。

## 原运行和旧数据保护

BHI 原运行 `market-94aec803-51e2-4411-8b95-47c494bc6576` 的单页全部发送后回读超时。确认原 PID 45844 已退出，完整 2895 行二十列与原文及 prepared 一致、WAL 结清，再用既有只读恢复完成同一 Native；补写和重发均为零，原失败回执、prepared 和旧事件保留。STJ、SIAL 均直接验收。

| 来源 | 原价格 SHA-256 | 原 Native |
| --- | --- | --- |
| BHI | `55d96446c6ccd8b2481ec9ae71466bc87d78316d74b00a3030e78f3551f404a3` | `market-94aec803-51e2-4411-8b95-47c494bc6576` |
| STJ | `286a8cd31bbe9f5fc5254da0c802b7af1dc4649763ba5bddf58bdedb2323ced1` | `market-cdec2652-09cd-4815-ba9f-d8e92e02000a` |
| SIAL | `faf8767c791143b3221879a49af6aa3c1b02db1979e1765ea8d90bf37c942ea7` | `market-d3c875ff-0d9a-4c9e-8bb2-787d98ba00a4` |

三条映射由原运行 `market-d7e780a5-2fc0-4554-8a84-8734dedeb0a8` 发布并验收。价格物理表 `us_equity_daily~3205`、映射表 `us_security_identifiers~3214` 保持；本轮价格 WAL 从 3134 推进至 3137，映射从 24 至 25，事务已应用、缓冲为零且未暂停。

主采集在 2023 年窗口完成后正常停在原年度边界，原 18 段 313128 行已从 128 份 Yahoo 原文、prepared 和 PGWire 独立核对。三组补录期间，原 Native 事件、全部 21 个窗口及 BHI、STJ、SIAL、LLTC 原队列任务保持。补录结束后归档本轮自己的停止标记，继续原 v26、47 个未变 JAR、2 GiB 堆和 `2026-10-16` 截止日；原先计划中的后三年窗口继续执行。

## 预算和剩余范围

本轮 Tiingo 只增加四次搜索和四次有界价格请求，新增四个价格键，含未发布的 LLTC。本地累计 431 次请求、31 日内 108 个非空标的键、已收 214310701 字节，失败预留字节仍计入预算；Starter 上限保持，不能将本地账本视作整个账户的剩余额度。

另只读筛查了 578 份原失败 Yahoo 冻结来源，27 组的最近五年字段和参照日期可核对；其中六个交易所标的已有正式来源，其余 21 组多含大量零量报价，仍留待核，不据此宣布全部日线齐全。两个已知本机端点的同一 5000 行原样本均逐字段一致，单次回读约 1.6 至 2.5 秒，未因这个单次比较修改数据库地址；它没有独立证明历史超时的原因。

本机证据位于 `var/market-data/scope-review/legacy-industrials-20261021/`，含 `identity-decisions.json`、`LLTC-short-source-held.json`、`BHI-before-read-only-recovery.json`、`BHI-recovery-result.json`、`price-independent-verification.json`、`identifiers-independent-verification.json`、`saved-query-verification.json`、主采集前缀核验和续跑记录。原始大文件及完整回读保持忽略，不提交 Git。

全市场仍在采集。原来源缺口、NFE 复权冲突、LLTC 短来源及其他未验收任务继续保留，不以三家历史公司的成功替代全目标验收。
