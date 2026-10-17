# 分包和依赖检查

总体设计看[项目规范](project-structure-and-standards.md)。这篇补充当前代码怎么组织，以及自动检查能管到哪里。

## 现在的依赖

```text
data-app ───────────────→ data-core + runtime-support
batch-app ──────────────→ data-core + runtime-support
main-strategy-runtime ──→ data-app + batch-app + runtime-support
l2-live-app ────────────→ runtime-support
```

主策略暂时组合两个应用模块，core 也还没完全拆干净。这是现状，不能照着复制成新市场架构。

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

**当前包扫描只包含 `data-core`、`data-app`、`batch-app`。** 主策略、实时 L2 和未来市场模块不能借此声称已全部通过包检查。新增模块时要同步扩展检查范围。

主要检查 domain、mapper、port 不依赖应用或存储实现，应用服务不直接操作数据库，CLI/Web 不直接调用 repository/client，data-app 不反向依赖 batch-app。

结果写到 `build/reports/architecture/current-violations.tsv`。待整改的旧依赖登记在 [architecture-exceptions.tsv](architecture-exceptions.tsv)，必须写精确类型名、实际整改任务和原因；不能用包通配符。新增违规、或已经消失但仍保留的例外都会失败。

模块和发行包检查另外验证依赖隔离：实时 L2 不加载数据 API、PGWire 或主策略，数据 API 和主策略不带 MQTT，Batch 包按允许的数据库驱动打包。通过包检查不等于通过了发行包检查，更不等于真实数据已经就绪。
