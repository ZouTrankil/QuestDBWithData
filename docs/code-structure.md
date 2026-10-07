# 代码结构与分包约定

主应用使用 `com.zoutrankil.data` 作为根包，支持有限生命周期 CLI 和 WebFlux API。独立批处理入口位于 `com.zoutrankil.batch`。依赖规则与存量整改清单见 [架构检查](architecture-rules.md)。

```text
com.zoutrankil.data
├── QuestDataApplication.java       # 主应用启动与配置属性注册
├── bootstrap/                       # 参数分类、Spring 配置后的模式选择和 CLI 生命周期
├── cli/                             # 命令解析和命令行输出
├── config/                          # YAML 属性类、WebClient 和 QuestDB Bean
├── client/                          # Tushare 等外部服务客户端
│   └── dto/                          # 外部 API wire-level DTO
├── domain/                          # 业务数据模型
├── mapper/                          # 外部 DTO 与 domain 的显式转换
├── service/                         # 同步流程和业务编排
├── repository/                      # QuestDB / SQLite 数据写入与查询
└── web/                             # WebFlux 请求、响应和应用服务调用
```

## 依赖方向

- `cli` 调用 `service`，不直接访问 HTTP、JDBC 或 QWP。
- `service` 编排 `client`、`mapper` 和 `repository`，承载同步流程。
- `client` 只处理外部 API 和 wire DTO；`mapper` 负责日期/时间及字段语义转换；`repository` 只处理数据库访问。
- `domain` 放跨层使用的数据结构，不依赖 Spring、WebClient、JDBC 或 QuestDB SDK。
- `config` 负责将 YAML 映射到配置对象，并创建有生命周期的基础设施 Bean。

避免让 repository 负责调用 Tushare，也避免让 CLI 直接拼 SQL。新增功能优先放进已有职责明确的包，不为单个类随意再建一层嵌套包。

## 配置约定

- Spring 配置放在 `src/main/resources/application.yml`，通过 `@ConfigurationProperties` 分别绑定 `app.tushare` 和 `app.questdb`。
- QuestDB PGWire 连接池由 Spring Boot 的 `spring.datasource` 自动配置；本地 SQLite 连接由存储组件按事务管理并及时关闭。
- 基础设施客户端通过 Spring Bean 管理生命周期；借用的 QWP `Sender` 必须使用 try-with-resources 归还。
- 本地 CLI 命令以非 Web 模式启动，执行完成后关闭上下文。无命令时由 Spring 解析后的 `app.web.enabled` 选择模式，`--web` 可显式启动 Web。同步应用服务经 WebFlux 调用时使用有界阻塞调度器，避免占用事件循环线程。

## 命名与可见性

- 类型使用名词并采用 PascalCase；方法用动词短语；常量使用大写下划线。
- 对外跨包使用的类型和方法声明为 `public`；只在包内使用的实现保持包可见。
- 配置属性使用明确的前缀和单位。时长使用 `Duration`，不要把裸数字的单位藏在代码里。
- 新增 QuestDB 查询时明确列清单，使用参数绑定，并把 QuestDB 特有语法封装在 repository 中。

## 当前流程

`CommandLineRunner` 当前承载数据集、同步任务、分组、调度和恢复命令。各数据集的 owner/JobService 负责计划和运行，registry 验证定义与依赖；`LedgerManagementService` 统一取消、历史和状态查询的台账选择。早期 stock-basic 同步链仍由 `StockBasicSyncService`、`TushareClient` 和 `QuestDbStockBasicRepository` 协作。

表与 view DDL 位于 `src/main/resources/db/migration/questdb/`。`SchemaMigrationService` 通过 Spring 管理的 Flyway Bean 运行迁移；同步和查询 service 先迁移再访问 repository。SELECT 1 探活不触发迁移。新增模型步骤见 [模型开发流程](model-development-workflow.md)。
