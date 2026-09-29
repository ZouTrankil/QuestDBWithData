# F009 任务定义与版本管理

已实现 SyncJobDefinition、SyncJobOwner、SyncJobRegistry 和 `show-sync-job-definitions` 命令。
定义包含单数据及版本、模式、typed 参数 schema、策略引用、依赖版本、owner、有限重试/超时/批次预算、修订窗口、频率/时区及 enabled/dailyEligible。

注册表绑定真实 DatasetRegistry，要求 dataset schemaVersion 一致，支持模式必须由拥有者服务声明；策略名和依赖版本必须可解析，重复 ID、自依赖和循环拒绝。一个 catalog generation 只含每个 job 的一个版本，新 catalog 不会改动已有 FrozenRequest 的不可变定义和参数。历史快照落盘由 F010 完成。

`StockBasicSyncService` 是当前 sample 任务拥有者，仅声明真实支持的 SNAPSHOT；不虚构历史增量。样例 definition 默认 disabled、manual、非 dailyEligible，参数为空；后续 F011 接入预算化执行及激活。查看命令只输出定义，不发起同步或启动调度。

执行阶段 `prepare` 必须匹配显式 job/version 且 enabled；未知参数、错误类型、超长/重复列表、不支持模式、无限/反向/超预算区间拒绝。支持 INCREMENTAL 时必须将其作为默认模式。正常参数在 freeze 时深拷贝有界列表，不能由调用者后改。

证据：`job-definitions.json` 来自真实 SpringApplication 命令启动。8 项定向测试通过，包括模型、注册表及实际命令入口。初次实际启动发现 Jackson 缺少 Duration 序列化，已用显式 ISO Duration/LocalDate/ZoneId 序列化修复后复验通过。

本卡为定义和版本管理，不发请求、不写库、不推进 checkpoint；来源/QuestDB写后回读 N/A。既有 stock_basic 写入适配器的实际数据验收见 F008，不把本卡 JSON 输出当作数据验收。任务 runner/持久化账本/恢复/调度分别在 F010—F016。人工复核 pending_review。

复现：参考 Python 项目中运行 `uv run python C:/Users/zouqiang/IdeaProjects/QuestDBWithData/tools/run_local_validation.py --build-name local-F009-application-fixed --test '*SyncJob*Test'`。
