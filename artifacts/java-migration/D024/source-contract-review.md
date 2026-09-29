# D024 `moneyflow` source contract review

Review date: 2026-09-30. Python source was inspected read-only; no Tushare request was made.

## Python request behavior

`moneyflow_sync.py` resolves its end date through `resolve_completed_sync_end_date(end_date)`, obtains open SSE/SZSE dates, then calls `pro.moneyflow(trade_date=YYYYMMDD)` once per date. It does not paginate. The Python module documents one-request maximum 6,000 rows, and its row limit is therefore a completeness boundary rather than a page size. D024 sends the same one-day request and rejects an unpaged response with 6,000 rows as potentially truncated. Java planning uses `DailySyncEndDate` with the 20:30 Asia/Shanghai completion ceiling, so a still-forming current-day close is not requested. It persists raw rows as `unverified` evidence before failing.

The connector catches connection, timeout, and value errors and returns an empty frame. The Java migration fails the date slice instead of turning a failed request into a valid empty source response. A successful zero-row API response is preserved as a complete empty receipt, but an empty response cannot replace a date where the target already contains rows.

## Fields and normalization

The source model and official [Tushare `moneyflow` page](https://tushare.pro/document/2?doc_id=170) agree on the 20 physical fields. Eight directional volume fields plus `net_mf_vol` are integer hands; the Python connector explicitly fills their nulls with zero before converting them to integers, so the Java mapper does the same. The nine amount fields are doubles in 10,000 CNY; source nulls remain null and no scaling is applied. Net volume/amount are signed. The official note says net inflow is calculated from active-order L2 values and must not be synthesized by subtracting size buckets.

The official endpoint documents a 6,000 row maximum. The task card and connector both state `150/min`; D024 uses the common shared Tushare request budget and does not raise it. Permission and current-account quotas remain unverified until root-controlled acceptance.

## Keys, revisions and write behavior

Natural and audited physical UPSERT key are `(ts_code, trade_date)`. Existing physical schema is YEAR partitioned, WAL enabled, DEDUP enabled. D024 isolated DDL preserves that key/schema. A five-calendar-day run issues at most one source request for each open date, capped to 30,000 rows total. First incremental bootstrap needs an explicit start and an empty isolated target; later runs use the receipt-backed checkpoint and re-read two calendar days of overlap.

Same-key source corrections are applied by QuestDB DEDUP upsert and verified by the generic full-key/full-value runner. Only incremental receipts extend the checkpoint; a backfill receipt may replace the latest validated source snapshot only for a date already inside that incremental chain. The API is treated as an authoritative full-date result. If a fetched date omits an already-present key, D024 fails before writing: no deletion/tombstone policy is documented, and this implementation does not silently leave stale rows while claiming a complete date. This boundary applies to zero-row responses as well. A future deletion policy would need a separate staged full-window replacement with actual acceptance.

No Flyway migration or formal-table DDL is added. D024 accepts only a named `java_d024_moneyflow_<suffix>` isolated table.
