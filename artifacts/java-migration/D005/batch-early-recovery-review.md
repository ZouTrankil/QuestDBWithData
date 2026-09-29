# D005 early coordinator recovery (2026-09-29)

`local-D005-early-resume-two-phases-build` passed two live isolated QuestDB cases with a saved real Y response. The source rows were replayed from the prior real receipt; these two tests did not make fresh Tushare calls.

The first case stops the coordinator after its frozen plan and RUNNING ledger entry, before attempt creation. The second stops after creating attempt and industry slot entries but before creating the first source child. Both have zero source requests and zero membership rows before recovery. With explicit `writerStopped=true`, recovery checks the original physical target identity and full content fingerprint. It marks the old coordinator PARTIAL, starts a new coordinator from an empty verified prefix, makes one bounded source request, publishes four rows in an isolated YEAR/WAL table, and verifies all 14 fields. The formal table snapshot remains unchanged.

Evidence is in the two `artifacts/java-migration/D005/early-resume-*/early-resume-readback.json` files and their retained SQLite ledgers. These tests simulate abrupt process loss with a caught Java `AssertionError`; they do not prove recovery after an operating-system kill. The writer-stopped assertion remains an explicit caller responsibility.
