# F011 单任务执行与互斥

已统一为 SyncJobRunner：预检、创建冻结运行、占用数据区间、逐页来源、typed 分批写入、完整键/值与 WAL 验证、run/attempt/slice 账本收敛。同步消费页面形成背压；fetch 返回后或其他线程的来源回调拒绝执行。

DatasetIntervalLock 与运行账本使用同一个 SQLite 控制存储，BEGIN IMMEDIATE 原子检查重叠并占用。快照任务占用整个数据对象；带区间请求按含端点业务日期比较。未知写入不释放或自动过期；必须确认 writer 停止并完成精确回读才能核验释放。不同实例共享同一本地账本；不声称支持不同主机、不同账本路径的分布式互斥。

StockBasicSyncAdapter v2 要求显式代码列表（最多100），单代码调用 F005/F006 的真实 Tushare stock_basic；只处理当前 L 状态，支持 SNAPSHOT，不虚构历史增量。每个响应先落盘，保存请求、原始行与 SHA256，再交给写入器。当前注册版本为v2、enabled、manual、非dailyEligible；旧v1常量和既存账本快照保留。

正式入口：`run-stock-basic-job --codes 000001.SZ,600000.SH --logical-date 2026-09-29`。
使用配置目标中 DatasetDefinition 声明的 Java 示例表，以及 `app.sync.ledger-path`（默认var/sync-ledger.sqlite3）。表必须已存在并通过预检，不隐式建表。缺参数在运行前拒绝；不成功时CLI抛错并输出run ID。此入口不会自动启用调度。

## 容错和证据

- logicalDate来自FrozenRequest，StockBasicSnapshot也使用该日期的UTC日桶，不随来源/写入发生时间变化。
- 请求预算限制页数、片数、总行数、每页10000行、应用字节及总时间。取消与期限在来源/发送边界检查；已提交状态不因期限到达变为可重放。底层不可中断调用仍受自己的传输/查询超时约束，不声称Java可以强制终止远端writer。
- 来源异常记FAILED/PARTIAL，不伪造empty；空片须等待来源完整结束才能verified_empty。取数后发送前适配器异常会关闭当前slice；父子状态一致。
- 确定性测试证明：未知发送到期后仍IN_DOUBT，区间锁保留，新重叠任务在fetch前拒绝；原source/ledger证据继续可查。
- 真实验收：`run-86bec424d8d443d8895a66229e1926e9/runner-readback.json`及同目录source响应。两代码2行真实取数，经QWP写入后7字段完整回读，run/attempt/两slice均verified，锁释放后可重新占用并释放；自有隔离表清理。
- `local-F011-final2`全套103项、94通过/9未启用外部测试跳过/0失败，包含当前slice异常收敛回归。`run-70e809afaeae4171bf6cbf4f06c28e75/runner-readback.json`再次证明真实两行全字段回读、同范围重跑后物理行仍为2。
- 后续边界复核发现跨页重复完整键可被逐页写入验收掩盖；现由runner在第二次发送前拒绝，`SyncJobRunnerKeyBoundaryTest`与现有runner边界测试定向通过。此后代码修改尚未重新执行全套live测试，上一条全套结果对应修改前版本。

F011不持久化增量checkpoint，不自行重放IN_DOUBT。恢复与checkpoint属于F012；后续数据任务必须声明真实增量语义。人工复核pending_review。第一批提交8bd4f2d已包含F001—F010，F011计入第二批。
