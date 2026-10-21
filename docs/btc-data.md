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

定时部署尚未安排。部署时用同一个工作目录和原队列，每天 UTC 01:00 后运行此有限命令即可，不能复制账本另开第二个 writer。

## 资金费率尾部与恢复

归档覆盖到上个月。截止月使用官方 `/fapi/v1/fundingRate`，单次限制在同一个月；达到 1000 条上限、空响应、错误币对、重复或越界事件都拒收。完整原响应先保存，写后仍按原正式流程核对。

API 没有历史结算间隔，所以 `btc_funding` 的读取定义 v2 只把 `funding_interval_hours` 放宽为空；表、键和旧 v1 冻结请求不变，月包的真实间隔继续保留。API 明确为非 Regular 类型时暂不合并，原响应留存。

```bash
./gradlew :markets:btc:data-app:run --args='funding-binance-tail --dataset funding --from 2026-10-01 --through 2026-10-19'
./gradlew :markets:btc:data-app:run --args='reconcile-binance --run RUN_ID'
# 明确补确认缺失的键，保留已有值：
./gradlew :markets:btc:data-app:run --args='reconcile-binance --run RUN_ID --repair-missing'
```

恢复会检查原 PID 和启动时刻，原进程仍在运行就拒绝。原归档、CHECKSUM、prepared、原物理表、已有字段值和 WAL 都须一致；不会换新请求或解锁重发。目前沿用引擎的“首切片中断、总量不超过一万行”恢复范围，其他情况先跳过。

官方 API 字段依据见 [Binance 市场数据文档](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)。月包与 API 的真实重叠核对见[记录](audits/2026-10-21-btc-funding-overlap-probe.json)。

同键修订保留 `archive_revision_runs` 中的新旧运行，发布失败保留原意图和锁。恢复仍使用 `reconcile-binance`，不会换物理目标。完整结果看[本轮验收](audits/2026-10-21-btc-数据工程验收.md)。

导出前后还会核对原物理表和 WAL 版本。原发布账本在本机时，也检查运行中及未决锁；版本变化或仍有锁就保留旧导出，不能把稳定的 WAL 单独当成正式发布完成。跨机器读不到原账本时，读取只证明库内快照稳定，不证明来源完整。

月包接替日包前比较重叠业务值；只换来源摘要不算数据冲突，月包可以补上 API 未提供的间隔。价格、数量、费率或已有间隔不一致时保留旧值，并在 `overlap-conflicts/` 留报告，交显式修订处理。
