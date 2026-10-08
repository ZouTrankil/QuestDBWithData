# ST 原生年度来源与正式发布验收

核对时间：2026-10-08（北京时间）。本审计只读取已有来源文件、SQLite ledger 和 QuestDB；未调用 provider、启动生产任务或写库。

## 结论

新运行 `stk-st-daily-07b07a05-eadc-441f-b731-ff1a923daa41` 及发布 `stk-st-publication-34706de4-d190-451b-a78b-a23ea7c369ac` 均实际 `VERIFIED`。共享路由修复后，17个公告年度请求保留各自日期参数。独立从捕获的 ST 名称与有效区间展开，三日全部837条记录逐键及三字段值与正式表一致，没有清空原有记录。

| 日期 | 原始有效区间独立展开 | 日来源回执 | 正式表 | 旧正式键保留 |
| --- | ---: | ---: | ---: | --- |
| 2026-09-28 | 280 | 280 | 280 | 全部 |
| 2026-09-29 | 279 | 279 | 279 | 全部 |
| 2026-09-30 | 278 | 278 | 278 | 全部 |

2026年请求参数为 `20260101..20260930`，实际437行原始名称区间，其中157条含ST、147条覆盖目标三日。17年来源合计有281条唯一重叠区间；按生效起止日（含结束日）及股票代码去重得到上表。逐行确认代码、日期类型、有效区间、`is_st=1`、UTC午夜、唯一业务键；年度均低于5000行上限，17份非空 raw 指纹互不相同。所有年度文件 SHA 与每份日回执中17个引用独立复核，日文件SHA也独立重算。

Tushare的输入日期筛选公告日期，返回 `start_date/end_date` 是名称有效期，跨请求年度的有效期不是错误。现有四字段owner不返回 `ann_date`，本结论验证已捕获源、请求参数及有效区间展开，不证明未返回的发行人公告不存在。[官方接口口径](https://tushare.pro/document/2?doc_id=100)

## 发布与恢复证据

- 正式物理表：id **3025**，目录 `java_d012_stk_st_daily_stage_bab6164bf8ca43468c1cf0d59504c3f8~3025`。发布前stage WAL=11；rename后正式 WAL writer=sequencer=**12**、buffer=0、未挂起。本次窗口读取前后该身份和时钟完全一致。
- 独立重读837行共**2511个字段值**，与来源逐值一致；按pipeline全类型摘要算法重算为 `0acf2ae506eaa2382ea60e947b2c75121f243487131ca5a07f2dbdebf9e7323b`，等于新 `VERIFIED` flow receipt。
- 新flow文件为 `receipt-b0cde648-2689-4d61-8ac8-2ccc8e413130.json`，明确关联本次run。旧错误run的 `FAILED receipt.json` 保留，未作为完成依据。
- 实际 `VERIFIED` owner完整回读证明全历史 **830433** 行，before/after canonical SHA均为 `fa12c8bf111364f98c209af7941a53201a14b71e35b3abb0b2b7a18099cd79ac`；窗口外 **829596** 行 SHA为 `aae30857e1df86950b890908f9f88403a0d78295c287ec44214df0bd4402a2b0`。全历史/窗口外摘要引用已验证publisher proof，本独立审计未另重复长扫描。
- 原物理表1821保存在 `java_d012_stk_st_daily_backup_1e4ae59286ca4bb2851c251fa98445ce`。

机器证据：`stk-st-native-source-audit.json`、`stk-st-native-formal-window.json`、`stk-st-routing-fixed-ledger.json`；修复前缺陷证据：`stk-st-namechange-routing-audit.json`。
