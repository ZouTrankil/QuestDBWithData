# 融资融券汇总的正式范围发布

`margin_all` 已支持精确同名正式目标，使用独立作业 v2；原隔离 v1 和默认目标保持。本轮只完成真实来源、正式计划及私有发布验证，没有正式写入或启用日批。

```sh
APP_SYNC_MARGIN_ALL_WRITE_TABLE=margin_all \
./gradlew run --args='check-margin-all-source --date 2026-09-18 --logical-date 2026-10-21'

APP_SYNC_MARGIN_ALL_WRITE_TABLE=margin_all \
./gradlew run --args='plan-margin-all-job --mode BACKFILL --from 2026-09-18 --to 2026-09-19 --logical-date 2026-10-21'
```

日期换成实际已完成来源范围。执行用 `run-margin-all-job`，参数与计划相同；恢复只传 `--resume-from 原运行编号`。正式只允许明确的 `BACKFILL`，最多366个自然日，不从旧最大日期推导增量覆盖。原生产 writer、账本和未决记录未核清前，不另启正式写入。

## 来源与冻结日历

来源每天请求 `margin(trade_date=YYYYMMDD)`，保留九个字段，金额单位不改。`trade_date` 仍是业务日期的 UTC 零点载体。

[Tushare官方日历说明](https://tushare.pro/document/2?doc_id=26)要求北交所参考沪深日历。正式 v2 因此要求沪深每个自然日都齐备、无重复且开休市一致；不新增或伪造独立的 BSE 日历行。规则冻结为 `calendarPolicy=TUSHARE_CN_A_SHARED_V1`，日历编码及摘要一起进入原请求。编码中的 `7` 表示三交易所开市，`0` 表示全部休市，全年编码仍在4096字符预算内。

开市日来源必须恰好有 BSE、SSE、SZSE 各一行；休市日只能为空。缺交易所、重复键、日期越界、字段异常或达到4000行接口上限均拒绝完整。历史日期若无法取得这三项来源，须单列缺口，不能缩成沪深后算全表完成。

v2 行数预算改为366×3=1098；原 v1 的366行预算和请求指纹不改。发布与中断恢复还会根据原冻结日历重验来源，不能只相信采集阶段曾通过。

## 不加去重键，仍按原协议发布

原表是 YEAR/WAL、没有物理去重键，不能直接追加或发 UPSERT。正式执行继续原 stage/backup 协议：

1. 固定原物理代、稳定 WAL 和全部旧值摘要。
2. 完整复制请求范围外的旧多重集合；重复行及其全部原值都保留。
3. 逐日收齐来源。遗漏任意旧业务键时先拒绝，不把空响应当作删除授权。
4. 只向独立 stage 写入，逐批、逐日和全 stage 核对全部键值。请求范围内的重复汇总行按完整来源替换为每交易所一行；范围外不顺带去重。
5. 用耐久发布日志、原 OS 锁和区间锁重命名发布；保留 backup 和恢复原件。

正式准入不扩展到任意同结构表。隔离 DDL 和原隔离写定义仍拒绝正式名，没有 ALTER、改去重键或清理旧历史。

## 读取和恢复限制

公开汇总读取增加原锁／未决检查，分页前后核对物理代及 WAL；发布后旧游标不能跨代复用。范围外历史仍可能有重复键，读取原重复范围会拒绝，不能把本次补数当作全历史修复。

原发布恢复命令为：

```sh
APP_SYNC_MARGIN_ALL_WRITE_TABLE=margin_all \
./gradlew run --args='finish-margin-all-publication --run 原运行编号 --writer-stopped true'
```

`writer-stopped` 必须对应实际停止证据。此命令恢复已有发布；未发布分支核对丢弃stage后显式取消原运行，最后解除原锁，不重发来源。已发布分支随后使用[显式原运行收尾](margin-all-run-recovery.md)核验全部原件、认证原账本并解除原锁；未发布分支随后由用户执行resume，按原冻结范围整窗重抓，不复用已丢弃stage的分片。不要用新的请求绕过原锁。

## 配置和本轮范围

writer 使用 `app.sync.margin-all-write-table`，环境覆盖为 `APP_SYNC_MARGIN_ALL_WRITE_TABLE`。它兼容回退原 `app.sync.margin-all-table`，再回退 `java_d028_margin_all_acceptance`；原配置仍控制读取目标。默认读正式表、写隔离表的行为保持，曾覆盖旧读取配置的部署须明确核对两端。正式 v2 不自动参加日批。

实库现有4722行，查到2289组重复业务键；2026-09-18和19日都没有旧行。真实来源分别返回三交易所3行和休市空响应，正式两日只读计划通过；没有修改这些旧数据。

私有实例使用真实三行来源验证首次发布、修订、范围内重复替换、范围外重复保留、公开全字段读取和旧游标拒绝；原生重命名故障后的物理恢复通过，原运行仍为 `IN_DOUBT`。旧重复行、修订、日历和故障为夹具。单元回归另覆盖四种重命名故障位置、缺日历和缺交易所拒绝。

见[阶段证据](audits/2026-10-21-cn-margin-all.json)。未发布stage的显式取消及重新采集已在私有实例验收；正式补数、周期队列及消费者验收仍待完成，不计正式完成。

后续已补已发布分支的原运行收尾和最后解除原锁，私有实例验证后续更新及完整空源恢复通过。旧阶段的物理恢复记录保留原样，最新结果见[收尾证据](audits/2026-10-21-cn-margin-all-finalization.json)。
