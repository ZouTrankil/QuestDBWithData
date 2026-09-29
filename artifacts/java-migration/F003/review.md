# F003 数据定义与单一注册

DatasetDefinition 定义版本、来源、owner、字段 source/logical/storage 映射、业务键、物理去重键、主时间语义、分区/WAL、读写能力及依赖。DatasetImplementation 由实际 adapter 实现，DatasetRegistry 在 Spring 启动时建立不可变注册表，拒绝重复逻辑/物理身份、未知依赖和依赖环。

现有 QuestDbStockBasicRepository 已实现该接口，其表名与读写能力检查引用同一 StockBasicDataset 定义。只登记现有 Java stock_basic 样例，未将184个生成投影自动注册为已实现数据集。owner=java_stock_basic_sample 表示现有样例代码的归属，不代表Python任一数据表owner已确认。

校验拒绝重复字段、未知/可空键、丢失完整业务维度的去重键、缺主时间语义、分区无主时间、dedup无WAL或缺主时间键。View/MV不得声明直接写入或UPSERT KEY，普通View不得声明分区/WAL。无dedup声明必须保留存储理由，后续数据任务另行核实该理由与来源身份。

命令 `.\gradlew.bat run --args='show-dataset-definitions' --console=plain` 已实际执行，输出完整JSON定义，不访问来源或运行迁移。该定义是契约，不宣称现有样例已经满足后续分页/增量/读写验证框架。

Python参考：`D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/config/tables.py`，其按domain导入再集中注册的结构；未改Python项目。

## 验收

普通 `gradlew test` 通过。显式 `QUESTDB_DEFINITION_READ=1` 运行 `--tests '*DatasetDefinition*Test'`，3项契约测试加1项真实读取测试全部通过。真实测试从既有daily表读取3行、核对所声明的3列类型、完整样本键、LocalDate语义与close数值类型，同时确认应用注册表只有现有stock_basic实现。证据为 definition-live-read.json，包含查询、实际返回和注册schema摘要。

daily的三列只是F001既有表上的只读测试投影，不注册、不创建目标表，不作为D007完成证据。首次测试因SQL列名column/type未引用返回HTTP400，已修复引号后真实重跑通过，不将400当空数据。source sync、写入、checkpoint在本项N/A；后续F004–F012分别实现并验收。人工复核 pending_review。
