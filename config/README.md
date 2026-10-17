# 配置放哪里

所有配置都在这里，不在各模块再维护一份。

- `application.yml`：公共数据库、数据源、日志和配置导入。
- `markets/cn.yml`、`us.yml`、`btc.yml`：各市场的时区和共用设置。
- `apps/cn-data.yml`：A 股数据 API、schema 和财务日批。
- `apps/cn-main-strategy.yml`：主策略参数和独立进程设置。
- `apps/cn-batch.yml`：Batch、SQLite、归档和调度。
- `apps/cn-l2-live.yml`：实时 L2 订阅、会话和存储。
- `application-local.yml`：本机覆盖，不入库、不打包；可从空白示例复制。
- `logback-spring.xml`：共用日志格式，各进程日志目录仍分开。

各入口自动选择自己的 profile。主策略继承 A 股数据组件的基础配置；美股、BTC 不加载 A 股应用。没有独立进程的公共库不用再建配置文件。

想让某个进程用不同数据库、端口或日志，就在它的应用文件里覆盖公共属性。本机覆盖还可以按 profile 分文档；环境变量和命令行优先。不要复制整套配置。

部署时带整个公开目录，保留 `markets/`、`apps/` 的相对路径，本机覆盖另放。详细步骤看[配置和启动](../docs/configuration.md)。
