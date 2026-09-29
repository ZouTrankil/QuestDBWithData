# D006 · `ths_member` local acceptance

## Scope and contract

- Python reference was inspected read only: `src/quant_platform/data/adapters/connectors/index/ths_member_sync.py` lists THS boards, calls `ths_member(ts_code=...)` once per board, then concatenates all responses before replacing a snapshot. The Java owner freezes one exact board per run. The [Tushare endpoint documentation](https://tushare.pro/document/2?doc_id=261) has no date or paging contract. Its unpaged 10,000 row cap is fail closed.
- Business key is `(ts_code, con_code)` for a current membership row. Physical MONTH/WAL dedup is `(ts_code, con_code, update_time)`; a new observation timestamp cannot replace the old board by itself. Eight fields are mapped with explicit nullable business dates and a microsecond UTC observation instant. The provider constituent code is preserved as an opaque nonblank string because the physical table contains more suffix forms than a mainland-only grammar.
- Formal table `ths_member` stayed read only. The registered owner rejects publication to that table until a separate consumer cutover. Actual writes used isolated tables created from two real boards.

## Current source and storage evidence

- Formal physical scan: 416,612 rows, 506 for `885800.TI`, 416,106 other rows, physical ID 1779 and unchanged directory; the scan uses a bounded board list and streams the other rows into a SHA-256 fingerprint. See `physical-scan.json`. The source preflight fetched 5,565 rows for `700001.TI` and 506 for `885800.TI`; see `source-preflight-61bf5181-d821-4f4f-9abb-226d18db1643/source-review.json`.
- The typed reader paged `885800.TI` in six pages and compared all eight stored fields for 506 keys with zero mismatch; read group returned the same typed projection. See `read-live.json`.
- The real source adapter returned 506 unique typed keys and a byte-hashed raw response receipt; see `source-199f9deb-4303-4887-8bf0-5b225a314706/source-review.json`. A separate live comparison found the same 506 keys in the stored board but all 506 business values revised, including the source `is_new=Y` versus the stored null flag. See `board-comparison.json`.

## Isolated publication and recovery

- A two-board isolated target started with 5,565 unaffected rows and 506 rows in the selected board. The stage copied and streamed all 5,565 unaffected rows, inserted all 506 real source rows in batches of at most 250, then read back the complete board row values. The original target remained untouched until publication. See `stage-b07c37f8-074c-4680-a8f3-aaf3ea307f62/stage-review.json`.
- A journaled table publication passed normal and simulated interruption after the old name was moved. Recovery required explicit stopped-writer proof and checked target, backup, stage identities and content. See `publication-bdb9f9ea34854d0483ab63cb97cc39f2/publication-review.json`.
- The registered source job produced run/attempt/slice receipts and verified 506 source rows plus 5,565 copied rows in an isolated table. Same scope rerun returned 506 rows with no new publication or physical change. See the latest `job-*.json` evidence.
- An injected ledger failure after physical publication remained `IN_DOUBT` until stopped-writer recovery rebuilt the completion proof from the frozen source receipt without another source call. Recovery before publication intent creation rebuilt a fresh stage and retained the old stage for inspection. Earlier failed test runs were subsequently reconciled and their isolated test tables cleaned after verification. See `run-recovery-9e8fc964f1664f45b48a7252792532f0/recovery-review.json`, `early-recovery-1f559f3cb2ac45f2bb29704a587b1075/early-recovery-review.json`, `retained-recovery-review.json`, and `early-recovery-7cbb1c7c93a14535aa3c992ce27a2b9e/retained-recovery-review.json`.
- A prepared typed write group accepted the same 506 real source rows with a separate `prepared-write-request` receipt, verified the board, reused its child on group resume, and skipped publication on a new same-payload run. A manual source sync group likewise executed and reused a verified child. See `prepared-a47ddf063fe345d686930e4cd0aa85a0/prepared-review.json` and `group-4b5ba1fa35b3467b9c441fc3737bb2f2/group-review.json`.

## Failure boundaries and limits

- Source cap hit, duplicate key, out-of-board row and cancellation produce no completed source receipt. Shared Tushare rate budget has an endpoint ceiling of 200/min, bounded retry/timeout and cancellation. If an existing board returns empty, the owner refuses to delete its rows. An unknown stage or table swap is held for explicit stopped-writer reconciliation.
- The source does not supply an upstream snapshot version or change cursor. `update_time` is a local observation. One board response can be internally bounded, but a whole provider-wide atomic snapshot cannot be proven from 2,517 separate requests. The Java job deliberately refreshes one board at a time. No production publication, consumer cutover, or 416,612 row replacement benchmark is claimed here.
- Human review remains `pending_review`. This is local D006 implementation and isolated live acceptance; it is not authorization for formal-table cutover.
