# 代码结构与分包约定

项目使用 `com.zoutrankil.questdbwithdata` 作为根包。应用启动 Spring Boot 非 Web 上下文，业务流程由 CLI 命令触发。

```text
com.zoutrankil.questdbwithdata
├── QuestDbWithDataApplication.java  # Spring Boot 启动与配置属性注册
├── cli/                             # 命令解析和命令行输出
├── config/                          # YAML 属性类、WebClient 和 QuestDB Bean
├── client/                          # Tushare 等外部服务客户端
│   └── dto/                          # 外部 API wire-level DTO
├── domain/                          # 业务数据模型
├── mapper/                          # 外部 DTO 与 domain 的显式转换
├── service/                         # 同步流程和业务编排
├── repository/                      # QuestDB 数据写入与查询
└── storage/                         # CSV 等本地文件读写
```

## 依赖方向

- `cli` 调用 `service`，不直接访问 HTTP、JDBC 或 QWP。
- `service` 编排 `client`、`mapper`、`repository` 和 `storage`，承载同步流程。
- `client` 只处理外部 API 和 wire DTO；`mapper` 负责日期/时间及字段语义转换；`repository` 只处理数据库访问。
- `domain` 放跨层使用的数据结构，不依赖 Spring、WebClient、JDBC 或 QuestDB SDK。
- `config` 负责将 YAML 映射到配置对象，并创建有生命周期的基础设施 Bean。

避免让 repository 负责调用 Tushare 或写 CSV，也避免让 CLI 直接拼 SQL。新增功能优先放进已有职责明确的包，不为单个类随意再建一层嵌套包。

## 配置约定

- Spring 配置放在 `src/main/resources/application.yml`，通过 `@ConfigurationProperties` 分别绑定 `app.tushare` 和 `app.questdb`。
- 数据库连接池由 Spring Boot 的 `spring.datasource` 自动配置；不在 repository 中直接创建连接。
- 基础设施客户端通过 Spring Bean 管理生命周期；借用的 QWP `Sender` 必须使用 try-with-resources 归还。
- 本地 CLI 命令以非 Web 模式启动；WebClient `block()` 仅用于这个同步命令入口。若以后提供 WebFlux API，应该单独使用完整的响应式链。

## 命名与可见性

- 类型使用名词并采用 PascalCase；方法用动词短语；常量使用大写下划线。
- 对外跨包使用的类型和方法声明为 `public`；只在包内使用的实现保持包可见。
- 配置属性使用明确的前缀和单位。时长使用 `Duration`，不要把裸数字的单位藏在代码里。
- 新增 QuestDB 查询时明确列清单，使用参数绑定，并把 QuestDB 特有语法封装在 repository 中。

## 当前流程

`CommandLineRunner` 解析 `sync-stock-basic`、`sync-stock-basic-questdb` 和 `verify-questdb-jdbc` 命令；`StockBasicSyncService` 协调取数、DTO 映射、CSV 持久化或 QuestDB 写入；`TushareClient` 和 `QuestDbStockBasicRepository` 各自封装外部服务边界。

表与 view DDL 位于 `src/main/resources/db/migration/questdb/`。`SchemaMigrationService` 通过 Spring 管理的 Flyway Bean 运行迁移；同步和查询 service 先迁移再访问 repository。CSV 与 SELECT 1 探活不触发迁移。新增模型步骤见 [模型开发流程](model-development-workflow.md)。
