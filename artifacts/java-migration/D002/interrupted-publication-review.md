# D002 nonterminal-phase recovery and lost acknowledgement

Status: running; owner/run integration remains required.

Recovery now accepts the owning publication's nonterminal phases after explicit stopped-writer proof, including PREPARED, OLD_RENAMED, NEW_RENAMED and RESTORING_OLD. It checks the actual whole-dataset lease, retains an ordinary lease as uncertain if the prior process did not do so, and normalizes the publication to IN_DOUBT before deciding from real table contents. Terminal results and other runs' publications are rejected.

restoreOriginal still requires ORIGINAL or OLD_MOVED with exact old/stage identities and fingerprints. acceptPublished is a new read-only QuestDB reconciliation path: it requires PUBLISHED with the expected target and backup, then changes only the local publication journal to VERIFIED. It never repeats a rename or source write. The caller must separately finish the sync-run result and release the uncertain lease after reconciliation.

`local-D002-interrupted-live-218c` passed the real QuestDB recovery test and journal regression. The live test creates isolated non-WAL tables from a real one-row stock_basic response, then injects an Error after synchronous DDL at OLD_RENAMED and NEW_RENAMED. Because Error escapes the ordinary Exception handler, SQLite retains the nonterminal phase and ordinary lease rather than a caught IN_DOUBT result. A fresh publisher/journal observes the actual layout. Recovery without stopped-writer proof is rejected. With the test-owned synchronous call stopped, OLD_RENAMED restores the original empty target; NEW_RENAMED finalizes the one-row target without rewriting it. Both actual target snapshots match all stored values. Successful owned tables are removed after recording results.

Evidence: `interrupted-cd40b2e34d424e05af601a02f0bb2834/recovery-readback.json`, original source response receipts, stage intents and verified snapshots. This simulates abrupt unwinding; no OS process was killed and no live network timeout was injected. It proves recovery from persisted nonterminal phases against real tables, not OS-level crash scheduling or automatic stopped-writer detection.

Remaining: task owner must hold exclusion from source observation through publication, preserve final run/checkpoint evidence despite physical table replacement, expose management and composition, and use the observed recovery result without promoting an intermediate staging or journal entry to a completed sync task. Recovery permissions still depend on actual stopped-writer evidence; they cannot be inferred from a timeout.
