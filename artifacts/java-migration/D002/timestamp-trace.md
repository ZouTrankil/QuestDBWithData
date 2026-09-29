# D002 `update_time` source-to-storage trace

Read-only code trace, 2026-09-29. No Java D002 write is implied.

1. `stock_detail_info_sync.py:merge_and_organize_data` calls `preprocess_dataframe`, which initially sets an aware UTC `update_time`, then overwrites each record with naive `datetime.datetime.now()` on the host. It is an observation time, not an upstream `stock_basic` revision cursor.
2. `StockDetailInfo.get_questdb_schema()` declares `update_time TIMESTAMP` and no explicit `temporal_columns` or `column_formats`. `BaseDataModel.prepare_questdb_dataframe()` invokes `temporal_spec_from_schema()`; its legacy adapter classifies names ending `_time` as `UTC_INSTANT` with `allow_naive_utc=True`.
3. `normalize_temporal_series()` calls `pd.to_datetime(..., utc=True)` for this instant, thereby interpreting the naive host clock as UTC. Because the table has no designated timestamp, `insert_qwp_dataframe()` declines the QWP path; the PGWire path normalizes aware instants to naive UTC before binding.
4. Thus the legacy stored clock may be shifted relative to the actual instant if the Python host local timezone was not UTC. Code alone cannot establish the host timezone at each historic run. Existing `update_time` must be preserved as a physical legacy value; no blind timezone correction or `updated_since` use is justified.

For new Java observations, use an explicitly UTC `Instant`, map it to QuestDB's timezone-free TIMESTAMP as UTC clock values, and document that new records have a different time provenance from legacy observations. Verify actual PGWire round trip on an isolated table before accepting it.

Code anchors: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/basic/stock_detail_info_sync.py` (`merge_and_organize_data`); `.../connectors/support.py` (`preprocess_dataframe`); `.../questdb/models/stock/fundamental.py` (`StockDetailInfo.get_questdb_schema`); `.../common/persistence/questdb_model.py` (`prepare_questdb_dataframe`); `.../common/persistence/questdb_temporal.py` (`TemporalColumnSpec.from_legacy`, `normalize_temporal_series`); `.../common/persistence/questdb_transport_runtime.py` (`insert_qwp_dataframe`); `.../common/persistence/questdb_write.py` (`_normalize_pgwire_temporal`).
