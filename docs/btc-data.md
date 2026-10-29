# BTC 数据采集

Java 下载 Binance Vision 官方 ZIP，正式数据写配置中的远端 QuestDB。Python 只读，研究文件可以导出到本地。

## 六张表

| 表 | 数据 | 时间键 |
| --- | --- | --- |
| `btc_spot_1d` | 原生现货日线 | `open_time` |
| `btc_spot_1h` | 现货小时线 | `open_time` |
| `btc_perp_1h` | USDT 永续小时线 | `open_time` |
| `btc_funding` | 实际资金费率结算 | `funding_time` |
| `btc_mark_1h` | 标记价格小时线 | `open_time` |
| `btc_index_1h` | 指数价格小时线 | `open_time` |

只采 BTCUSDT，UTC 切分。K 线保留 OHLC、结束时刻；成交 K 线另存成交数量、金额、笔数、主动买入数量和金额。标记价和指数价不把占位列当成交量。

六表均保存来源 ZIP 摘要和实际取得时间。原始 ZIP 全留。小数转 DOUBLE，原精度在 ZIP 中。`observed_at` 不是历史已知时间，不能直接拿来证明无未来信息。

## 命令

```bash
./gradlew :markets:btc:data-app:run --args='probe-binance'
./gradlew :markets:btc:data-app:run --args='status-binance'
./gradlew :markets:btc:data-app:run --args='collect-binance --dataset all --from 2020-01-01 --through 2026-10-19'
```

`--dataset` 可选 `spot_1d`、`spot_1h`、`perp_1h`、`funding`、`mark_1h`、`index_1h`。加 `--download-only` 只下载和解析，不建表、不写行情。

起止日期都包含，只接收已结束的 UTC 日期。完整月取月包，其余日期取日包。资金费率不完整月份先记录月包缺失，再由 `funding-binance-tail --dataset funding --from ... --through ...` 调官方历史 API 补尾部。

旧 `collect --through ...` 保留原行为，不接管这六张表。

旧 `crypto_ohlcv` 的跨资产核心范围另行维护：Binance、Bybit 的 BTCUSDT/ETHUSDT 现货及 USDT 永续，小时线和 UTC 日线已验收到 2026-10-25，累计 370198 行。原两条官方停市小时缺口保留。现有每日任务只继续这八个产品的日期增量，先维护美股核心；最新200行、原文对照和完整字段证明见[单日增量验收](decisions/2026-10-26-核心币价十月二十五日增量验收.md)，[此前两日验收](decisions/2026-10-25-核心币价两日增量验收.md)保留原窗口。

## 记录与停止

根目录由 `app.crypto-data.root` 配置，默认 `var/crypto-market-data`。

- `binance-vision/probe.json`：真实样本、字段和解析结果。
- `binance-vision/archives/`：不可变版本清单和当前指针，指向原 ZIP/CHECKSUM。
- `binance-vision/archive-queue.sqlite3`：各归档状态、原因和成功运行号。
- `binance-vision/sync-status.json`：本次结束时的汇总，不能当完整性证书。
- `sync-ledger.sqlite3`、`prepared/`、`receipts/`、`queue-verifications/`：正式写入和核验原件。

创建 `binance-vision/stop.request`，在当前归档处理完后停止；继续前先移除。保留整个根目录，不能只备份行情而丢掉原文、SQLite 和 prepared。

来源失败记为 `SKIPPED_SOURCE`，继续下一项。未决记为 `PUBLISHING`，保留锁和证据；不自动重发、解锁或覆盖。来源重试用 `retry-binance-source`，参数与 `collect-binance` 相同。只重试未发送的 `SKIPPED_SOURCE`，原原因留在 `archive_source_retries`；不会重置未决或已核验项。

官方文件有缺小时或提前收盘时，实际记录仍可入库，月份记为 `PARTIAL_VERIFIED`；缺口和原结束时刻保留在 bundle 和队列中，不补价格，也不推进整月完整状态。已见结束早于开始、全价相同且无成交的坏占位行，隔离原行并记缺口；其他异常仍跳过整个文件。

`check-binance-revisions` 也使用相同范围参数。它重取官方 CHECKSUM/ZIP，与原成功运行的 ZIP 摘要比较，原件和报告保存在 `revision-checks/`。发现修订标为 `REVISION_REQUIRES_REVIEW`；再运行 `apply-binance-revisions --report REPORT.json`。只接受原运行和旧值仍一致、完整键集合没变的修订；再次从官方核对新版本，绑定原物理表发布。新增、删除键的修订先跳过，不自动删记录。

