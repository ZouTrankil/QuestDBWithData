# D012 source contract review

Status: static source-chain review only. No Tushare call, QuestDB query, Gradle build, or test was run by this implementation pass.

## Python connector chain checked

- `src/quant_platform/data/adapters/config/table_definitions/reference_market.py` registers `stk_st_daily`, `sync_stk_st`, `namechange`, `timestamp`, start `20100101`, daily frequency, 600-second timeout, and `rate_limit: 10`.
- `src/quant_platform/data/adapters/connectors/definitions.py` maps `stock.price.stk_st_sync.sync_stk_st` as the connector entry.
- `src/quant_platform/data/adapters/connectors/stock/price/stk_st_sync.py` decorates `get_namechange_with_retry` with `api_limit=10, period=60`, retries selected request errors at most three times, and calls `pro.namechange(start_date=..., end_date=...)`.
- The connector explicitly slices `20100101..sync_end` by natural year, fails the year if the response is unavailable, drops exact duplicate rows, selects names whose uppercase text contains `ST` before validating the selected codes as six digits plus exchange suffix, parses an empty `end_date` as the current sync horizon, and treats both interval endpoints as inclusive. Non-ST historical rows such as `X19363.SH` are ignored by the daily materialization and are not subject to the selected-ST code check.
- It asks the D001 calendar for every open date in the requested output window, expands each active name interval to one `(ts_code, timestamp, is_st=1)` row per open date, and batches writes by 500 symbols. It does not write raw namechange intervals into the daily table.
- The audited `StkStDaily` model and physical DDL agree on `ts_code SYMBOL`, `is_st INT`, `timestamp TIMESTAMP`, designated timestamp `timestamp`, YEAR partition, WAL, and dedup/upsert key `(ts_code,timestamp)`. The table config also declares `timestamp` and source `namechange`.
- Python recovery binding uses the same namechange history and interval expansion, and its empty-result handling allows an empty daily ST slice. A successful empty history response is distinguished from unavailable source history.

## Java source policy

- D012 requests only `ts_code,name,start_date,end_date`, one bounded non-paged natural-year request at a time, through the shared Tushare request-budget client.
- The local 5000-row bound is protective, not a verified provider quota. A response at the bound is persisted as incomplete evidence and rejected. The shared decoder also rejects a response above the declared bound; its raw wire body is not exposed to this source adapter.
- Empty `end_date` means active through the frozen run end. `start_date` and nonempty `end_date` are parsed as exact Tushare `YYYYMMDD` calendar dates. Invalid dates/names, invalid six-character raw identifiers, non-numeric selected-ST codes, duplicate same-code/start/name/end intervals, and malformed responses fail the slice. Raw non-ST identifiers may contain uppercase letters and digits, matching the connector's filter-before-strict-code-validation order.
- Overlapping ST intervals are unioned by complete daily business key. `namechange` end dates remain inclusive, so an ST interval ending on a trade date emits that date and none after it.
- D001 supplies every expanded SSE session. D002 is declared as a job/dataset dependency and the strict mainland code is retained in the daily identity; no current-stock complement is synthesized because the table stores positive ST rows only.
- Each successful annual response is stored as a SHA-256 raw receipt. Each emitted trade-date page has a receipt that references every annual response and stores the derived daily rows. Reopen verifies all annual hashes and independently re-expands the rows before they can establish coverage.

## Known acceptance limits

- Current shared `TushareProperties.effectiveEndpointLimits()` has no `namechange` ceiling entry. The coordinator must bind `namechange <= 10/minute` before enabling D012; Python config and decorator both state 10/minute.
- The D012 job uses a new slice-policy reference `stk_st_daily.namechange_year_trade_dates`; shared `DatasetConfiguration` must admit that reference and register the D012 owner/adapter.
- A newly fetched overlap that retracts/shortens an ST interval is published as an authoritative bounded window: all existing rows inside the window are replaced by the complete positive ST result, while every row outside it is preserved and verified. A D012 SQLite journal and mutex serialize the non-atomic table renames; interrupted layouts require exact stopped-writer recovery. No `is_st=0` rows are synthesized and the formal table is never deleted or renamed.
- D011 remains a serial actual-acceptance prerequisite. D012 has not been live-verified.
