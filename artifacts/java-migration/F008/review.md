# F008 单数据分批写入验收

入口：`QuestDbStockBasicRepository.writeDetailed(stocks, logicalDateInstant)`，兼容入口
`storeAndVerify` 只接受 EMPTY 或 VERIFIED，其余抛错且保留详细结果供调用者记录。
公共 `VerifiedBatchExecutor<T,K>` 使用完整键 Codec、串行 Port 和有界 Iterator，后一批必须等待前一批逐字段回读与 WAL 确认。
DatasetWritePreparation 提供完整字段、类型、键、规范化字节及写入能力检查；DatasetWriteComparison 提供与行序无关的逐键全字段差异。

## 预算口径

- maxRowsPerBatch、maxBytesPerBatch、maxBatches 分别限制单批行数、应用编码预算和总批数；总候选键最多 100000。
- `Receipt.bytes` 是应用预算计数：默认 canonicalBytes 长度，stock_basic 为 canonicalBytes 长度乘 4 加每行 256 字节余量。不是 QWP 帧或网络流量计数，不能用于带宽计费。相同预算口径用于拆批和拒绝超大单行。
- QWP WebSocket Sender 的 bufferView 不支持获取编码帧，不能声称测得真实帧大小。该功能的字节上限用于控制应用批次负载；传输内部缓冲由 SDK 管理。
- HTTP 来源按 F005/F006 限流分片；单批发送不负责预先获取全部来源。stock_basic 是现有示例，后续数据接入必须传入有界来源。
- 回读 JDBC 单查询 20 秒，最多 10001 行；可见性策略最长 5 分钟，轮询间隔不得超过可见性期限。

## 验收证据

- `source-write.json`：两次真实 stock_basic 单代码请求，2 条源记录、2 批 QWP 写入，完整 snapshot_ts+ts_code 与 7 字段回读一致；重跑仍 2 行。记录请求、源值、回读值和批摘要，成功后清理自有隔离表。
- `live-write.json`：真实 QuestDB 上测试数据同键修订、null 覆盖及去重；其中测试数据不能称为来源修订。
- `schema-rejections.json`：真实错误分区、错误 dedup 键、多余列的隔离表全部发送前拒绝，保持 0 行后清理。
- `failed-fixtures-final-cleanup.json`：调试期间留下的 3 张自有隔离表，逐表核实名称、7 列物理契约及各 1 行后已清理；这些失败场景不计入成功验收。
- VerifiedBatchExecutorTest：部分批次、未知 ACK、sender 未终止、WAL 未追平、同数量不同值、字节拆批、空源、非法键、预检取消、已验收后来源中断。故障分支为确定性注入，不是伪称发生过线上网络事故。

## 恢复边界

- QWP 显式 flushAndGetSequence 和 awaitAckedFsn；ACK 后仍须完整键逐字段回读及 WAL settled。
- ACKNOWLEDGED_UNVERIFIED 与 UNKNOWN_UNRESOLVED 都使结果 IN_DOUBT，保留已验收前批摘要，不自动重放或推进 checkpoint。
- 未知 ACK 仅在 Port 显式证明 sender 已停止且实际回读、WAL 均满足时允许 UNKNOWN_RECONCILED。QWP 适配器无法证明异常 sender 已停止时默认 false，因此保留 IN_DOUBT；不会靠空查询判断没有写入。
- 来源在已验收批次后报错返回 PARTIAL 并保留该批回执；取消发生在预检后、发送前时不再发送。
- F008 不创建 checkpoint/运行账本，不启动日常调度。持久化运行状态和按已验收批恢复由 F010/F012 实现，不能把本项验收解释为调度或全数据迁移完成。

复现：在 `D:/work/fund_2/back-monitor` 使用 `uv run python C:/Users/zouqiang/IdeaProjects/QuestDBWithData/tools/run_local_validation.py --build-name local-F008-final --enable QUESTDB_WRITE_LIVE`。
凭据只进入子进程环境；人工复核保持 pending_review。
