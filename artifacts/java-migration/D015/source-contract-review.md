# D015 `etf_adj` source contract review

Review date: 2026-09-30. Source project was read only; this review did not issue Tushare requests.

## Python call path inspected

Primary implementation: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/etf/etf_adj_sync.py`.

- `FUND_ADJ_PAGE_LIMIT = 2000`; `_call_fund_adj` is decorated for 20 calls per 60 seconds.
- `_get_etf_adj_by_date` calls `fund_adj(trade_date, limit=2000, offset=...)`, advances by returned row count, and terminates on a short/empty page. It validates non-null `(ts_code, trade_date)`, exact date scope, and duplicates across pages.
- The Python connector has a compatibility fallback only when the first paged SDK call raises a `TypeError` mentioning `limit` or `offset`; an unpaged fallback returning at least 2000 rows is rejected as possibly truncated. Java follows the currently documented paging contract and fails closed on request errors; it does not silently retry as an unpaged request.
- `sync_etf_adj` maps `trade_date` to the model's physical `timestamp`, reads last update using `timestamp`, and obtains open dates via `get_trading_dates_between`. That helper queries the complete SSE calendar date coverage and returns SSE-open dates. This is a scheduling calendar only; all codes returned by `fund_adj` are retained without exchange filtering.
- `ETFAdj` declares `ts_code SYMBOL`, `adj_factor DOUBLE`, `timestamp TIMESTAMP`, YEAR partitioning, WAL and deduplication key `(ts_code,timestamp)`. The Python model's timestamp is a date carrier, not an event instant.
- `config/table_definitions/funds.py` says daily at 03:20 and `rate_limit=25`; the actual connector decorator is 20/min. The Java shared endpoint budget must therefore be at most 20/min, with the existing global credential budget still applied.

## Official source documentation

Tushare [`fund_adj` documentation, doc 199](https://tushare.pro/document/2?doc_id=199), checked 2026-09-30, states that each request returns at most 2,000 records, the endpoint can be looped to retrieve more with no total data limit, and accepts `trade_date`, `offset`, and `limit`. Output fields are `ts_code`, `trade_date`, `adj_factor`.

Implementation declares 2,000 per-request cap and uses offset pagination. It adds a deliberately bounded Java acceptance ceiling of 10,000 rows per trade date (five full pages plus a sixth short/empty terminal page). Hitting the ceiling before a short terminal response is a failed/incomplete slice, never a successful truncated receipt. The cap is a local operational bound, not an official total-per-day cap.

The Tushare `fields/items` response carries no frozen snapshot/version token in the current shared Java client. Offset-page consistency therefore cannot be proven from a provider snapshot ID; Java rejects duplicate keys, date drift, repeated pages and incomplete terminal evidence, and live acceptance must independently compare the captured full key/value set to a second source read. The receipt records `sourceVersion=null` rather than inventing a version.

## Read-only physical schema spot check

QuestDB was queried through the local read-only HTTP `/exec` endpoint only:

```sql
SELECT "column", "type" FROM table_columns('etf_adj') LIMIT 10;
SELECT ts_code, adj_factor, timestamp FROM etf_adj ORDER BY timestamp DESC, ts_code LIMIT 5;
```

The schema query returned exactly `ts_code SYMBOL`, `adj_factor DOUBLE`, `timestamp TIMESTAMP`. The bounded sample returned five rows dated `2026-09-28`; examples include `158000.SZ`, `158001.SZ`, and `158003.SZ`, each with `adj_factor=1.0`. This is a schema/sample audit only, not D015 source validation or write acceptance.

## D015 frozen mapping

| Source | DTO/domain | Dataset logical field | Existing physical field | Semantics |
| --- | --- | --- | --- | --- |
| `ts_code` | `TushareEtfAdjDto.tsCode` / `EtfAdjKey.tsCode` | `ts_code` | `ts_code` | Required six-digit fund code plus `.SH`, `.SZ`, or `.OF` suffix |
| `trade_date` | `TushareEtfAdjDto.tradeDate` / `EtfAdjKey.tradeDate` | `trade_date` | `timestamp` | BASIC `yyyyMMdd` business date, carried at UTC midnight in QuestDB TIMESTAMP microseconds |
| `adj_factor` | `TushareEtfAdjDto.adjFactor` / `EtfAdj.adjFactor` | `adj_factor` | `adj_factor` | Nullable finite DOUBLE, kept at source scale |

Business key is `(ts_code, trade_date)`; the existing physical UPSERT key remains `(ts_code,timestamp)`. DDL is provided only for a caller-created `java_d015_etf_adj_*` isolated acceptance object. No Flyway migration is added for the externally owned production table.

## Acceptance state

No live source request, write, build, or test was performed for this task. D015 remains `implemented_not_verified` until the coordinator performs real `fund_adj` pagination, exact all-column comparison against an isolated QuestDB target, idempotent rerun and incremental/revision acceptance. The formal `etf_adj` table was only read for bounded schema/sample inspection and was not modified.
