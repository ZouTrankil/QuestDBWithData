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

## Gradle 模块结构

```text
QuestDBWithData/
├── data-core/              # 数据与批处理共享的契约和基础类型
├── data-app/               # 数据 CLI、WebFlux API、数据 owner 与 QuestDB 访问
├── batch-app/              # 独立批处理应用与 L2 计算
├── main-strategy-runtime/  # 主策略组合运行时，连接数据和批处理应用
├── config/                # 共享 application.yml 配置
├── deploy/                # Batch / L2 部署脚本与配置示例
├── docs/                  # 架构约定、数据规范与操作说明
├── tools/                 # 可重复使用的生成、读取、清洗和验证工具
└── schema-export/         # Java 模型生成器使用的 schema catalog
```

根项目负责聚合构建、测试和兼容的启动入口，不再包含生产源码。`data-app` 和 `batch-app` 分别依赖 `data-core`，主策略组合由 `main-strategy-runtime` 负责。所有入口共用根目录 `config/application.yml`；公共段只定义一次，`data`、`batch`、`l2` profile 保存各入口的差异。Gradle 将同一文件打入两个应用的分发包。

所有默认构建产物集中在顶层 `build/`，子模块使用独立子目录，避免类文件、资源和报告相互覆盖：

```text
build/
├── libs/                   # 根项目兼容 JAR
├── distributions/          # 应用分发包
├── reports/                # 根项目聚合报告
├── test-results/           # 根项目聚合测试结果
└── modules/
    ├── data-core/          # 各模块自己的 classes、resources、libs、reports 等
    ├── data-app/
    ├── batch-app/
    └── main-strategy-runtime/
```

根 `./gradlew :clean` 清理整棵 `build/`；`./gradlew :data-app:clean` 只清理该模块的输出。启动和打包任务通过 Gradle 的模块输出引用解析路径，不需要修改启动命令。L2 清洗脚本等显式隔离运行仍可覆盖构建目录。Gradle 缓存位于 `.gradle/`，运行台账位于 `var/`，与构建产物分开管理。

依赖方向：`data-app → data-core`、`batch-app → data-core`、`main-strategy-runtime → data-app + batch-app`。各模块的回归测试位于自己的 `src/test/`。根 `src/test/` 原用于跨模块契约检查；本次清理开始前，其测试源码和 daily-t12 资源已在工作区删除，本次未恢复或扩展这些删除。

## 依赖方向

- `cli` 调用 `service`，不直接访问 HTTP、JDBC 或 QWP。
- `service` 编排 `client`、`mapper` 和 `repository`，承载同步流程。
- `client` 只处理外部 API 和 wire DTO；`mapper` 负责日期/时间及字段语义转换；`repository` 只处理数据库访问。
- `domain` 放跨层使用的数据结构，不依赖 Spring、WebClient、JDBC 或 QuestDB SDK。
- `config` 负责将 YAML 映射到配置对象，并创建有生命周期的基础设施 Bean。

避免让 repository 负责调用 Tushare，也避免让 CLI 直接拼 SQL。新增功能优先放进已有职责明确的包，不为单个类随意再建一层嵌套包。

## 配置约定

- Spring 配置放在 `config/application.yml`，通过 `@ConfigurationProperties` 分别绑定 `app.tushare` 和 `app.questdb`。
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

表与 view DDL 位于 `data-app/src/main/resources/db/migration/questdb/`。`SchemaMigrationService` 通过 Spring 管理的 Flyway Bean 运行迁移；同步和查询 service 先迁移再访问 repository。SELECT 1 探活不触发迁移。新增模型步骤见 [模型开发流程](model-development-workflow.md)。

## 历史文件清理（2026-10-16）

本次按实际入口与引用清理，保留四个业务模块的生产源码、测试、配置、SQL 迁移和依赖锁文件。

| 清理类别 | 删除文件数 | 原始文件字节数 |
|---|---:|---:|
| 历史运行快照、探针、临时构建脚本和旧测试报告 | 3,122 | 536,874,083 |
| 旧迁移任务卡、逐项验收草案、证据快照和过期报告 | 615 | 6,310,250 |
| 一次性迁移、审计、补录、核对脚本及其配套测试 | 89 | 1,257,376 |
| 合计 | 3,826 | 544,441,709 |

删除的脚本包括 `accept_d*`、`admit_d*`、`audit_d*`、`prepare_d*`、`record_d*`、`review_d*`、`test_d*`，以及旧迁移 inventory、证据验证工具和依赖其他机器路径的 `run_local_validation.py`。它们用于当时的有限验收或补录，不属于应用启动链。

