# F014 JSON 调用入口

通用命令：`read-dataset-group --request artifacts/java-migration/F014/read-request-example.json`。应用启动方式沿用已有 Java CLI；示例只请求已经注册的 stock_basic 快照与 latest view，不创建对象，不运行 sync，不写入。

每个成员必填 memberId、datasetId、definitionVersion 和 query。query 指定 columns、pageSize，可选 equalities、半开区间 rangeColumn/fromInclusive/toExclusive 和 cursor。所有完整业务键必须包含在投影中。

续页时，把该成员返回的 nextCursor 原样放入对应 query.cursor；保留数据定义版本、投影与过滤范围。其他成员可以独立保留或结束。业务日期用 ISO `YYYY-MM-DD`，Instant 用带偏移量的 ISO 时间；不能将数值 epoch 或无时区日期时间当作 Instant。精度按数据定义检查。

`ReadGroupJson` 在查询前拒绝重复 JSON 键、未知属性、错误类型/日期、整数溢出、缺失键、不匹配游标与超出 1 MiB 的输入。每组上限 64 成员、累计分页 100000 行、10 分钟边界预算；没有无限拉取。未知数据集或错误输入使请求预检失败；运行时单成员失败保留明确状态，CLI 输出结果后以失败结束，成功空页不等同失败。

生产注册的投影使用 DatasetValues，保留定义确定的 Instant、LocalDate、数值及 null 类型，JSON 只包含请求字段；也可为 Java 调用者注册具体 record mapper。sourceVersion 未提供时为 null，不把观察时间称为源版本；组合不保证跨查询原子快照。

`local-F014-json` 请求解析、游标往返、CLI 路由及已有命令回归通过。`local-F014-json-live` 真实 daily/etf_daily 续页将游标写入 JSON 后重新解析，再查询 QuestDB；每表 4 行，与独立 SQL 一致。探针定义不表示这两张业务表已经完成迁移。成功证据：`read-group-1b52bbe2-329e-4a2c-846d-10755d61a875.json`。
