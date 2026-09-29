# D001 cancellation and uncertain-write boundaries

`local-D001-fault-regression-d274` passed both CalendarFailure tests using the real calendar adapter, persistent SQLite ledger and shared runner with controlled source/transport doubles. These are implementation fault tests, not additional real-source or QuestDB-write acceptance.

A cancelled request does not reach the source or writer. A transport exception after submission remains IN_DOUBT even when a readback double reports matching rows and settled WAL, because stopped-sender evidence is absent. Reopening the ledger retains that state; another run for the same interval returns DATASET_INTERVAL_BUSY without another fetch or send. No checkpoint is reconstructed from the uncertain run.

Real completed-child recovery is covered separately by `group-08b7c5b182c442aaa9dd63efee350783/group-readback.json`: source and QuestDB revalidation precede reuse. No uncertain production writer was stopped or replayed in these tests. Network retry/rate-limit implementation tests remain in the shared HTTP suite and calendar rate/source tests; real source access is documented separately.
