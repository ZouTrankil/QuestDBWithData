# 代码从哪看

开发规范和按 A 股、美股、BTC 拆分的目标结构见 [项目结构与开发规范](project-structure-and-standards.md)。本页只介绍当前已存在的代码。

项目里有六个 Gradle 模块，主要是为了让不同程序分开启动：

- `data-app`：数据 API、CLI 和 QuestDB 访问。
- `batch-app`：批处理和离线 Level2 计算。
- `main-strategy-runtime`：把数据和 Batch 能力接起来，单独运行主策略。
- `l2-live-app`：实时 MQTT 采集。
- `data-core`、`runtime-support`：给其他模块共用的基础代码。

大概的依赖关系是：数据和 Batch 共用 `data-core`、`runtime-support`；主策略组合这两边；实时 L2 单独运行，只用共享启动支持。

## 数据采集怎么走

从 `data-app/src/main/java/com/zoutrankil/data/` 开始找。`stock/`、`etf/`、`index/`、`factor/`、`flow/` 这些目录按业务分开。

可以先看 `stock/application/StockBasicSyncService`。它拿数据、做格式转换、调用写入，再检查 QuestDB 有没有读到结果。顺着这条线，你会看到来源客户端、Mapper 和存储代码是怎么配合的。

新一些的功能通常把代码分成几层：

- `domain`：业务里的数据和规则。
- `application`：把一次业务流程串起来。
- `mapper`：转换外部数据和业务数据。
- `port`、`storage`：定义存储接口和具体实现。

老一些的功能还会用 `service/`、`repository/` 这种通用目录，所以别假设每个业务都长得一模一样。

## Batch 和主策略

`batch-app` 从 `com.zoutrankil.batch.BatchApplication` 启动。想看作业怎么跑，先看 `job/`；想看数据怎么采集和写入，再看 `source/`、`writer/`；运行状态和检查点主要在 `storage/`，保存在 SQLite。

`main-strategy-runtime` 是单独的启动程序，会组合数据模块和 Batch。`l2-live-app` 则只管实时 MQTT 采集，归一化数据和累计检查点保存在类型化 Parquet，SQLite 保存原始接收 WAL、状态和提交引用。

## 建议阅读顺序

先看 `QuestDataApplication`，弄清楚程序怎么启动；再沿一个 `stock/` 同步流程往下读；之后看 Batch，最后再看主策略和实时 L2。

配置放在 [`config/application.yml`](../config/application.yml)。怎么启动各程序，见[配置说明](configuration.md)。
