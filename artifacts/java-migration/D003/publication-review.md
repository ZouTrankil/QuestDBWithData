# D003 WAL publication and retained-scene recovery

IndexCatalogPublication requires the durable whole-index lease, the frozen endpoint/physical target identity, and exact current target/stage snapshots before storing a publication intent. ReferencePublicationJournal records a unique dataset/run intent and optimistic phase revisions. The original target is retained as a backup; the verified stage is renamed into place. Both tables must satisfy the monthly WAL contract, settled WAL and frozen full-row fingerprints before the publication is VERIFIED. Two renames are not atomic and a temporary target-name gap is possible.

Interrupted recovery requires stopped-writer proof and exact actual layout. ORIGINAL completes both renames, OLD_MOVED completes only the remaining rename, PUBLISHED only verifies. Conflicting names, identities or contents do not trigger automatic mutation. This component verifies publication; it does not by itself mark an owning sync job complete.

The first live run (`local-D003-publication-06fc`) completed ordinary publication but failed when immediately inspecting the interrupted OLD_MOVED scene: a renamed WAL table had not yet settled. The stage, backup, journal and lease were retained. The fix waits at most 20 seconds for WAL readiness before the normal physical snapshot checks; it does not waive WAL or full-value verification.

`local-D003-publication-06fd` passed the ordinary/interrupted live publication test and two existing endpoint identity tests. Each publication uses two real file rows in isolated monthly WAL tables. Full values and the original backup match; successful test targets/backups were dropped. Evidence: publication-36cfcdf7887844e0891c678664b9c6c7/publication-readback.json.

After the original Gradle process had returned terminal exit 1, `local-D003-observed-recovery-40d1` recovered its actual retained OLD_MOVED scene. It performed one remaining rename, zero new source requests and zero row inserts, verified the two-row target fingerprint, and released the lease. Evidence: observed-recovery-catalog-publish-true-3b1a3d99393346f6a586106e3d17fad8.json. Its target and backup remain available for inspection. The fixture ledger job is test.index_publication, not a completed formal sync job.

The interruption is an injected Error after synchronous DDL, not an OS process-kill test. Production index was not changed. D003 still requires the file owner, formal run/attempt/slice state, management and combination routing, recovery integration and final dataset acceptance.
