# 宏观统计期同步

`cn_cpi`、`cn_ppi`、`cn_pmi`、`cn_m`、`cn_gdp`、`sf_month` 现在有正式 Java owner，写远端 QuestDB 的同名表。复用已有来源契约、原同步账本、整表锁、全部字段回读和 WAL 核验，不经过 Batch 隔离 writer，不自动建表或改列。

先明确统计期，再执行；最多一次 12 期。月份用月首，GDP 用季度末，业务日期另外填写。

```sh
./gradlew run --args='plan-macro-observations --dataset cn_cpi --from 2026-09-01 --to 2026-09-01 --logical-date 2026-10-20'
./gradlew run --args='run-macro-observations --dataset cn_cpi --from 2026-09-01 --to 2026-09-01 --logical-date 2026-10-20'
./gradlew run --args='resume-macro-observations --dataset cn_cpi --run 原运行ID'
```

缺任一期、来源达到上限或字段不符，整次在发送前停止。已有值允许按来源修订；已发送但未验收的运行保留原锁，恢复不能换日期、请求或物理表。

日批只在 A 股数据 API 常驻进程启用，配置在 `config/apps/cn-data.yml` 的 `app.sync.macro-observations`。默认关闭；开启后每天上海时间 19:10 重查最近六个月和四个季度。不是只看最大日期，也不按股票交易日历过滤。某来源未发布时记录未完成，其他来源继续核对。

统计期不是公布时刻。来源归档保存日期文本，旧表按 UTC 午夜承载日期，不加 8 小时；当前修订值不能证明当时已经知道。

2026-10-20 已拿到六条来源的新一期真实响应，见[来源探测](audits/2026-10-20-cn-macro-source-probes.json)。本地覆盖字段、修订、缺期、原运行恢复、换表和未决锁的回归已通过；正式写入、连续日更及原部署接管尚未验收。

社融的字段和月份参数对照 [Tushare 官方文档](https://tushare.pro/document/2?doc_id=310)。`inc_month`、`inc_cumval` 保留亿元，`stk_endval` 保留万亿元。
