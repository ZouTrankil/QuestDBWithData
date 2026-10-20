# 配置和启动

运行配置都在根 `config/`。入口文件是 [application.yml](../config/application.yml)，日志格式是 [logback-spring.xml](../config/logback-spring.xml)。公共、市场和进程设置分开维护，程序自动按 profile 加载。

| 文件 | 放什么 | 自动加载条件 |
| --- | --- | --- |
| `application.yml` | QuestDB、Tushare、公共日志、profile 分组和配置导入 | 所有入口 |
| `markets/cn.yml` | A 股时区、研究目录、离线 L2 数据目录、ETF 复权、日线与份额新计划布局 | `market-cn`；现有市场进程自动带上 |
| `markets/us.yml` | 美股时区及后续市场设置 | `market-us` |
| `markets/btc.yml` | BTC 时区及后续来源切分设置 | `market-btc` |
| `apps/cn-data.yml` | A 股数据 API、schema 位置、离线 L2 读取、财务日批和 Batch 托管开关 | `data` |
| `apps/cn-main-strategy.yml` | 主策略参数、端口和日批开关 | 参数供 `data` / `main-strategy` 使用；仅策略 profile 默认启用策略日批 |
| `apps/cn-batch.yml` | Batch 端口、SQLite、归档和任务计划 | `data`；`batch` 供控制 CLI 和隔离测试使用 |
| `apps/cn-l2-live.yml` | 实时 L2 订阅、会话和本地存储 | `l2` |
| `apps/us-data.yml` | 美股行情目录、核心证券列表、Tiingo/Yahoo 请求预算 | `us-data` |
| `apps/btc-data.yml` | BTC/ETH 历史起点、目录及公开来源请求预算 | `btc-data` |

美股和 BTC 分别通过 `us-data`、`btc-data` 带上 `market-us`、`market-btc`。入口仅运行明确的有限采集任务；命令和恢复约束见[基础行情](us-market-data.md)。core、engine、公共 API 库不单独放一份运行配置。

私密值统一放本机 `config/application-local.yml`，已从公开 YAML 迁出。该文件被 Git 忽略，不进入 JAR 或通用发行包；本机原值保留，没有更换凭据。新环境复制[空白示例](../config/application-local.example.yml) 填写。API 缺 token 时拒绝受保护请求，健康检查仍公开。

内置加载顺序是公共 → 已启用的市场/应用 → 本机覆盖；环境变量和 CLI 仍优先。`main-strategy` 会自动带上 `data` 和 `market-cn`，其他现有进程会带上 `market-cn`，不用自己拼 profile。部署在别处可用 `APP_LOCAL_CONFIG` 指定实际文件 URI，可选文件加 `optional:`。检查公开配置及跟踪文件：`python3 tools/check_public_config.py`；检查发行文件再加 `--package 路径`，脚本需要 PyYAML。

## 怎么启动

在项目根目录运行，需要 JDK 24：

```sh
# 数据 API，默认 8080
./gradlew webRun

# 列出同步任务，执行后退出
./gradlew run --args='show-sync-job-definitions'

# 主策略管理 API，默认 8081
./gradlew mainStrategyRun

# 兼容命令：启动同一个 A 股数据服务，同时启用 Batch 托管
# 数据 API 默认 8080，Batch 管理 API 默认本机 9085
./gradlew batchRun

# 只看实时 L2 计划，不连接 MQTT
./gradlew aliyunL2Live --args='plan --symbols 563360'
```

数据 API、主策略、实时 L2 各自启动。Batch 合入数据 API 进程，`jdb.enabled` 默认 `false`；启用后随进程启动和关闭。`batchRun` 是启用 Batch 的兼容启动命令，不要再与同一个数据 API 实例重复启动。

数据入口实际在 `markets/cn/data-app`，也可以直接用 `./gradlew :markets:cn:data-app:run` 启动。根 `batch-app` 已删除。`platform/app-support` 是公共支持库，没有 run 任务；美股、BTC 在各自市场的 `data-app` 装配。

## 常改的值在哪里

