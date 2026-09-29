# F014 验收

`ReadGroupRequest` 为每个成员保存独立数据定义版本、过滤、投影、分页大小和游标。`ReadGroupReader` 串行读取，每个成员分别返回 typed page 或明确失败状态；任一失败使组合不完整，空页仍是成功读取。结果明确标记不提供跨查询原子快照，现有 QuestDB 表的 `sourceVersion` 为 null。

正式入口 `read-dataset-group --request artifacts/java-migration/F014/read-request-example.json` 使用严格 JSON 请求解析。生产注册当前只包含既有 stock_basic 快照和 latest view；其他业务数据须在各自迁移任务完成后注册。`read-stock-basic-group` 提供按代码分别分页的便捷入口。查询为只读，不执行 schema 迁移或写入；表或 view 未部署时成员返回失败。JSON 示例不是一次已执行的生产读取凭据。

真实只读验证启用 `QUESTDB_BOUNDED_READ`：daily 与 etf_daily 使用测试专用定义，在 2018-01-02 至 2018-01-06 半开区间分别分页 4 行；每表结果与独立显式 SQL 完全一致，非法投影状态为 FAILED，未伪装成空页。[实测证据](read-group-1b52bbe2-329e-4a2c-846d-10755d61a875.json)保留每页状态、SQL 和结果。测试专用定义不表示这两个数据集已完成业务迁移。

`local-F014-final` 启用 `QUESTDB_BOUNDED_READ` 与 `QUESTDB_WRITE_LIVE`：138 项，130 通过、8 跳过、0 失败、0 错误。随后新增既有 latest view 的生产注册，应用上下文、JSON 解析和命令定向测试通过。无数据库写入；取消在成员边界检查，单个 JDBC 查询另有 20 秒超时。人工复核为 pending_review。
