# F007 单数据有界读取

QuestDbBoundedReader 是通用读取实现；DatasetReadQuery 使用定义中的逻辑字段名，声明显式列、等值/NULL过滤、左闭右开范围、1–10000行页大小和游标。查询值通过PreparedStatement绑定，标识符来自DatasetDefinition白名单。主查询LIMIT为页大小+1，20秒超时；schema预检也有行数和超时上限。没有使用OFFSET或无上限全表装载。

完整业务键按定义排序，游标使用字典序条件，并绑定查询范围、过滤、列投影、模型schema及来源版本。错范围/错版本游标、错误Java类型、未知过滤字段和缺失完整键投影在执行前拒绝。实际schema不匹配、空主键、页内/跨页边界重复键拒绝生成游标。DatasetValues按名称严格获取类型和null，dataset mapper返回typed record；返回definitionVersion、sourceVersion及observedAt。

TIMESTAMP/DATE/TIMESTAMP_NS在SELECT转为LONG载体，Java按明确的微秒/毫秒/纳秒转换；过滤参数再显式CAST回原存储类型。这样避免PGWire的无时区timestamp被JDBC按默认时区解释，且不经微秒中转损失纳秒。官方背景见[QuestDB PGWire时间处理](https://questdb.com/docs/connect/compatibility/pgwire/overview/)与[时间类型](https://questdb.com/docs/query/functions/date-time/)。业务日期为LocalDate、时刻为Instant、技术时间单独标记；非有限数字或不支持的排序类型不能悄悄强制转换。

现有QuestDbStockBasicRepository和StockBasicLatestRepository已接入分页读取；StockBasicSyncService提供loadLatestStockPage。旧findLatest最多10000行，超过则明确要求分页，不能返回静默截断列表。schema自动生成record未修改。StockBasicDataset.LATEST描述已有V2视图，不代表新增数据任务已完成。

## 实际验收

`live-read.json`保存实际查询和返回：daily单股2018-01-02至2018-01-06跨页4行、etf_daily相同范围跨页4行，与独立SQL逐日期/代码/close/可空字段一致；同一交易日的复合键继续读取三页共6个股票代码，与独立排序查询前6个一致。累计14行对照，覆盖TIMESTAMP和TIMESTAMP_NS业务日期。投影顺序刻意不同于定义/物理顺序，仍按名称映射。

非零纳秒另外通过真实服务器SQL literal→TIMESTAMP_NS→LONG读取验证为1790643723123456789；该项是传输精度探针，不冒充真实来源事件。单元测试覆盖错误类型、参数注入字符串仅作为绑定值、未知过滤、范围反向、NULL、查询/版本绑定、纳秒与重复键边界。

最终隔离构建：54项测试，46通过、8个未启用的外部集成测试跳过，失败0；其中F007的5项单元测试及1项真实读取均通过。报告位于 `var/local-F007-final-build/test-results/test/`，避免其他本地测试进程覆盖。

本项实际读取是既有表上的公共读取验证，没有迁移daily/etf_daily业务模型，也没有写入数据库。源sync、写入、增量checkpoint在F007为N/A。真实数据无可证明的不可变sourceVersion，因此返回null；稳定键排序不等于跨查询原子快照，并发修订场景须由调用方冻结版本/范围并复核。人工复核 pending_review。

## 连接修复与复现

最初使用application.yml共用凭证连接PGWire返回 `invalid username/password`，不是SQL或分页错误。随后使用参考Python项目 `D:/work/fund_2/back-monitor/config/database.py` 经config.env加载的现有PG凭证，仅在Java子进程的SPRING_DATASOURCE_USERNAME/PASSWORD中覆盖，原目标地址保持不变，认证与实际查询通过。未在文档、日志或命令参数中保存密码，未修改用户application.yml及QWP凭证。

已提供可复现入口（从Python项目目录运行，使用其uv依赖）：

```powershell
uv run python C:/Users/zouqiang/IdeaProjects/QuestDBWithData/tools/run_local_validation.py --build-name local-F007-final --enable QUESTDB_BOUNDED_READ
```

该入口仅导入参考项目配置，隔离Gradle构建目录并显式控制live测试开关；配置目标仍由Java工作区决定。直接不带进程覆盖运行原PG凭证仍可能失败，不能把本次环境修复误写成已修改用户配置。

调用示例：构造包含完整业务键的DatasetReadQuery，通过loadLatestStockPage取得typed page；hasMore为true时将nextCursor传给query.after，再读下一页。调用者同时设置总页数/时间预算；超预算保持未完成，游标不作为sync checkpoint。F008将负责写入后按完整键和值回读，F010/F011再管理checkpoint。
