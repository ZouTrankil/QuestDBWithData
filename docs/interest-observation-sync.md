# 利率观测同步

`shibor`、`hibor`、`shibor_lpr` 已有正式 Java owner，写远端原表。沿用 v2 的 `observation_date` 逻辑键、原日期列及数字开头的期限列；没有改名、平移日期或缩放利率。

```sh
./gradlew run --args='plan-interest-observations --dataset shibor --from 2026-10-15 --to 2026-10-20 --logical-date 2026-10-20'
./gradlew run --args='run-interest-observations --dataset shibor --from 2026-10-15 --to 2026-10-20 --logical-date 2026-10-20'
./gradlew run --args='resume-interest-observations --dataset shibor --run 原运行ID'
```

一次最多 31 个自然日，逐日冻结原文、核对日期和全部期限值。不套股票日历，不假定 LPR 固定哪天返回。完整空响应有单独证据，保留空日，不生成零利率。`VERIFIED_EMPTY` 只证明请求范围内接口返回空，不代表这一期报价已经发布或全历史齐全。

日批只由数据 API 常驻进程托管，配置为 `app.sync.interest-observations`，默认关闭。启用后每天上海时间 18:40 重查最近 31 个自然日，接收晚到和修订。更早缺口用明确范围补数。原运行未决时保留锁，不自动重发；恢复不能换日期或物理对象。

2026-10-20 的[只读样本](audits/2026-10-20-cn-interest-target-preflight.json)确认：SHIBOR 10 月 19 日、HIBOR 10 月 16 日有来源响应，但实库缺键。当前配置的 LPR 接口查询 8 月 1 日至 10 月 20 日，只返回 8 月 20 日；[区间响应](audits/2026-10-20-cn-lpr-range-probe.json)中这条与实库三个字段一致。不能据此补造 9、10 月数据，也不能宣称源方没有发布。

[LPR 官方接口](https://tushare.pro/document/2?doc_id=151)支持日期区间和 `date`、`1y`、`5y` 字段；历史介绍含旧报价制度，本实现不拿它推算当前报价日。

本地已验证多日键、合法空日、修订、范围错误、原运行恢复和闰日。实库只做结构、WAL 与样本回读，没有正式补数或启用调度。
