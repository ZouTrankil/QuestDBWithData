# F004 共享 HTTP 客户端

现有 TushareClient 已改为有界通用 TushareRequest/TusharePage，请求DTO不包含凭证；token只在HTTP边界注入。stock_basic 原入口复用同一实现，不新增数据接口。fields/items按响应名称映射，保留JSON null及数值，拒绝缺失/重复字段、错误行宽、嵌套单元格、缺少业务code和超过声明行数的响应。正常0行与失败分别返回空page或明确异常。

ClientConfiguration 管理共享连接池：最大4连接、16个等待请求、等待10秒、空闲30秒；默认最大响应8MiB。连接/响应/总请求分别受现有配置限制。关闭底层隐式TCP重试与重定向，确保之后F005能够逐次计费。HTTPS协商优先HTTP/2并允许HTTP/1.1回退；明文测试服务仅HTTP/1.1。观察记录以HTTP/2 stream类型识别实际协议，并记录底层连接ID，最多200条，不保存URL/body/header。参考[Reactor Netty官方HTTP客户端文档](https://projectreactor.io/docs/netty/release/reference/http-client.html)的连接池、隐式重试与协议协商说明；实际行为以下述执行证据为准。

requestMono为冷publisher，每次订阅一次HTTP尝试；requestAsync的future取消传播至订阅。异常仅输出安全分类/状态码，不带来源msg、正文或底层异常链。通用HTTP层不判断窗口完整性、不做分页和重试，分别由F005/F006负责；有界page不等于完整数据集。

## 实际验收

- TushareHttpClientTest：本地真实HTTP服务四项测试通过。覆盖乱序/null/连接复用、缺字段/重复字段/坏JSON/坏行宽/超行数、业务错误与HTTP429及无隐式重试、正常空响应/取消/超时。
- TushareHttpLiveTest：真实stock_basic仅请求ts_code=000001.SZ、list_status=L，两次间隔3.1秒，每次返回1行，六个字段一致且list_date严格解析通过。证据source-http.json。
- 实际协议两次均为HTTP/2.0，底层连接ID相同，确认复用。本地HTTP/1.1服务也验证同连接复用。未声称所有来源均支持HTTP/2。
- 凭证未写入证据；只保留公开股票字段。数据库写入/checkpoint对本HTTP功能N/A，先前F002/F003的实际QuestDB读取验收独立保留。此结果不是全市场sync或数据迁移验收。

复现：JAVA_HOME指向本机jdk-24.0.2，TUSHARE_HTTP_LIVE=1，执行 `.\gradlew.bat test --tests '*TushareHttp*Test' --rerun-tasks --console=plain`。诊断只有两次有界请求；共享速率预算尚待F005，不能据此启动批量同步。人工复核 pending_review。
