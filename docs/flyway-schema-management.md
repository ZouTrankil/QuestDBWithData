# Flyway 管理 QuestDB schema

本项目已接入 Flyway：Spring 管理 Flyway Bean，复用 YAML 配置的 PGWire DataSource，由 service 在数据库业务操作前执行迁移。表和 view 定义位于 `src/main/resources/db/migration/questdb/`，repository 只负责数据读写。

## 依赖与版本

```groovy
dependencies {
    implementation('org.flywaydb:flyway-core') {
        version { strictly '11.9.0' }
    }
    runtimeOnly 'org.flywaydb:flyway-database-questdb:10.26.0'
}
```

这是应用运行依赖，放在顶层 `dependencies`。`buildscript.dependencies` 管理 Gradle 构建类路径，应使用 `classpath`，不是 `implementation`。

QuestDB 社区模块 10.26.0 的父 POM 声明 core 11.9.0；本项目 Boot 4.1.1 BOM 默认管理 core 12.4.0，因此显式锁定模块声明的组合。采用 Flyway Java API 和自定义 Spring 配置，未引入 Flyway starter 或 Gradle 插件。模块注册与类路径已通过单元测试，但不等同于真实 QuestDB 服务端兼容性验证。

依据：[Flyway QuestDB 支持](https://documentation.red-gate.com/fd/questdb-305791448.html)、[模块父 POM](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-community-db-support/10.26.0/flyway-community-db-support-10.26.0.pom)、[Gradle 插件类路径](https://documentation.red-gate.com/flyway/reference/usage/gradle-task)。升级时应成组核对模块、core 和目标 QuestDB 版本。

## YAML 和执行时机

`application.yml` 中：

```yaml
app:
  migration:
    location: classpath:db/migration/questdb
    history-table: java_tushare_schema_history
```

连接地址和凭据复用 `spring.datasource`，通过 PGWire 8812 执行 DDL。`SchemaMigrationConfiguration` 固定启用校验，禁用自动 baseline 和 clean，并关闭迁移事务包裹；QuestDB DDL 不应假定能事务回滚。这是项目自定义 `app.migration` 配置，`spring.flyway.*` 不控制这条执行路径。

| 命令 | 是否迁移 |
| --- | --- |
| `migrate-questdb-schema` | 执行待应用迁移；旧 `create-questdb-schema` 是别名 |
| `sync-stock-basic-questdb` | 先迁移，再获取数据和写入 |
| `show-stock-basic-latest` | 先迁移，再查询 view |
| `verify-questdb-jdbc` | 仅 SELECT 1，不创建数据库对象 |
| `sync-stock-basic` | 仅 HTTP/CSV，不执行迁移 |

```bash
./gradlew run --args='migrate-questdb-schema'
./gradlew run --args='sync-stock-basic-questdb'
./gradlew run --args='show-stock-basic-latest'
```

`SchemaMigrationService` 在同一进程内只记录成功的迁移；异常向上传递，阻止后续业务读写。不同进程仍由 Flyway 检查迁移历史。部署时安排一个迁移执行者，不把本地进程内同步当作跨进程锁保证。

## 版本化 SQL

```text
src/main/resources/db/migration/questdb/
├── V1__create_stock_basic_table.sql
└── V2__create_stock_basic_latest_view.sql
```

V1 建立 WAL 快照表，指定 `snapshot_ts` 时间列、DAY 分区及 `(snapshot_ts, ts_code)` 去重键。V2 创建 latest view。新字段或 view 定义变更新增 V3、V4 等迁移；已经在环境中执行过的脚本保持不变。

迁移历史和校验和用于跟踪脚本执行；Flyway 不会自动从 Entity/Pydantic 生成 SQL，也不会发现所有人工 schema 漂移。模型契约可生成待审阅 SQL，再作为迁移文件提交。

## 接管已有数据库

当前 V1/V2 面向空库，使用明确的 CREATE，避免 `IF NOT EXISTS` 掩盖旧对象与迁移定义不一致。已有测试表/view、但没有迁移历史的实例，不能直接假定可用：

1. 核对现有表列名、类型、designated timestamp、WAL、分区和 UPSERT KEYS，以及 view 定义。
2. 若已有对象完全等价于 V1 和 V2，可通过受控 Flyway 运维流程显式 baseline 到版本 2；baseline 只记账，不创建或修复对象。
3. 若仅存在等价的 V1 表，可核对后 baseline 到版本 1，再迁移 V2。若结构不同，先制定数据迁移方案。
4. 不开启自动 baseline 跳过检查，不为绕过异常自动删除旧表或修改历史。

迁移失败时检查已生效的 DDL 后再修复。`repair` 修复迁移历史元数据，不会恢复业务数据或撤销 DDL。旧 `QuestDbSchemaInitializer` 已移除，schema 统一由 Flyway 管理。

## 已验证与待验证

- 已验证：Java 编译、实际解析的依赖版本、QuestDB 插件可被 core 发现、迁移资源进入类路径、迁移失败不会标记成功。
- 尚未执行：真实 QuestDB 空库的 V1/V2 首次迁移与再次迁移、旧库 baseline 接管、迁移后的 QWP 写入及 JDBC 查询。需要可用测试实例和正确凭据后验证。
- Flyway 接管 schema 不改变 QWP/WAL 异步可见性；读写限制见 [读写规范](questdb-usage.md)，模型定义步骤见 [模型规范](model-schema-conventions.md)。