## 验收

每项使用官方 CHECKSUM、真实字段和完整日期范围。正式发布复用原引擎：完整业务键和值回读、WAL 结清、原物理目标稳定后才记成功。`MAX`、行数和进程退出只供观察。

当前范围和剩余任务看 [BTC 计划](plan/btc/README.md)。字段依据见 [Binance 官方公开数据](https://github.com/binance/binance-public-data)；原生日线与旧聚合日线分存的原因见[决定](decisions/2026-10-20-BTC官方原生归档.md)。

## 研究读取和导出

```bash
uv run tools/btc_research_data.py export --from 2020-01-01 --through 2026-10-19 --output var/crypto-market-data/research
uv run tools/btc_research_data.py audit --from 2020-01-01 --through 2026-10-19 --output var/crypto-market-data/research/audit.json
```

脚本读取根 QuestDB 配置和 `APP_QUESTDB_HTTP_URL` / `APP_QUESTDB_HTTP_AUTHORIZATION` 覆盖，只发 SELECT。Parquet 用 UTC 微秒时间，按摘要保留版本，JSON 清单原子切换；`load_export()` 先核对摘要和行数，拒绝半更新文件。

统一取数可导入 `tools/btc_research_data.py` 的 `BtcReader.read(dataset, from_date, through)`；`funding_with_prices()` 只关联结算前已结束、距事件不超过两小时的 K 线，缺价留空，不提前使用当前小时收盘。它仍不能证明修订历史在当时已知。

DuckDB 读取已验收的本地文件：

```python
from pathlib import Path
import duckdb
from tools.btc_research_data import load_export
spot = load_export(Path("var/crypto-market-data/research"), "spot_1d")
connection = duckdb.connect()
connection.register("spot", spot)
print(connection.sql("SELECT min(open_time), max(open_time), count(*) FROM spot"))
```

## 日常增量

```bash
./gradlew :markets:btc:data-app:run --args='sync-binance'
```

截止日默认取昨天 UTC，也能显式传 `--through YYYY-MM-DD`。起点读根配置 `app.crypto-data.archive-history-start`，默认 2020-01-01。已验收包直接跳过；未发送的来源失败项每日可重新取官方文件，原错误保留。未决项仍挡住同一数据集，不自动解锁。

月份完整后优先计划月包；如果官方尚未发布，记录来源缺失，后续重试。原日包和月包的原文及正式运行都保留，不删旧证据。历史已验收月包是否修订，另跑 `check-binance-revisions`，不会在增量里默默覆盖。

日更编排和当前用户的 macOS 定时入口已提供，统一使用原工作目录和队列。部署、停止、备份与故障边界见 [BTC 日更](../deploy/btc-data/README.md)。本机安装与真实执行结果见 [收尾验收](audits/2026-10-21-btc-工程收尾.md)，七个日更周期尚待观察。

## 资金费率尾部与恢复

归档覆盖到上个月。截止月使用官方 `/fapi/v1/fundingRate`，单次限制在同一个月；达到 1000 条上限、空响应、错误币对、重复或越界事件都拒收。完整原响应先保存，写后仍按原正式流程核对。

API 没有历史结算间隔，所以 `btc_funding` 的读取定义 v2 只把 `funding_interval_hours` 放宽为空；表、键和旧 v1 冻结请求不变，月包的真实间隔继续保留。API 明确为非 Regular 类型时暂不合并，原响应留存。

```bash
./gradlew :markets:btc:data-app:run --args='funding-binance-tail --dataset funding --from 2026-10-01 --through 2026-10-19'
./gradlew :markets:btc:data-app:run --args='reconcile-binance --run RUN_ID'
# 明确补确认缺失的键，保留已有值：
./gradlew :markets:btc:data-app:run --args='reconcile-binance --run RUN_ID --repair-missing'
```

恢复会检查原 PID 和启动时刻，原进程仍在运行就拒绝。原归档、CHECKSUM、prepared、原物理表、已有字段值和 WAL 都须一致；不会换新请求或解锁重发。部分发送补缺沿用引擎的“首切片中断、总量不超过一万行”范围。冻结资金费率 API 也支持原运行重算、核对与状态收拢；原值冲突和超出能力的故障继续保留原锁。

官方 API 字段依据见 [Binance 市场数据文档](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)。月包与 API 的真实重叠核对见[记录](audits/2026-10-21-btc-funding-overlap-probe.json)。

同键修订保留 `archive_revision_runs` 中的新旧运行，发布失败保留原意图和锁。恢复仍使用 `reconcile-binance`，不会换物理目标。完整结果看[本轮验收](audits/2026-10-21-btc-数据工程验收.md)。

导出前后还会核对原物理表和 WAL 版本。原发布账本在本机时，也检查运行中及未决锁；版本变化或仍有锁就保留旧导出，不能把稳定的 WAL 单独当成正式发布完成。跨机器读不到原账本时，读取只证明库内快照稳定，不证明来源完整。

月包接替日包前比较重叠业务值；只换来源摘要不算数据冲突，月包可以补上 API 未提供的间隔。价格、数量、费率或已有间隔不一致时保留旧值，并在 `overlap-conflicts/` 留报告，交显式修订处理。

## 固定研究批次与质量读取

```bash
uv run --script tools/btc_research_data.py publish-batch --from 2020-01-01 --through 2026-10-19 --output var/crypto-market-data/research
```

`publish-batch` 保留旧 `export` 和单表清单，新增 `objects/`、`batches/<batch_id>/manifest.json`、`quality.json` 和唯一 `current.json` 指针。批次清单 schema 为 1，质量规则为 3；质量规则 1、2 的固定批次仍可读取，不能冒认新版同步关联能力。跨表检查只读取候选文件，文件摘要、UTC 范围和质量报告绑定后才切换指针。

`dataVerified` 只表示已导出记录逐字段匹配原正式成功运行及当前物理目标；来源完整性、质量问题与历史 PIT 分开说明。原始 `IN_DOUBT` 回执必须同时存在独立成功恢复证明。跨机器缺原账本时可以生成未认证批次，严格读取默认拒绝认证不足；不能据此称全部历史齐全。

```python
from pathlib import Path
from datetime import datetime, timezone
from tools.btc_research_data import read_batch

root = Path("var/crypto-market-data/research")
result = read_batch(
    root, "spot_1d", columns=["open_time", "close"],
    lower=datetime(2021, 1, 21, tzinfo=timezone.utc),
    until=datetime(2021, 1, 22, tzinfo=timezone.utc),
)
fixed_batch = result["batchId"]
# 后续传 batch_id=fixed_batch，不受 current.json 更新影响。
print(result["table"])
```

读取范围为 UTC 左闭右开。`mode="strict"` 默认要求正式证明，缺小时、非完整 K 线和所需字段冲突会返回问题 ID 并拒绝；`mode="explore"` 返回原记录及 `issues`，不静默删行，`excludedRows` 为 0。四天成交字段冲突只约束实际差异字段，不自动否定 OHLC。未知资金费率间隔不妨碍仅取费率，但不允许严格读取未知间隔字段。

DuckDB 可以 `connection.register("spot", result["table"])` 后查询；不通过文件通配符混合不同批次。`commonCompleteHourlyOhlc` 给出当前批次中小时数据集的共同完整价格区间，不替代原来源证明。

`funding_with_prices(..., mode="synchronous")` 要求四侧均来自事件前一个完整小时，以 `prices_complete` 说明联合结果；缺任一侧不能计算完整同步价差。原 `mode="carry"` 保留兼容沿用价，最大年龄由 `app.crypto-data.research-price-max-age-seconds` 控制，默认 7200 秒，并返回每侧时间、年龄和是否沿用。最终结算费率不能无证据当作结算前已知信号。

## 修订待办、空结果与持续观察

修订探测和来源重叠冲突进入 `binance-vision/revision-review.sqlite3`。新一轮未检查某旧月或返回 `UNCHANGED` 都不解除待办；原报告丢失或变化会拒绝放行。普通 ZIP 修订须由相同任务、原运行、新 ZIP、原物理目标的 Java 成功证明解除；跨来源冲突继续显式复核。

当前批次严格读取检查相关待办；显式 `batch_id` 仍可用于研究原历史版本，结果附 `pendingRevisions`。标准目录可自动找到本机原审查记录；自定义目录可传 `writer_root`。跨机器不可取得审查材料时 `revisionReviewAvailable=False`，不表示官方没有修订。

```python
result = read_batch(root, "funding", batch_id=fixed_batch,
                    columns=["funding_time", "funding_rate"],
                    purpose="funding_synchronous")
print(result["associations"])
```

质量 v3 逐事件保存四侧前一完整小时的价格与来源摘要。缺价、非完整小时、范围前置数据不在批次内等原因分别记录；同步用途还检查四侧文件摘要及 Schema。严格同步用途拒绝不完整事件；探索模式返回原因；默认 `purpose="raw"` 不因缺价否定资金费率本身。

空查询保留实际表的列和 UTC 类型，空范围读取返回 `EMPTY_RESULT`；零行导出标记 `EMPTY_OBSERVED`，不能证明合法空来源。未取得实际 Schema 时明确拒绝。

```bash
uv run --script tools/btc_daily.py scheduled
uv run --script tools/btc_daily.py observations
uv run --script tools/btc_remaining_acceptance.py --check-empty-source --output var/crypto-market-data/research/acceptance.json
```

`scheduled` 按根配置有界续跑，成功后当天不再写。`observations` 每个真实运行日只计一次，历史补跑、同日重跑和失败周期不计成功；同时检查固定批次原件及当前目标覆盖。观察未完成时退出码为 2，不是要求重发数据。每轮日更自动刷新 `daily-observations.json`。

截至 2026-10-21，本轮已补齐 10 月 20 日永续 24 根，六组共同批次推进到该日；七日观察为 1/7。具体证据见[剩余任务验收](audits/2026-10-21-btc-剩余任务验收.md)。


## 历史缺口逐段核对

```bash
# 从当前固定研究批次生成有限计划，由 Java 冻结月包、日包和 REST 原件：
uv run --script tools/btc_quality_review.py run
# 只重算本地证据，不重新访问来源：
uv run --script tools/btc_quality_review.py summarize --plan PLAN.json --report REPORT.json
# 显式补齐完整缺日，并在原队列全部核验后发布同截止日研究批次：
uv run --script tools/btc_quality_review.py repair --plan PLAN.json --report REPORT.json
```

每轮最多 64 个数据集日期；共用原预算、来源目录、归档锁和日更锁。Python 不下载来源，不写 QuestDB。月包可复用已校验冻结版本；REST 原 JSON 与官方 ZIP 分开保存，投影不增加原时间精度。四个冲突日额外核对分钟归档，分钟不完整时不生成完整日汇总。

现货小时请求可在计划中设置 `minuteCheck: true` 和 `aggregateTradesCheck: true`，分别保存官方分钟日包和聚合成交日包。聚合成交单包最多 64 MiB，仍沿用官方 CHECKSUM、原持久预算和不可变原文目录；下载成功只表示原件已保存，不自动重建或覆盖 K 线。聚合成交证据保存在来源报告的 `aggregateTrades` 中。

首笔资金费率需要起始日前一小时价格时，来源核对计划可设置 `fundingBoundaryDependency: true`。仅允许 `2019-12-31` 的四类小时价格，不允许同时取分钟或聚合成交。这只保存边界原件，不扩大正式同步范围，也不自动改变研究批次。

聚合成交大包的请求和读流时限由根配置 `app.crypto-data.quality-trade-download-timeout-seconds` 控制，默认 180 秒，最多 300 秒；可通过 `APP_CRYPTO_DATA_QUALITY_TRADE_DOWNLOAD_TIMEOUT_SECONDS` 覆盖。其他来源仍沿用原 60 秒时限。失败项可生成有限重试计划，已冻结原件直接复用，重试继续计入原预算。

当前补齐入口只接受标记价、指数价的完整缺日，要求日包和 REST 完整且业务值一致。Java 命令为 `audit-binance-quality --plan PLAN.json` 和 `repair-binance-quality --plan REPAIR_PLAN.json`；后者接受工具生成的绑定来源摘要的修复计划，不把普通核对计划直接作为写入依据。正式未决仍走原恢复入口；同键改价和分钟重建不在此入口范围。

修复保留原截止日，不计作新一天的日更。旧批次计划不能覆盖后来发布的新鲜版本；连接恢复后先核对当前指针。显式修复不由现有日更自动接管。最新结果见[缺口核对与补齐](audits/2026-10-22-btc-缺口核对与补齐.md)。

只读查询故障会记录阶段、表、查询指纹、超时与耗时、异常类型和调用位置，便于区分来源等待和数据库故障；不复制 SQL、连接地址或凭据。正式写入前的目标核对失败状态为 `BLOCKED_TARGET`，来源失败仍为 `SKIPPED_SOURCE`，`PUBLISHING` 仍须原运行恢复。
