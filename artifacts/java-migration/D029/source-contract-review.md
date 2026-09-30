# D029 source contract review — `margin_detail`

Status: reviewed from the checked-out Python call path and the official Tushare API page. No source request was made.

## Python registration and execution path

- Canonical table configuration is `config/yaml/data/table_definitions/stock_flows.py` (`margin_detail`): `sync_function=sync_margin_stock`, `api=margin_detail`, `date_column=trade_date`, daily frequency, configured `rate_limit=150`, and configured start date `20260101`.
- `reference_market.py` also contains `margin_stock`, disabled, which calls the same `sync_margin_stock` and writes the same physical `margin_detail` table. D029 registers only canonical `margin_detail` to prevent duplicate jobs.
- `connectors/stock/margin/margin_sync.py::fetch_margin_detail_tushare` calls `pro.margin_detail(trade_date=trade_date)` once for a date. It has no offset, cursor, or page parameter. The decorator is `api_limit=150, period=60`.
- `sync_margin_stock` uses verified SSE open dates, bounds the default end to the latest SSE open session before today, and replays seven calendar days behind the physical `MAX(trade_date)`. This accommodates Friday SZSE/BSE records that may arrive on Monday. The Java plan preserves the one-open-session publication delay and the seven-day replay.
- The Python connector catches connection/timeout/value/key errors and returns `None`; its caller records these partitions as skipped/unverified. An empty DataFrame is likewise skipped/unverified. D029 does not copy that ambiguity into a successful empty run: request errors remain errors, and an empty/fully-filtered source partition cannot extend checkpoint coverage.
- `_normalized_margin_detail_partition` removes only an exact self-identifying `日期：YYYY-MM-DD.BJ` footer whose eight numeric values are all null. It requires the requested `trade_date`, a unique `ts_code`, and a mainland code ending in `.SH`, `.SZ`, or `.BJ`.
- Python's QuestDB `MarginStock` model declares 11 columns, `trade_date` timestamp, YEAR partition, `ts_code` SYMBOL, and DEDUP keys `(ts_code,trade_date)`. The physical audit records WAL and DEDUP true. Python's Pydantic defaults some values to zero, but D029 preserves provider nulls and rejects missing `rzye`/`rzmre` rather than inventing defaults.
- The Python payload certificate lists the eight numeric fields and the data-quality contract requires finite, nonnegative `rzye`/`rzmre`; optional `rqye`, `rqyl`, `rqmcl`, and `rzrqye` must be nonnegative when present. The two correction flow columns `rzche` and `rqchl` are finite when present and are not silently clamped.

## Official source evidence and frozen call contract

The [official Tushare `margin_detail` documentation](https://tushare.pro/document/2?doc_id=59) states that the endpoint returns daily Shanghai/Shenzhen margin details, describes the late Monday availability of the prior Friday SZSE/BSE rows, supports `trade_date` as a request filter, and caps one request at 6,000 rows. It exposes no documented offset/cursor for this call path. D029 therefore makes one bounded call per SSE session and rejects any response with 6,000 rows as potentially truncated.

Frozen request: `margin_detail(trade_date=YYYYMMDD)`. Frozen response columns, in storage projection order:

`trade_date, ts_code, name, rzye, rzmre, rzche, rqye, rqyl, rqchl, rqmcl, rzrqye`

The date is a BASIC ISO business date stored as a UTC-midnight timestamp carrier. `ts_code` includes exchange suffix. `name` and provider-null numeric values are preserved. Monetary balances/flows remain in CNY; `rqyl`, `rqchl`, and `rqmcl` retain Tushare's source quantity scale without conversion. Tushare documents `rqmcl` as shares/units/lots depending on instrument, so D029 does not normalize it to a different scale.

The official endpoint page does not prove current account permission or effective runtime quota. Python configuration/decorator both state 150 requests/minute; the shared Java Tushare budget remains subject to the coordinator's conservative configured endpoint ceiling and account grant.