| 要改什么 | 配置位置 |
| --- | --- |
| QuestDB 主机、端口、账号、密码 | `app.questdb` |
| Tushare 地址和 token | `app.tushare` |
| 研究项目目录 | `markets/cn.yml` 的 `app.paths.research-root`，默认 `../back-monitor` |
| 数据 API 和主策略 API 的 token | `app.web.api-token` |
| Batch 管理 API 的 token | 本机覆盖中的 `jdb.api-token` |
| 主策略方案、文件路径和日批 | `apps/cn-main-strategy.yml` 的 `app.sync.batch` |
| 是否在数据 API 托管 Batch | `apps/cn-data.yml` 的 `jdb.enabled`，也可用 `JDB_ENABLED=true` |
| Batch 定时任务 | `apps/cn-batch.yml` 的 `jdb.schedules` |
| 实时 L2 本地目录和会话 | `apps/cn-l2-live.yml` 的 `l2.root`、`l2.session`；账号放本机覆盖 |
| 日志目录和级别 | `logging.file.path`、`logging.level.root` |

配置已有本项目使用的实际默认值，可以直接改。QuestDB 当前主机是 `100.64.153.106`，HTTP `9000`、PGWire `8812`、数据库 `qdb`；URL 引用这组值，改主机或端口会一起变化。

`app.questdb.direct-connection: true` 让指定数据库绕过本机 JDK 的 HTTP/SOCKS 全局代理，其他站点继续用原代理。HTTP 是否需要 Authorization 也看配置，当前为空。

管理 API 使用 `Authorization: Bearer <配置值>`。Batch 控制工具自己读取同一配置，不用把 token 填进每条命令。文档不复制真实凭据。

实时 L2 的私有订阅账号还需要填写；`plan`、`replay` 无需账号。主策略方案、批准摘要和计划文件也必须真实存在，有默认路径不等于输入已经准备好。

## 市场时间在哪里配

`markets/cn.yml`、`markets/us.yml`、`markets/btc.yml` 分别设置 `app.time.markets` 中自己的市场，`cn` 是 `Asia/Shanghai`，`us` 是 `America/New_York`，`btc` 默认 `UTC`。A 股和美股不接受随意改成固定偏移量，BTC 可以按交易所契约设置切分时区。

旧 `app.time.business-zone` 继续表示 A 股；与新 A 股值冲突会拒绝启动。实时 L2 的 `l2.session.zone` 也要与它一致。事件时间仍存 UTC，不迁移现有数据库时间值。详见[市场时间](market-time.md)。

## 换环境或临时覆盖

可以直接改 YAML，也可以用标准环境变量覆盖，例如 `APP_QUESTDB_HOST`、`APP_TUSHARE_TOKEN`、`APP_WEB_API_TOKEN`、`JDB_API_TOKEN`。项目不会自动读取根目录 `.env`。

如果经常在两个环境切换，可以在入口 YAML 最后加自己的配置段：

```yaml
---
spring:
  config:
    activate:
      on-profile: office
app:
  questdb:
    host: 192.168.1.20
```

启动前设置 `SPRING_PROFILES_ACTIVE=office`，只覆盖这一环境不同的值。JVM 参数和命令行参数也按 Spring 规则覆盖。

## 部署时带哪些文件

发行包会带完整公开 `config/` 目录，并在应用 jar 中保留同一份默认值。部署时复制整个目录，保留 `markets/`、`apps/` 的相对路径；不要只复制 `application.yml`。本机覆盖文件另外配置。

各应用只激活自己的设置。公共连接可复用；某个进程需要不同数据库、端口、日志或 token 时，在它的应用文件或带 profile 的本机覆盖文档里设置同名属性。不要把应用 profile 写到公共默认激活列表。

## 日志和运行文件

日志默认分别在 `logs/questdb-data/`、`logs/main-strategy/`、`logs/questdb-l2/`。可改 `logging.file.path` 和 `logging.level.root`，或对应环境变量 `LOGGING_FILE_PATH`、`LOGGING_LEVEL_ROOT`。

`var/` 保存 SQLite 运行记录、恢复进度和提交索引。实时 L2 的解析结果和累计检查点在 `l2.root/parquet/batches/`，SQLite 保存原始接收日志、状态和文件引用；备份、恢复时两边一起保留。

