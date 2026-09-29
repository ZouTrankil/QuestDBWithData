# D001 initial real write/read and idempotency

Status: in progress. Not a completed dataset task yet.

ExchangeCalendarWritePort implements finite QWP batches and exact four-column SELECT by exchange/calendar date. It uses the declared business-date mapping and rejects empty/oversize/duplicate-key batches before borrowing a sender. QuestDbWriteChecks verifies columns/types/dedup keys, designated timestamp, partition and settled WAL without running DDL or repairs. The existing VerifiedBatchExecutor owns acknowledgement versus full-value/WAL verification and uncertain-write behavior.

`local-D001-write-live-853d` passed an opt-in actual source/write/read test. Two real trade_cal requests supplied SSE and SZSE, September 25–28 inclusive, eight rows. An owned isolated YEAR/WAL table received the rows and then the same rows again. Both writes passed full-key/all-field verification, the second read still contained exactly eight unique rows, and settled WAL was checked. Independent SELECT compared exchange, timestamp microseconds, is_open and pretrade_date. The test-owned table was dropped only after success.

Evidence: `9c439aa969c545578b497d03a306b49f/write-readback.json`, original source JSON files and `independent-values.json`. `tools/verify_calendar_evidence.py` independently reconstructs expected values from original trade_cal responses and compares actual SQL rows: eight matched, zero mismatched/missing/duplicate keys. The two send passes submitted 16 rows in total to a new table containing eight final rows; this does not claim 16 inserted rows.

This establishes initial nonempty sync-to-write-read and same-batch idempotency, not incremental checkpoint acceptance. Remaining work includes persistent per-exchange verified coverage, rereading revision overlap, runner/job/CLI and read/write group admission, recovery/cancellation and full task result registration. No observed source revision is claimed by this test.
