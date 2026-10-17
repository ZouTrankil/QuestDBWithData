# 数据库结构怎么迁移

Flyway 在这里负责带版本的 SQL 迁移，不负责推断业务模型，也不覆盖所有数据集的运行时建表。当前这一条迁移链主要管理股票资料隔离示例的表和视图。

## 脚本放哪里

[data-app/src/main/resources/db/migration/questdb](../data-app/src/main/resources/db/migration/questdb/) 目前包含：

- `V1__create_stock_basic_table.sql`：股票资料 WAL 快照表，按日分区，去重键是 `(snapshot_ts, ts_code)`。
- `V2__create_stock_basic_latest_view.sql`：最新快照视图。

结构变更新增下一个版本，已经在环境里执行过的 SQL 不改。不能靠改 Java record 或加 `@Entity` 自动改表。

## 怎么执行

```sh
./gradlew run --args='migrate-questdb-schema'
```

`sync-stock-basic-questdb` 和 `show-stock-basic-latest` 也会先执行这条迁移。`verify-questdb-jdbc` 只执行连通性查询，不建表。

连接使用配置里的 `spring.datasource`。自定义选项在 `app.migration`，例如脚本路径和历史表 `java_tushare_schema_history`；`spring.flyway.*` 不控制这条调用链。

`SchemaMigrationService` 只记成功的迁移；失败就阻止后续操作。一个进程内的保护不代替跨进程协调，部署时安排一个迁移执行者。

## 已有库怎么接

1. 核对列、类型、指定时间列、分区、WAL、去重键和视图定义。
2. 已有对象与某个版本完全相同，才显式 baseline 到该版本。baseline 只登记历史，不创建或修复对象。
3. 结构不同时先做迁移方案，不自动删表，也不靠自动 baseline 绕过检查。

当前配置启用校验，禁用自动 baseline 和 clean。QuestDB DDL 不按可事务回滚处理，迁移失败先查哪些语句已经生效。`repair` 修历史记录，不恢复业务数据。

## 依赖和验证范围

目前 `data-app` 锁定 Flyway core `11.9.0` 和 QuestDB 社区模块 `10.26.0`，以模块构建和锁文件为准。升级时一起检查模块、core、Boot 管理版本和服务端兼容性，不只改一个版本号。

编译或插件加载通过，不能替代真实空库首次迁移、重复迁移、旧库接管以及迁移后的读写验证。现有旧文档里的测试快照不再作为本次环境的成功证明。
