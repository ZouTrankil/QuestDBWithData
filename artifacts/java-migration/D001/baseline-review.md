# D001 exchange_calendar baseline

Status: in progress; no new source sync or write performed.

Current QuestDB schema and 30 bounded SSE calendar rows for September 2026 are captured in physical-baseline.json. This is actual SQL evidence, not the generated ExchangeCalendarRow projection. The four physical columns are exchange, cal_date, is_open and pretrade_date. The task's logical business date must remain distinct from the TIMESTAMP storage representation; mapping must validate the observed day boundary rather than accept arbitrary intraday instants.

Read-only Python references inspected:

- D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/basic/trade_calendar_sync.py
- D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/system.py

The existing connector requests SSE and SZSE, declares 20 calls/60 seconds, reads a global last calendar date and otherwise defaults through one year ahead. Its retry wrapper can return an empty dataframe after network failure. These behaviors are reference facts, not requirements to copy: Java must preserve failure versus empty, partition requests by exchange and finite time slices, and reread a bounded revision overlap instead of only MAX(date)+1. No local weekday approximation is acceptable for exchange sessions.

Next: define typed business date/key and strict mapper, declare schema/WAL/key compatibility, add bounded source/runner and read/write owner integration, then perform real nonempty source-write-read and incremental/idempotency acceptance. No dataset registration is claimed by this baseline.
