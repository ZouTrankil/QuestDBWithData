# Flyway 管理 QuestDB schema

Flyway 负责按版本执行 SQL 迁移、记录迁移历史和校验已执行脚本。QuestDB 的支持模块是 `org.flywaydb:flyway-database-questdb`，通过 PostgreSQL JDBC / PGWire 连接数据库；它属于 Flyway 的社区数据库支持实现。[官方 QuestDB 模块说明](https://documentation.red-gate.com/fd/questdb-305791448.html)

本项目目前仍由 `QuestDbSchemaInitializer` 创建测试表及 latest view，尚未接入 Flyway。以下是接入约定与配置示例，本次仅记录文档。

## 职责

| 组件 | 职责 |
| --- | --- |
| Pydantic / JSON Schema | 数据契约、字段类型和校验约束 |
| Java DTO / domain / mapper | 数据承载、时间与字段转换、业务身份 |
| Flyway + QuestDB 支持模块 | 执行版本化 DDL，记录历史与校验和 |
| QWP / JdbcTemplate | 业务数据写入和查询 |

可以从统一模型契约生成初始 DDL，再把审阅后的 SQL 纳入 Flyway。Flyway 本身不会从 Pydantic 或 Java Entity 自动推导表结构，也不会自动实现 QWP 字段映射。QuestDB 的时间列、分区和 `DEDUP UPSERT KEYS` 仍需在迁移 SQL 中明确声明。

## Gradle 依赖放在哪一层

Spring Boot 应用启动时执行迁移，依赖放在项目顶层 `dependencies`，不是 `buildscript.dependencies`。本项目使用 Spring Boot 4，建议通过 Flyway starter 接入：

```groovy
// 示意：questdbFlywayVersion 需在接入时定义并锁定为已验证版本。
// 当前 build.gradle 已导入 Spring Boot BOM。
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
    runtimeOnly "org.flywaydb:flyway-database-questdb:${questdbFlywayVersion}"
    // 项目已有 PostgreSQL JDBC runtimeOnly 依赖，无需重复添加。
}
```

starter 用于 Spring Boot 的迁移自动配置；QuestDB 模块提供数据库适配。不要假定 Boot BOM 管理了社区 QuestDB 模块，也不要假定模块版本号必须和 Flyway core 相同。接入时检查模块 POM、实际解析到的 core 版本及目标 QuestDB 版本，锁定经过集成验证的组合。[Spring Boot 数据库初始化](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

若选择由 **Flyway Gradle 插件**执行迁移，插件的构建类路径依赖应写成：

```groovy
// 构建脚本片段；需另行配置 Flyway Gradle 插件及其连接参数。
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath "org.flywaydb:flyway-database-questdb:<已验证的模块版本>"
    }
}
```

因此，`buildscript { dependencies { implementation ... } }` 不适合直接照搬。应用运行类路径和 Gradle 构建类路径是两套配置；Gradle 插件也不会自动读取 Spring 的 `application.yml`。本项目希望沿用 YAML 和现有 DataSource，优先采用应用启动集成。[Flyway Gradle 配置说明](https://documentation.red-gate.com/flyway/reference/usage/gradle-task)

## Spring YAML 配置

接入后合并到现有 `spring` 节点，复用 `spring.datasource` 的 PGWire 地址、用户名和密码：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration/questdb
    validate-on-migrate: true
    baseline-on-migrate: false
    clean-disabled: true
  sql:
    init:
      mode: never
```

应用启动时迁移失败应中止启动，避免同步任务在不匹配的 schema 上运行。启用自动迁移后，包括 CSV 命令在内的启动也会需要数据库；若要保留独立 CSV 使用方式，可通过专用 profile 关闭 `spring.flyway.enabled`。

## 迁移目录和脚本

```text
src/main/resources/db/migration/questdb/
├── V1__create_stock_basic_table.sql
├── V2__create_stock_basic_latest_view.sql
└── V3__add_new_column.sql
```

`V1` 可从当前初始化器提取，空库首次迁移的建表语句示例：

```sql
CREATE TABLE java_tushare_stock_basic_qwp_test (
    snapshot_ts TIMESTAMP,
    ts_code SYMBOL,
    symbol SYMBOL,
    name STRING,
    area SYMBOL,
    industry SYMBOL,
    list_date STRING
) TIMESTAMP(snapshot_ts) PARTITION BY DAY WAL
  DEDUP UPSERT KEYS(snapshot_ts, ts_code);
```

`V2` 创建依赖该表的 latest view。后续变更新增迁移文件，已经执行的版本化脚本不要直接修改；view 定义更新也应新增迁移，而不是依靠 `CREATE VIEW IF NOT EXISTS` 更新已有对象。

Flyway 记录的是执行历史，不会保证每次启动都发现所有手工 schema 漂移。需要对照实际列和 key 时，仍应检查 QuestDB metadata。

## 从当前初始化器迁移

1. 空测试库使用完整的 V1、V2 迁移；确认表、view、时间列和 dedup key 与当前实现一致。
2. 对已由初始化器建表的实例，先核对实际结构，再决定显式 baseline 的版本。baseline 会跳过相应及更早版本的迁移；不能仅因表已存在就自动认为历史结构完整。
3. 接管后移除同步路径中 `QuestDbSchemaInitializer.initialize()` 的建表职责，使 Flyway 成为唯一 schema 管理入口。现有 `create-questdb-schema` 命令也应统一到迁移入口。
4. 验证迁移重跑无新增操作、脚本校验失败可被发现、迁移完成后正常读写，并检查 view 结果。当前文档没有验证具体模块/core/服务端组合。
5. QuestDB DDL 的事务和回滚能力按目标版本处理；迁移失败后先检查已生效的数据库对象。Flyway `repair` 修复迁移历史元数据，不会自动撤销已执行 DDL 或恢复业务数据。

schema 由 Flyway 版本化管理后，Java 模型、DDL 和写入映射仍应遵循同一份字段契约，参见 [模型规范](model-schema-conventions.md)。