删除的文档包括 `migration-tasks-20260929/`、`migration-acceptance-review/`、`java-batch-20260929/evidence/`、过期迁移状态与差距报告、已结束的架构改造和 L2 抽样验收报告。Batch 来源、冻结计划、恢复规则及 OpenAPI 说明继续保留。历史文件可从 Git 历史查阅；本次未创建提交。

`tools/` 保留 9 个文件：

- `generate_domain_models.py` 与 `test_generate_domain_models.py`：根据 schema catalog 生成模型，并验证生成器。
- 五个 `read_l2_*.py`：L2 来源独立读取和比对工具；当前正式入库已使用 Java 原生 Parquet reader，历史 live 验收仍引用这些工具。
- `run_l2_native_cleaning.ps1`：归档解压、清洗、断点恢复入口。
- `run_offline_tests.ps1`：Windows 离线验证入口；其根契约测试选择依赖根 `src/test/`，该目录在本次清理前已被删除。

首轮清理后曾保留 D001–D006、D085–D105 下的历史输入和验收证据。后续提交 `dda9048` 已移除剩余整个 `artifacts/` 目录（1,598 个文件）。当前源码中仍有测试引用旧 `artifacts/java-migration/` 路径；运行这些测试需要从 Git 历史恢复相应输入，或另行迁移到正式测试资源目录。下文早期测试结果记录的是当时输入仍在时的结果，不表示删除整个目录后的全量回归已通过。

`schema-export/` 虽带日期，但模型生成器及其测试仍使用其中的 `domain-catalog.json`，因此保留。`gradle/dependency-*.json` 由依赖兼容性检查使用，也继续保留。本地 `var/`、SQLite/WAL 台账、开发者工作文件与已有未提交修改保持原样。

后续首轮精简移除了 D002、D003 下 20 个无源码或构建脚本直接引用的历史运行目录，共 184 个文件、329,869,411 字节（约 314.59 MiB），包括当时的发现、全量 owner、staging、发布和恢复探针记录及其 SQLite 运行账本。这些记录可从清理前的 Git 提交找回；历史评审里对它们的引用仅作历史线索。保留根目录的 `physical-baseline.json`、`source-catalog.csv` 等测试输入，其余较小文件和 live 验收证据留待后续逐项核实。

本轮清理验证：日历映射、证券资料映射、指数目录文件和同花顺指数映射的 12 项测试均通过。完整 `:data-app:test` 共运行 2,384 项，其中 156 项失败、142 项跳过；失败报告未引用本轮删除的目录，完整测试未通过。

提交前识别并清理了 D003 下 12 组旧 `prepared-*`、`prepared-recovery-*` 和 `publication-*` 运行快照，共 83 个文件、979,628 字节，包含 8 份旧实验 SQLite 账本；这些删除现已包含在并行生成的 `dda9048` 提交中。日常运行的 `var/` 台账继续保留。旧构建输出（约 493 MB）与本地日志（约 3.3 MB）作为可再生成文件清理，不纳入提交。

新的 `artifacts/` 输出统一忽略，历史目录已从当前版本移除；各模块 `src/test/resources/` 中的测试资源继续由 Git 管理。构建目录与 Gradle 缓存可重新生成，本次不将清空开发环境缓存作为代码清理的一部分。

### 本次验证

最终提交前，在整个 `artifacts/` 已移除的状态下，跨四模块选取的 70 项专项测试全部通过，模块边界、Batch 打包驱动及依赖版本检查通过；这次未运行仍依赖旧 artifacts 输入的测试。

- 模型生成器 10 项 Python 测试通过。
- 直接使用保留输入的四组 Java 测试通过，共 12 项、无失败或跳过：`ExchangeCalendarMappingTest`、`StockDetailInfoMappingTest`、`ThsIndexMappingTest`、`IndexCatalogFileSourceTest`。
- `verifyModuleBoundaries`、`verifyBatchDistributionDatabaseDrivers`、`verifyDependencyVersionCompatibility` 通过。
- 维护文档的本地 Markdown 链接检查通过，清理改动的空白检查通过。删除计划中的文件均已移除，已有测试输入路径保留；被移除的四个测试代码引用均为写出位置，运行时会重新生成。
- `:test` 全量回归未通过：已出现 CLI 命令集合、Spring 装配、发布/恢复及 Parquet 等测试失败，随后长时间停留在 `SqliteRuntimeTest.lateSourceRebindsSameBatchInstanceOnlyBeforeAnyWriteIntent` 的 Batch 执行中，已主动停止。期间工作区另有配置与业务源码的并行修改，未在此次清理中修改这些实现，也未将本次结果认定为整仓回归成功。完整输出保存在本机 `/tmp/questdb-cleanup-tests.log`，专项验证输出在 `/tmp/questdb-cleanup-focused.log`。
