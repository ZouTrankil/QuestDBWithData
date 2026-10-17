# 分包和依赖检查

总体设计看[项目规范](project-structure-and-standards.md)。这篇补充当前代码怎么组织，以及自动检查能管到哪里。

## 现在的依赖

```text
A 股数据入口 ──→ A 股数据 / 来源 + 美国利率来源
公共 data-app ─→ 通用数据引擎 + runtime-support
Batch 入口 ────→ A 股批处理 / 来源 + 美国来源
主策略入口 ────→ A 股数据 + A 股批处理 + 美国来源
实时 L2 ───────→ core + runtime-support
市场业务 ──────→ 对应通用引擎 ──→ core
```

主策略不依赖 A 股数据入口或 Batch 入口；公共 `data-app` 是支持库，可被市场业务复用，它不依赖任何市场。core 主代码只用 JDK。数据、来源和 Batch 引擎分成子项目，避免把 PGWire、MQTT 或定时器带到不需要的进程。

## 分包时怎么放

业务先分 `stock`、`etf`、`index`、`flow`、`margin`、`calendar`、`l2`、`factor`、`derived`。每个业务里面再放 domain、application、port、mapper、storage。各层职责见[项目规范](project-structure-and-standards.md#按功能分包)。

应用服务决定何时写、何时恢复；storage 执行 SQL、文件和物理换表。构造器和组件装配不发起采集、查询或建表。每次运行单独创建 writer/session，别把发送状态、临时表或取消标志共享出去。

有几处扩展入口可以优先复用：

| 要加什么 | 从哪里接 |
| --- | --- |
| CLI 命令 | 对应 `CliCommandFamily`，通过 `CliCommandRegistry` 注册 |
| 数据集读取 | `ReadBindingCatalog`，明确版本、行类型、映射和来源版本 |
| Batch 来源 | 来源 JSON 契约和 `SourceStrategies`，分别定义请求、行转换、覆盖和存储规则 |
| 跨业务分组写入 | `WriteGroupWriters` 工厂，每次创建独立会话 |
| 离线 L2 | `batch.l2` 的应用服务；CLI 只解析参数和输出结果 |

新增读取绑定不能重复 ID，启用的 READ 数据集必须有匹配版本的绑定。主程序和 Batch 共用的字段语义由 `DatasetSemantics` 等契约校验，但各自的分区、归档、分页和恢复规则继续分别维护。

## 迁包时别改掉这些行为

- ETF 持仓按公告日，份额按交易日；不同任务的日期、分页和空源规则不能混用。
- ST、HSGT 的中断发布恢复只接受已完整核验、严格整数零行的相应空源凭证。非空或损坏凭证不能继续换表。
- THS 成员的完整空响应只能确认原本就为空的板块，不能据此删除已有成员。
- stage 要绑定原运行、原目标和物理身份；正式读回和运行记录完成后才释放所属锁。不能按名字批量删除。
- 来源变更、未决写入、父子任务和取消传递，继续按各任务的协议处理。迁包不顺带启用禁用任务或扩大重试范围。
- 离线 L2 的文件结果、哈希和检查点一起发布。取消时先阻止继续发布，再停 worker；恢复前重查来源和结果，保留异常原因与线程中断状态。

更具体的写入恢复规则看对应应用服务和测试，不把旧重构任务编号当作运行说明。

## 自动检查

```sh
./gradlew architectureTest
./gradlew verifyModuleBoundaries verifyBatchDistributionDatabaseDrivers
./gradlew verifyDependencyVersionCompatibility
```

`check` 也包含相关检查。包依赖检查读取编译产物，并补查源码中的常量引用；不会初始化应用类。

**当前字节码检查覆盖全部子项目**，包括市场库、主策略和实时 L2。它核对类归属、core 只用 JDK、引擎和公共 API 不反向依赖市场、公共库不包含市场启动类、市场实现不互调，以及旧 Quartz 类名仍可加载。新增模块时要同步更新清单。

分层规则继续要求 domain、mapper、port 不依赖应用或存储实现，CLI/Web 不直接访问数据库。现有自动字节码检查主要验证模块边界，不声称已经穷尽每个包的分层违规。

`verifyModuleBoundaries` 核对声明依赖、jar 归属、重复类、旧构建残留和配置打包，报告在 `build/reports/modules/market-boundaries.json`。`architectureTest` 读取实际 class 文件检查引用；`architecture-exceptions.tsv` 保留原格式，不用它跳过新的模块边界。

模块和发行包检查另外验证依赖隔离：实时 L2 不加载数据 API、PGWire 或主策略，数据 API 和主策略不带 MQTT，Batch 包按允许的数据库驱动打包。通过包检查不等于通过了发行包检查，更不等于真实数据已经就绪。

构建输出为 `build/modules/<完整模块路径>/_output/`，父模块输出不会包含子模块输出，单独 clean 不会连带删除子模块。每个模块的 `verifyTestOwnership` 检查测试 class 和资源归属，聚合测试不能借用旧构建产物。
