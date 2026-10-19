# 独立实时 Level2 应用

`l2-live-app` 直接消费供应商 MQTT，负责原始报文采集、协议解析和部分分钟观察。它独立于数据 API、主策略和离线 Batch 进程，不执行交易动作。每个标的订阅 `trans/<code>`、`order/<code>`、`market/<code>`，最多 16 个 SH/SZ 标的、48 个 topic。供应商账号只允许单终端；接管前通过原采集器的控制入口停止旧进程。

## Parquet 数据格式

行情落地使用 Apache Parquet，GZIP 压缩；逻辑类型遵循 [Parquet 类型规范](https://parquet.apache.org/docs/file-format/types/logicaltypes/)。每个不可变批次包含四个文件：

```text
var/l2-live/
├── raw-journal.sqlite3               # 原始接收 WAL、批次提交与文件摘要
└── parquet/
    ├── .pending/<batch_id>/           # 已冻结、尚未发布的恢复批次
    └── batches/<batch_id>/
        ├── raw.parquet               # 原始报文及接收证据
        ├── events.parquet            # 解析后的事件和无效报文审计
        ├── minutes.parquet           # 部分分钟观察，保留修订及可用时间
        └── checkpoint.parquet        # 类型化状态树，用于精确恢复累计器
```

`events.parquet` 和 `minutes.parquet` 将价格、数量、十档深度、质量标记直接保存为列，不放 JSON 字符串或 JSON BLOB。原始报价及累计金额使用精确 `DECIMAL(38,4)`，中间价使用 `DECIMAL(38,5)`，衍生比率和加权指标使用 `DOUBLE`；数量和供应商子序号使用整数，可能超过整数范围的订单标识使用字符串。时间使用 UTC `TIMESTAMP(NANOS,true)`，业务日期使用 `DATE`。原始报文在 `raw.parquet.payload` 中使用二进制，保留任意字节与纳秒接收时间，不转 Base64。

事件通过 `capture_id` 关联同批次的原始报文。不同证券共用固定 schema，缺失字段保持 NULL。文件元数据保存格式版本、数据集名称和批次 ID。检查点文件使用 `path: LIST<STRING>`、节点种类及类型化标量列表示状态树，保留空容器、NULL 和精确十进制状态。

分析前停止采集并用 `./deploy/l2-live/run.sh files > /实际路径/files.json` 导出已提交文件清单，再用 PyArrow 读取。归档后不再用批次目录的 glob 充当完整历史清单：

```python
import json
import pyarrow.dataset as ds

with open('/实际路径/files.json', encoding='utf-8') as stream:
    files = json.load(stream)
events = ds.dataset(files['events'], format='parquet')
print(events.to_table(columns=['symbol', 'exchange_ts', 'price', 'volume']))
```

SQLite 保留原始接收日志和提交账本，保证刚收到但尚未形成 Parquet 批次的报文可恢复。新批次的 `prepared_batch.payload` 和 `committed_accumulator.payload` 为空，仅通过 `parquet_batches` 中的 SHA256 引用 Parquet。日志、文件摘要和控制状态不替代行情文件。`status.json` 与命令行 JSON 仅用于进程管理。

四个文件写完、同步并逐列回读核验后，才冻结提交引用。发布通过同一文件系统中的目录原子移动完成；文件缺失、schema 或摘要变化会阻止推进原始接收检查点。发布后、账本提交前崩溃，重启复用同一批次，不重复计入成交或改写分钟可用时间。单批最多 200 条事件；SQLite 与 Parquet 的容量上限各由 `l2.storage.max-bytes` 设置，默认 8 GiB，也可用 `--storage-bytes` 覆盖。实际磁盘余量不足 64 MiB 时停止接收。

## 配置和启动

`--journal-max-bytes` 兼容旧参数，最大 64 GiB，与 `--storage-bytes` 绑定同一容量；同时提供时值必须相同。

通过根 `config/apps/cn-l2-live.yml` 设置标的、路径和会话；入口自动启用 `l2` 与 `market-cn`，公共连接从 `config/application.yml` 继承，供应商账号放 `config/application-local.yml`。既有 `var/l2-live/credentials.env` 仍作为字面量覆盖，随后由进程环境变量和显式 CLI 参数覆盖。账号和密码不会写入计划输出。

```sh
./gradlew aliyunL2Live --args='plan --symbols 563360'
./gradlew writeL2LiveRuntime --offline
./deploy/l2-live/run.sh start --wait-credentials true
./deploy/l2-live/run.sh status
./deploy/l2-live/run.sh stop
```

Windows 可使用 `./deploy/l2-live/run.ps1 -Action plan|start|status|stop`；分别执行其中一个 action。`start` 构建独立运行快照；已构建过可加 `-NoBuild`。每次使用固定 JAR 快照，后续构建不替换正在运行的 classpath。私有凭据缺失时进入 `awaiting_credentials`，每秒重新加载。

默认标的来自 Python override 文件中未过期的 `force_symbols`，再应用 allow/block 策略；allow 条目本身不会创建订阅。可显式传入 `--symbols 563360.SH` 或 PowerShell 的 `-Symbols '563360.SH'`。运行期间冻结订阅范围，变更后需停止并重启，不延长过期配置。

`session` 只运行当天，通过 QuestDB HTTP 只读查询 `exchange_calendar`，默认交易所 `SSE`。休市正常退出；日历缺失、重复、非法标记或不可读则报错退出。上海时间 09:00 前等待、15:05 停止接收并排空可处理消息，午间保持连接，时间由 `l2.session` 配置。收盘后补启动直接退出。`run` 保留手工诊断语义，不检查交易日历。

订阅成功需检查 `collector.ready`、全部 topic 的 SUBACK、持续增长的接收数和验证行数。QoS 0 不能补回断线期间未收到的报文，原始重放只能恢复本地已提交的采集；供应商序列号的业务范围仍未验证。

## 历史迁移、重放和导出

先停止采集器，再执行：

```sh
./gradlew aliyunL2Live --args='migrate-parquet'
./deploy/l2-live/run.sh replay
./deploy/l2-live/run.sh export
```

`migrate-parquet` 将 schema 2 的历史 SQLite JSON 批次逐个迁为 Parquet，核验内容后才清空旧 JSON 载荷。操作可重复执行，保留原始 capture 和 batch ID，已完成报文不会重新计数。尚未完成的旧本地批次也会在重放时迁移。旧远端目标的未决 outbox 不会被自动改写为本地成功，遇到它会停止并要求先对账。

`replay` 使用原冻结值完成待处理批次。`export` 将全部原始报文流式导出为不可变 `raw-export.parquet` 并报告摘要，不覆盖既有导出文件。它包含尚未完成归一化的原始报文；常规 Parquet 批次在处理和发布后可见。升级时应保留整个 `var/l2-live/`，仅复制 SQLite 会丢失其引用的 Parquet 文件。

## 每日自动启动

先构建运行快照，再由操作系统每日 08:55 运行前台 `session`。机器时区设为 `Asia/Shanghai`，真实交易日由数据库判定。

Windows：

```powershell
./gradlew.bat writeL2LiveRuntime --offline
./deploy/l2-live/register-task.ps1
```

任务托管前台进程，禁止并发实例，错过触发后补启动，失败最多重试 3 次，每日不重新编译；当前用户需登录。更新代码后重建快照。

macOS：将 `com.zoutrankil.l2-live.plist.example` 中 `/ABSOLUTE/QuestDBWithData` 全部替换为真实路径，保存至 `~/Library/LaunchAgents/com.zoutrankil.l2-live.plist`，再用 `launchctl bootstrap gui/$(id -u) <plist绝对路径>` 注册。Linux 可用 `55 8 * * * /实际路径/deploy/l2-live/run.sh start --wait-credentials true`。这些文件不会自动注册系统任务。

## 接收落盘

接收线程复用 SQLite 连接和预编译语句，原始报文仍逐条以 FULL 事务提交；提交成功才算已保存。Parquet 编码不持有接收锁。状态计数与接收、提交处于同一事务，状态查询不再全表扫描 payload；完整内容另由存储核验检查。

## 分日归档与存储检查

停止采集器后运行：

```sh
./gradlew aliyunL2Live --args='archive --root var/l2-live --date 2026-10-14'
./gradlew aliyunL2Live --args='verify-storage --root var/l2-live'
```

只接受已经结束的接收日期；有待处理报文或活动批次时拒绝封存。混合日期批次及当前恢复检查点批次保留原文件。归档在 `parquet/archives/YYYY-MM-DD/`，含四个同类型文件及清单。

先复制原行组，再逐批回读全部值，最后原子发布并提交 SQLite 引用。提交引用后才删除对应旧小文件；中断后先核验归档，再继续清理。重放和 `storedBatch` 都能读取已归档批次，重复命令不会重算或重复累计。单次最多扫描 200,000 个候选批次、清单 128 MiB，超过上限明确失败，须先封存较早日期。`files` 从提交索引列出有效批次及归档，忽略未提交和已被归档接管的旧文件，避免漏历史或重复读取。

本地日志升级到 schema 4，旧 schema 2、3 自动兼容；使用了归档的目录不交给旧程序恢复。原始 SQLite 接收日志没有删除，仍须与全部 Parquet 一起备份。`verify-storage` 核对 SQLite、全部原始报文摘要、批次和归档，并报告积压、空间及可用磁盘；该命令可能较慢。

## 可复现负载

```sh
./gradlew aliyunL2Live --args='benchmark --root var/l2-load-new --messages 960 --rate 100 --batch-size 200'
```

只允许新目录。工具生成 16 标的、48 topic 的合成负载，经过真实 FULL 日志、解析、累计及 Parquet 核验，报告落盘延迟、峰值积压、空间和重启耗时；原始持久化要求未降低。可增加 `--messages`、`--seconds`、`--rate` 运行长负载。上限及磁盘压力会明确失败，已提交报文保留供重放。

本轮 960 条合成负载全部核验、最终无积压，并验证了强杀发布中子进程后只恢复一次。短测不能证明供应商真实高峰或全天容量够用；实际订阅、全天流量与成对备份恢复仍需现场验收。
