# D026 source contract review — `moneyflow_dc`

Status: source contract reviewed from local Python call path; no Tushare request was made for this implementation handoff.

## Observed Python path

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/moneyflow/moneyflow_dc_sync.py` defines `get_moneyflow_dc_by_date(pro, trade_date)` and calls exactly `pro.moneyflow_dc(trade_date=trade_date)` (line 48 in the inspected checkout). The sync loop iterates the exchange trading dates and makes one full-market call per date.
- The connector catches connection/timeout/value errors and returns an empty frame. D026 intentionally does not carry that behavior over: failed requests remain failures and cannot create verified-empty receipts.
- Python date selection resolves a completed end date, then asks `get_trading_dates_between(start, today)`. The connector writes each nonempty day through `questdb_client.write_model`.
- `src/quant_platform/data/adapters/questdb/models/stock/fina/moneyflow_dc.py` declares 15 columns, `trade_date` timestamp, YEAR partition, WAL, and dedup keys `(ts_code, trade_date)`.
- The Python model and connector documentation state source coverage begins on 2023-09-11 and that access requires at least 5,000 Tushare points. D026 rejects bootstrap dates before that source start; current account permission remains unverified.
- Python model fields are `trade_date`, `ts_code`, `name`, then the 12 numeric fields. The model describes amounts in 10,000 CNY and rates in percent. The Java mapping retains those values without rescaling; source nulls remain null.
- `artifacts/storage-audit-20260929/objects.csv` records the external table as a 15-column data model, `trade_date`/YEAR/WAL/DEDUP, full key `(ts_code,trade_date)`, 3,478,841 rows, and source coverage through 2026-03-20 in that snapshot. This is an audit snapshot, not current live-schema verification.
- The source module comment and the repository's historical interface specification state a 6,000-row maximum for one request. The endpoint has no documented offset path in the Python implementation. D026 therefore issues one request per open date and fails closed when the result reaches 6,000 rows. Current account permission and effective quota remain unverified.
- Configuration declares rate limit 150/minute; the connector decorator declares 1,500/minute. D026 records both facts and requires the shared endpoint budget to use the conservative configured value (150/minute) until the effective account grant is confirmed.

## Frozen request and mapping

`MoneyflowDcSource.CONTRACT` requests fields in this stable order:

`ts_code, trade_date, name, pct_change, close, net_amount, net_amount_rate, buy_elg_amount, buy_elg_amount_rate, buy_lg_amount, buy_lg_amount_rate, buy_md_amount, buy_md_amount_rate, buy_sm_amount, buy_sm_amount_rate`.

The complete key is `(ts_code, trade_date)`, where `trade_date` is parsed as a BASIC ISO business date and stored as a UTC-midnight timestamp carrier. `name` is nullable text; all other non-key numeric fields are nullable finite doubles. The value scale remains the provider scale: prices in CNY/share, amounts in 10,000 CNY, rates and `pct_change` in percent. No values are derived.

The isolated candidate schema is YEAR/WAL/DEDUP with `ts_code,trade_date` upsert keys. D026 does not change or migrate the audited external table. A full-cap response, duplicate key, wrong response date, unknown/unrequested response field, failed request, missing receipt, or unexplained existing target date is a hard failure.
