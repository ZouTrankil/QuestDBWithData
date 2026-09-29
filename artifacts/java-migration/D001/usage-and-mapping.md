# D001 field contract and bounded commands

Python reference root: `D:/work/fund_2/back-monitor`. Continue checking `src/quant_platform/data/adapters/connectors/stock/basic/trade_calendar_sync.py` and `src/quant_platform/data/adapters/questdb/models/stock/system.py` when the upstream contract changes.

| Source / physical column | Logical field | Java business type | Storage compatibility |
| --- | --- | --- | --- |
| exchange | exchange | String, admitted SSE/SZSE | SYMBOL; required key |
| cal_date | calendar_date | LocalDate | TIMESTAMP at UTC midnight as a date carrier, never an event instant |
| is_open | is_open | boolean in ExchangeCalendar, Integer 0/1 in DatasetValues | INT; reject null and values other than 0/1 |
| pretrade_date | previous_trade_date | nullable LocalDate | nullable STRING yyyyMMdd; if present must precede calendar_date |

The complete business key is `(exchange, calendar_date)`; its physical equivalent is `(exchange, cal_date)`. Existing physical columns remain compatible. The table requires designated cal_date, YEAR partitioning, WAL and DEDUP UPSERT KEYS(exchange,cal_date). Preflight rejects incompatible tables; it does not alter production DDL. No currency, unit scaling or numeric rounding applies. Actual observed dates, including weekday closures, come from trade_cal.

The default incremental source request is bounded by explicit bootstrap/end dates. Verified same-target history supplies per-exchange candidates, actual target coverage is read before use, and up to 366 days are reread for revisions, clipped by the caller's bootstrap bound. Requests are split by exchange and year, with at most 3660 days per exchange. A known finite calendar slice requires all dates including closed days: empty, missing or duplicate days are incomplete and never sent as a successful empty write. Natural source revisions were not observed in acceptance; overlapping refetch, new-day addition and idempotent replacement were verified.

CLI arguments (supply them to the application's configured local launcher):

```text
plan-exchange-calendar --exchanges SSE,SZSE --from 2026-09-25 --to 2026-09-28 --logical-date 2026-09-29
run-exchange-calendar --exchanges SSE,SZSE --from 2026-09-25 --to 2026-09-28 --logical-date 2026-09-29
show-sync-job --job data.exchange_calendar --version 1
```

`plan-exchange-calendar` preflights and reads QuestDB; it does not fetch Tushare or write data. `run-exchange-calendar` performs bounded source sync and verified writes. Explicit `--mode backfill` or `--mode reconcile` uses the supplied window. For `--resume-from`, provide the exact frozen original window, exchanges, logical date and mode; do not substitute a newly advanced incremental plan.

`group.exchange_calendar_manual` runs the same calendar owner. `group.reference_manual` orders calendar then the existing stock-basic job. `run-sync-group` accepts the same group/version/logical-date/parameters/overrides inputs as `plan-sync-group`, plus optional `--resume-from`; use per-member overrides for different fields and snapshot versus date windows. Only the calendar-only sync group has live acceptance at this point. No timer is enabled by registration.

The read group returns typed DatasetValues for explicit projections containing both key fields. Full domain reads are provided by ExchangeCalendarReadRepository. `write-dataset-group --request <file>` admits complete calendar rows through the owning mapper and verified write port. See the saved `write-request.json` under the write-group acceptance folder for an actual source-derived example. Groups run serially and do not offer cross-table atomic commits.

Human comparison: compare each saved raw trade_cal response to the explicit four-column SQL rows and independent-values receipts; distinguish submitted rows from final distinct rows. Then inspect ledger source/verified counts, checkpoint before/after, physical target identity and failure states. Test-owned isolated tables were dropped after success; saved query results and source receipts remain. Production suitability and human acceptance are not inferred from these bounded tests.