Batch 已保存到 SQLite 的定时任务设置，不会自动被 YAML 重置。修改已有任务要走管理操作或迁移。更多操作见[批处理说明](batch.md)和[数据库维护](questdb-maintenance.md)。

启用托管前配置 `jdb.api-token`（至少 24 位），继续使用原 `jdb.metadata-url` 和 `jdb.archive-root`。先停止旧 Batch 服务，再启动托管，不能让两个进程同时占用账本。`jdb.scheduling-enabled` 仍控制 Quartz 是否运行；新登记的触发器仍暂停，已有触发器保留原状态。有限 CLI 即使传 `--jdb.enabled=true` 也不会启动 Batch。

`batchDistZip` 保留旧发行文件名和控制脚本，内部已改用统一 A 股入口，包含数据 API 的驱动和配置。旧 `questdb-batch` 脚本现在也会启动数据 API，端口和依赖按市场应用处理。

## 按父子工程构建

顶层业务工程只有 `platform` 和 `markets`，配置仍统一在根 `config/`。Gradle 子项目路径与目录一致，旧模块路径不再使用；根级 `webRun`、`batchRun`、`mainStrategyRun` 和 `aliyunL2Live` 不变。

```sh
# 只构建公共模块及其检查
./gradlew :platform:build

# 构建全部市场模块，或只构建 A 股
./gradlew :markets:build
./gradlew :markets:cn:build

# 日常开发只验证涉及的子模块
./gradlew :markets:cn:data-app:test --tests '*CnBatchConfigurationTest'
```

父工程的 `build`、`check`、`assemble` 聚合所属子模块；`platform:test` 和 `markets:test` 聚合各自测试。`markets:cn:test`、`platform:engine:test` 仍只测试本模块，整组检查用对应的 `check`。`clean` 只删除对应模块自己的输出；全部产物在 `build/modules/<完整模块路径>/_output/`，父子目录互不覆盖。目录迁移后的首次构建需要重新编译，后续继续使用 Gradle 增量检查。

主策略的 `app.sync.batch.input-schema` 默认 `v1`，只支持 `v1/v2`，标准环境覆盖为 `APP_SYNC_BATCH_INPUT_SCHEMA`。新 Batch v4 请求冻结该值；CLI/Web 和评分阶段要求批准清单与选择一致。`coverage-inputs/coverage-inputs-sha256` 是可选的覆盖组装输入，根配置保留空默认值；两项须成对配置，不表示来源已验收。旧 v3 任务不随协议配置自动升级，见[主策略数据说明](main-strategy-java-data.md)。

ETF 复权存储布局由 `app.sync.etf-adj-schema-version` 选择，公开默认值在 `config/markets/cn.yml`，默认 `1`，仅支持 `1/2`；环境覆盖用 `APP_SYNC_ETF_ADJ_SCHEMA_VERSION`。它同时装配数据应用的新作业、计划、目标和读取注册，不自动迁移旧表。旧单表请求和组成员仍按原冻结版本恢复。Batch 独立来源任务与正式调度尚未切换，见[布局分版决定](decisions/2026-10-21-交易日存储布局分版.md#配置与组入口)。

ETF 日线使用独立的 `app.sync.etf-daily-schema-version`，默认 `1`，只支持 `1/2`；环境覆盖为 `APP_SYNC_ETF_DAILY_SCHEMA_VERSION`。它保留纳秒日期载体，与复权表的微秒布局分开。新作业和读取同步选择，旧请求按原账本恢复；不自动迁表或改 Batch 独立来源任务。

ETF 份额使用 `app.sync.etf-share-schema-version`，默认 `1`，仅支持 `1/2`；环境覆盖为 `APP_SYNC_ETF_SHARE_SCHEMA_VERSION`。布局 v1 对应原作业 v3，布局 v2 对应作业 v4。旧请求的 `observedAt` 与各行已存 `update_time` 分别保留，不用新时间覆盖历史。生产端口仍限 D016 隔离目标，不自动接管正式表。
