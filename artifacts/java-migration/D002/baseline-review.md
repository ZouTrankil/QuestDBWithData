# D002 baseline and implementation constraints

Status: running, not verified. D001 is recorded as verified before this task begins.

Read-only actual QuestDB baseline: stock_detail_info has 18 columns, 5908 rows and 5908 distinct ts_code values. It has no designated timestamp, partition NONE, WAL disabled and no physical dedup. Three complete-column samples include two listed instruments and the delisted 000003.SZ. Evidence: physical-baseline.json with explicit queries and parameters embedded in the SQL.

Python owner: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/basic/stock_detail_info_sync.py`; model: `.../questdb/models/stock/fundamental.py`. The connector fetches stock_basic separately for L/D/P, requests 17 source fields, stamps update_time locally and publishes a verified non-WAL staging table by rename with backup/rollback. It does not supply an upstream updated-since cursor. Its row/count checks are weaker than this migration's required full-value validation. Existing Java seven-column stock-basic snapshots are not an equivalent D002 implementation.

Required choices before admitting writes:

- Keep ts_code as natural identity. Physical non-WAL/no-dedup means the calendar QWP/WAL writer cannot be reused as-is: append would duplicate keys. Design and test bounded incremental merge/publication with explicit conflict and recovery behavior, preserving unrelated rows.
- Distinguish source business dates list_date/delist_date from local observation update_time. update_time is not an upstream business change timestamp and must not be used as a Tushare incremental date filter.
- Python preprocess_dataframe stamps UTC, but this connector subsequently replaces it with naive datetime.now(). Trace the final writer conversion before assigning a timezone interpretation to existing stored values. Do not silently shift historic timestamps.
- Actual legacy delist_date contains literal string `None` for listed instruments. Define an explicit compatibility null rule with retained raw evidence; strict new source parsing must not silently accept arbitrary malformed dates.
- Source parameters must cover status L/D/P with finite request sizes and source failure distinct from a legitimate empty status/code response. Request-scoped synchronization cannot delete other codes or treat an absent source row as a delisting.

No D002 Java model, new source call, write or checkpoint has been accepted yet. Production table remains read-only in this investigation.
