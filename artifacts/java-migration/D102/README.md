# D102 · v_etf_market_overview_daily

状态：verified（隔离验收）；最终独立复核与协调 gate 通过，accepted_for_serial_progress。人工复核 pending_review，按序准许 D103。

普通视图直接聚合 `etf_share` 与 `etf_daily`，按完整代码和 timestamp INNER JOIN，再 `SAMPLE BY 1d ALIGN TO CALENDAR`。四个字段为精确 UTC 午夜业务日期、非负 LONG ETF 数量、可空有限 DOUBLE 总份额（万份）及总市值（亿元）。SQL 中只进行一次 `/10000.0` 换算，mapper 保留 null、原位 double 和 signed zero。没有 basic JOIN、基金类型或代码后缀过滤。

自然身份为 `trade_date`。普通视图没有自己的 WAL、分区、DEDUP、直接 writer、物化刷新 job 或持久化 checkpoint。Python `install_views()` 仅是缺失视图的部署入口；现有原始基表 owner 提供数据。Java 原有 D014/D016 job 默认写各自隔离目标，本卡没有将它们改为生产来源同步。D101 仍为独立 readthrough cache 的原 Python publisher 委托任务。

## 读取入口与边界

`EtfMarketOverviewDailyViewReadRepository` 提供 `findKey`、`findForDate`、`findRange` 与 `findPage`。READ 及真实配置的 ReadGroup 均使用四列 typed DTO。单日等值或最多 31 天的 increasing LocalDate 区间，单页最多 31 个日桶；区间结束日期不含当天。无界、错误日期载体、不完整投影和超预算请求在 JDBC 前拒绝。

视图的 valid 状态、目录、定义 SQL、状态更新时间，以及两个基表完整 schema、主时间精度、YEAR/WAL/DEDUP 完整键、物理 id/directory/txn 与独立 WAL sequence 都绑定在游标中。读前后必须稳定且两个源都 caught up。日期窗口和 LIMIT 约束结果桶，不证明原始 JOIN 扫描行数受 LIMIT 限制。每条 JDBC SQL 超时 20 秒；不宣称整个 ReadGroup 的绝对总期限。

WRITE、STATIC_REPLACE、WAL_REPLACE 在准备阶段拒绝。ReadGroup 取消验证覆盖成员启动前；本卡没有声明在途 SQL 取消能力。分页恢复使用相同查询和实际游标，来源向量或查询变化时重新读取。

## 当前实际证据

- [Java 纯测与读取回归](commands/java-pure-tests-20261006.json) 128 项，加上 [超时护栏](commands/java-metadata-deadline-guards-20261006.json) 中新增 1 项，合计 **129 个唯一 Java 方法**，旧 23 项 guard 是新 24 项的子集。
- [目录启动](commands/java-catalog-startup-20261006.json)：51 Dataset、40 job、D102 独立 job 0，未连接数据库或创建 ledger。
- [Python 脚本护栏](commands/python-historical-identity-leaf-guards-20261006.json)：81 个最终唯一方法通过（包含旧 39/59/74 项，不叠加重跑），包含严格 target、唯一 CREATE、UNKNOWN 无重试、完整源值、截断、取消和 PG 20 秒绝对操作期限。
- [正式库只读审计](commands/view-formal-readonly-audit-20261006.json)：2026-09-17、18、21 三日 12 个字段值、6 个 double 原位比较与原始 JOIN SQL 精确一致，零容差；五表及视图状态前后未变。
- [首次隔离验收失败](commands/view-isolated-acceptance-20261006.json)：历史 PID 仍被权威 CIM 观察到，在 CREATE 前拒绝。没有 CREATE claim、owner 调用或业务数据写入。
- [失败后的只读诊断](commands/failed-precreate-process-diagnostic-20261006.json)：当时五表仍等于 D101 accepted typed frontier，普通视图缺失。首批 7 个当前 PID 的独立复核通过，随后 [新的显式尝试](commands/view-isolated-acceptance-new-identity-20261006.json) 在 CREATE 前仍拒绝：[新只读诊断](commands/failed-precreate-identity-delta-diagnostic-20261006.json) 显示审计自身临时进程也分配历史 PID。两次均 0 DDL、无 CREATE claim。随后采用显式完整旧 OS birth/STOP 凭证规则，默认拒绝及 UNKNOWN 不重试保持；没有终止用户进程。

隔离源复用 D101 已验收的 11667 个完整真实行和 164180 字段值；本卡没有新增来源数据。D102 中没有诱发同键源修订或新的来源追加，来源漂移拒读由纯测覆盖；D101 的实际第三日追加保留独立来源证据。

## 有界示例

READ 组合请求的单个成员格式如下；配置应指向明确的查询目标，私有验证目标为 `127.0.0.1:18832/19020`。

```json
{
  "timeoutMillis": 30000,
  "members": [{
    "memberId": "etf",
    "datasetId": "v_etf_market_overview_daily",
    "definitionVersion": 1,
    "query": {
      "columns": ["trade_date", "etf_count", "total_share", "total_size_yi"],
      "equalities": {},
      "rangeColumn": "trade_date",
      "fromInclusive": "2026-09-17",
      "toExclusive": "2026-09-22",
      "pageSize": 1,
      "cursor": null
    }
  }]
}
```

使用既有 CLI `read-dataset-group --request=<保存的请求路径>`。后续页带真实 `nextCursor` 并保持相同查询；不手造来源版本。

正式库只读对照不认证 provider universe、全历史、current cache hit、latest 或生产 caller 切换。D101 正式历史回执的两项摘要异常未修复。

## 完成的实际验收

[唯一缺失视图创建与私有验收](commands/view-isolated-acceptance-history-rule-new-attempt-20261006.json) 已通过，CREATE ACK1；11667真实源行/164180字段原位回读和三日12字段/6DOUBLE比较通过。三次早先CREATE前FAILED保留，未提交DDL。完整旧身份规则审466 eligible与9 hard-deny；未知出生/不完整STOP不放宽，PID12620两次已知叶子复用不会当作同一个OS实例。

[Java实际验收](commands/java-view-read-acceptance-20261006.json) 私有与正式各三页1行、两日真实cursor恢复与重读、第三日窗口扩展、变化range拒旧cursor、按键回读、独立JDBC与实际配置ReadGroup均匹配；取消无page，批写及两种replace准备拒绝。五表/view元数据跨阶段及读前后稳定。合计[130Java与81Python唯一方法](commands/unique-test-inventory-final-20261006.json)，0失败/错误/跳过。本卡未发生源修订或追加。

DOUBLE比较严格限定在各实际目标内，原SQL/view/Java映射零容差；私有与正式之间保留4项原生聚合bits差异（9/17市值+7ULP；9/18份额+1ULP、市值-2ULP；9/21市值-4ULP），没有扩大容差或改SQL。捕获输入一致但物理布局不同，原生SUM归约顺序是可能解释；没有验证具体执行计划原因，不认证跨布局全局bits稳定。

原始 JDBC metadata snapshot 的 Timestamp JSON 是带默认本地时区解释的 epoch 毫秒载体，与 native UTC 时间相差 -8 小时并丢弃亚毫秒位，不能用它证明 UTC 时间相同。实际 reader 使用 getString，sourceVersion 绑定完整正确的 UTC µs 状态文本，独立复核逐项匹配两目标的 native 元数据；业务日期 mapper 未受该 metadata 表示差异影响。

最终协调记录：[D102 gate](coordinator-review-20261006.json)。
