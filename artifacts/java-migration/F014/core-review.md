# F014 核心及真实读取复核（进行中）

`ReadGroupRequest` 显式列出成员、定义版本及各自 `DatasetReadQuery`；最多 64 个成员、合计 100000 行页预算，正的有限时间预算。`ReadGroupReader` 绑定已注册定义与类型映射，每成员一次有界查询，独立版本、观察时间、游标和错误。错误成员没有 page，成功空页与错误明确区分。取消后保留已读结果，后续成员不再查询。时间预算在成员边界检查，单次 JDBC 调用沿用 20 秒超时。

`local-F014-core` 核心 4 项通过。`local-F014-live-fixed` 核心 4 项及真实测试 1 项通过，未跳过。真实查询 daily 和 etf_daily，只读投影探针沿用 F007 的定义，不代表这些业务数据已经迁移。前者 pageSize=1、后者 pageSize=2，各自推进游标，与独立 SQL 的日期、代码、close 全值对照一致。错误字段投影单独失败，其余成员正常读取。

证据：[read-group-227c1a63-a6ba-413a-a52e-7f7b73efd093.json](read-group-227c1a63-a6ba-413a-a52e-7f7b73efd093.json)。sourceVersion 为 null，明确没有跨查询原子快照。没有 sync 或写入。

首次真实测试在全部查询断言通过后，因缺少 Instant JSON 序列化而导出失败。已在 `JobDefinitionJson` 补充 ISO Instant 序列化并重跑通过；`read-group-ea229c61-34d3-4ed3-aa2c-a6023384a470.json` 是失败时的不完整文件，不得用作验收。

正式调用入口、请求/游标解析以及全套回归待补齐。F014 保持 running，累计完成 13 项，人工复核 pending_review。
