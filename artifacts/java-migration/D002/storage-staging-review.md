# D002 storage and staging acceptance

Status: running. No target publication or D002 completion is claimed.

StockDetailInfoStorage verifies all 18 types and non-WAL/NONE/no-designated/no-dedup metadata. A single explicit-column snapshot query is capped at 10,001 rows, rejects more than 10,000 rows, duplicate identities and payloads above 16 MiB, validates mapping and checks physical identity before/after reading. Raw records are retained separately from business values. Key reads are limited to 250 unique codes.

`local-D002-static-read-76be` passed: actual production table read-only, 5,908 mapped records, 2,638,733 canonical bytes; three exact-key results match complete snapshot records. `static-read.json` retains identity, digest and selected rows. The prior 76bd attempt rejected historical T600018.SH; `code-boundary.json` records the actual row. The explicit code contract now admits that T-prefixed Shanghai form without dropping or replacing the row.

StockDetailInfoStaging preserves unchanged raw records, including legacy sentinels, and maps only changed business rows. It bounds replacement bytes before creation, records unique stage intent, creates a non-WAL table, inserts through parameterized PGWire batches of at most 250 rows and compares every raw field after writing. It neither renames nor alters the current table. Failure/cancellation retains any stage for inspection; unchanged/empty input does not create one.

`local-D002-stage-live-78cd` passed using two real stock_basic records: first a one-row stage, then a two-row stage after a bounded incremental merge. All 18 fields match, the original row is identical in the second stage, and repeated input reports two unchanged rows and no new stage. The original test-owned empty target remains empty. Evidence: `stage-982d8b79bec74cc6a586247f5fe5f877/stage-readback.json`, source receipts, intent and verified snapshot files. Successful owned tables were dropped after evidence was retained.

`local-D002-stage-cancel-59ce`: two controlled tests passed; preflight cancellation and unchanged input never obtain a database connection.

Remaining: durable publication state and backup/rename recovery, exact original-target comparison, interval exclusion covering source fetch through publication, unknown-outcome reconciliation, owner and group admission. Mixed-provenance observation timestamps are not upstream revision clocks; publication must not rely on numeric timestamp ordering to reject/accept a source revision (see revision-clock-risk.md). Staged success alone does not advance checkpoints.
