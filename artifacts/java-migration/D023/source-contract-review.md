# D023 `dc_index` source contract review

Review date: 2026-09-30. Python reference was read-only; no provider request was made.

## Python call path

`src/quant_platform/data/adapters/connectors/index/dc_index_sync.py` implements `_fetch_dc_index_by_date(pro, trade_date)` as exactly one `pro.dc_index(trade_date=YYYYMMDD)` call per calendar date returned by `get_trading_dates_between`. It has no offset/limit loop. The model documents a 5,000-row maximum. `sync_dc_index` uses `trade_date` as its cursor, starts first/forced runs from `DC_INDEX_START_DATE` or `20240101`, and otherwise begins after the target MAX date. It catches connection/time/value failures as an empty frame; the Java owner deliberately fails closed and does not translate errors to empty receipts.

The corresponding Python `DcIndex` model maps eleven fields, with `trade_date` a business calendar date and `ts_code` the DC-board code. `total_mv` is described as 万元 in the Python model. D023 preserves values without rescaling. `pct_change`, `leading_pct`, `turnover_rate`, and `total_mv` units need a live source-to-model sample comparison before acceptance; the endpoint page currently available does not document these fields.

## Official documentation drift

The current official [Tushare DC concept-board market page](https://tushare.pro/document/2?doc_id=382) documents endpoint `dc_daily`, a maximum of 2,000 rows, and the fields `ts_code`, `trade_date`, OHLC/change/volume/amount/swing/turnover/category. Those do not match the Python call `dc_index`, its 5,000-row model note, or the audited 11-column `dc_index` table. No Java fallback to `dc_daily`, field synthesis, or mixed endpoint is implemented. Before live acceptance, verify the exact-account `dc_index` response and determine whether a legacy endpoint remains callable; if only `dc_daily` is available, this is a different dataset/schema task and must not be silently treated as D023.

The D023 card separately records a Python-side `200 requests / 60 seconds` decorator. It is source code evidence, not the account's granted quota. D023 uses the existing shared Tushare limiter and leaves the conservative configured budget in force.

## Key and physical behavior

The inferred source business key is `(ts_code, trade_date)`, because the Python loop retrieves a complete date and the model has one daily board observation per code. The audited physical table has no UPSERT key and `DEDUP=false`; the Java code preserves that fact. Duplicate source keys fail closed. A verified target replacement must use an exact-schema DEDUP=false stage and a journaled table rename, not an added physical dedup key.
