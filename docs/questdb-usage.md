# QuestDB 使用规范

本文记录本项目连接 QuestDB 的协议选择、时间处理、写入、查询和运行约定。应用由 Spring Boot 管理配置和客户端生命周期，命令行入口运行在非 Web 模式。

## 协议分工

| 操作 | 客户端 | 协议 | 默认端口 | 本项目用途 |
| --- | --- | --- | ---: | --- |
| 高吞吐写入 | `org.questdb:questdb-client` | QWP / WebSocket | 9000 | 同步股票快照行 |
| SQL、DDL、查询 | Spring `JdbcTemplate` + HikariCP | PGWire | 8812 | 建表、探活、读取和写后可见性确认 |

写入使用 QWP 原生 Java `Sender`；SQL 读取使用 `JdbcTemplate`。JDBC 在本项目中负责表结构和查询，不用于逐行写入。`QuestDB` 客户端作为 Spring 单例 Bean 在应用关闭时释放；PGWire 连接由 HikariCP 复用。

## 配置

在 `src/main/resources/application.yml` 中配置 Tushare 和 QuestDB 参数：

```yaml
app:
  tushare:
    token: "your-tushare-token"
    api-url: "https://api.tushare.pro"
  questdb:
    host: 127.0.0.1
    pg-port: 8812
    qwp-port: 9000
    username: admin
    password: "your-questdb-password"
    database: qdb
```

应用属性由 `@ConfigurationProperties` 绑定，PGWire 的 `DataSource` 由 Spring Boot 自动配置并使用 HikariCP。当前 YAML 中的 token 和密码是占位值，请按实际部署环境填写。生产环境应使用 TLS (`wss`)、受限账户和受保护的配置文件；不要把凭据写入日志。

## 写入约定

表和 view 的版本化变更可交给 Flyway，接入方式见 [Flyway schema 管理](flyway-schema-management.md)。当前项目仍使用初始化器；正式接管时应统一迁移入口，避免初始化器与 Flyway 同时管理 DDL。

1. 通过 QWP 客户端批量构造行，并在批次结束时关闭 `Sender` 或显式 `flush()`。`Sender` 不可跨线程共享；每个并发生产线程独立借用一个 sender。
2. 时间列使用明确的 `Instant`，避免依赖隐式时区。本项目启动时将 JVM 默认时区设为 UTC；快照按 UTC 当日零点生成，并作为指定时间列 `snapshot_ts` 写入。
3. 时间序列应尽量按时间顺序写入。需要重试或去重时，先定义业务去重键；本示例使用 `(snapshot_ts, ts_code)`。
4. 建表 DDL 明确指定分区、WAL 和去重键。正式业务表应由迁移/初始化步骤管理，避免生产写入路径自行改变未知表结构。
5. QWP 客户端当前没有 `DATE` setter。本示例把 Tushare 的 `list_date` (`YYYYMMDD`) 按 STRING 保存，避免将 DATE 错写成不兼容类型。

本项目将数据写入隔离表 `java_tushare_stock_basic_qwp_test`，不会覆盖正式表。首次创建的表结构如下：

```sql
CREATE TABLE IF NOT EXISTS java_tushare_stock_basic_qwp_test (
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

## 写后读与异步可见性

QWP flush/关闭 sender 不等于数据已经可查询：WAL 会由服务端异步应用。不要用固定时长 `Thread.sleep()` 后假定成功，也不要在立即读取为空时直接判定写入丢失。

本项目通过 PGWire/JDBC 对该快照执行 `count()` 轮询，间隔 100ms，最多等待 10 秒；达到提交行数才成功，超时抛错。生产代码应根据业务 SLA 设置截止时间和轮询间隔，并记录提交行数、可见行数、等待耗时及超时状态。大批量数据还应通过稳定的业务键或批次标识核验，而不只检查整表总数。

## 查询约定

- 使用 JDBC 参数绑定用户输入，避免把请求参数拼入 SQL。
- 常规时序查询利用时间范围过滤和 designated timestamp；适合时可用 QuestDB 的 `LATEST ON ... PARTITION BY ...` 获取每个分区最新记录，用 `SAMPLE BY` 做时间桶聚合。
- 列名、表名通常不能像值一样通过 `?` 绑定；动态标识符应采用白名单映射。
- `SELECT *` 适合探索，不适合作为长期 API 查询；明确列集合并映射到领域模型。

例如，按股票代码查最近数据：

```sql
SELECT snapshot_ts, ts_code, symbol, name, list_date
FROM java_tushare_stock_basic_qwp_test
WHERE ts_code = ?
ORDER BY snapshot_ts DESC
LIMIT ?;
```

## WebClient 与 Tushare

外部 Tushare 请求使用 Spring WebClient，并从 `TushareProperties` 读取连接、响应和整体请求超时。HTTP 状态码成功后仍检查 Tushare JSON 的 `code` 字段，因为业务错误可能仍以 HTTP 200 返回。令牌只从 Spring 配置读取，不写入请求日志。

当前命令行程序会用 `block()` 等待 WebClient 响应，因此保持简单的同步 CLI 入口；若将来改为 Spring Boot WebFlux 服务，应贯穿使用响应式链，避免在 WebFlux 请求线程上调用 `block()`。

`LATEST ON`、`SAMPLE BY` 等 QuestDB SQL 是数据库特有语法，应封装在 repository 层并通过真实 QuestDB 版本验证。

## 参考

- [QuestDB Java 客户端与 QWP](https://questdb.com/docs/connect/clients/java/)
- [QuestDB Java 客户端写后读说明](https://questdb.com/docs/connect/clients/java/#read-after-write)
- [QuestDB PostgreSQL JDBC / PGWire](https://questdb.com/docs/connect/compatibility/pgwire/java/)
