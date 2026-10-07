# Architecture refactor execution record

Approved scope: T00–T18, in this order. This file records acceptance and remaining work;
an unchecked task has not passed its gate. Production access and Git delivery are outside this execution.

## Task order

| Task | Scope and acceptance | Status |
| --- | --- | --- |
| T00 | Worktree/entrypoint/test baseline; JSON, fingerprints and recovery references | Complete: baseline captured, 19 existing failures |
| T01 | Correct generator output/package and bounded cleanup; temporary output and repeatability tests | Complete |
| T02 | Ledger management service; explicit CLI path > configured path > default | Complete |
| T03 | Unified CLI/web startup using Spring external/profile configuration; conflicts and finite CLI lifetime | Complete |
| T04 | Package rules and enforceable finite violation baseline | Complete |
| T05 | Domain rules and target identity policies; remove concrete service reverse dependencies | Complete |
| T06 | Main ledger/lock SQL and schema probes behind storage APIs | Complete |
| T07 | Evidence store with bounded reads, path validation, digests and immutable writes | Complete |
| T08 | Shared private-instance attestation; preserve Windows admission/rejection behavior | Complete |
| T09 | Batch store operations and transaction boundaries; no business access to ledger JDBC | Complete |
| T10 | CLI command families, shared parsing/output/exits and thin dispatcher | Complete |
| T11 | Registered dataset readers; explicit generic fallback and binding validation | Complete |
| T12 | Shared field/type/key/time/version semantics; daily compatibility first | Complete |
| T13 | Source strategies for request/normalization/coverage/key/DDL; generic collector | Complete |
| T14 | ETF package pilot: daily, adj, factor; shared execution and run-scoped state | Complete |
| T15 | Families in order: other ETF/fund; stock/calendar; index; flow/margin; macro; derived; L2 ingestion | In progress: stock/calendar |
| T16 | L2 batch computation service and checkpoint store; cancellation/resume/hash tests | Pending |
| T17 | Gradle data-core, data-app, batch-app; isolated resources and driver checks | Pending |
| T18 | Offline regression, distributions/startup and documentation | Pending |

## Compatibility gates

- Keep CLI/API contracts, serialized request/evidence bytes, stable request fingerprints, data values and recovery transitions.
- Preserve existing workspace edits. Record existing failures separately from regressions.
- Use local mocks, loopback fixtures and temporary databases. `tools/run_offline_tests.ps1` excludes live/recovery tests and clears enabling flags in the test process environment.
- L2 compute fingerprints deliberately include compiled class bytes and runtime versions. Refactoring may invalidate old checkpoints; preserve safe recomputation on mismatch rather than accepting incompatible checkpoints.
- Do not merge the main application and batch runtime state machines merely because they share dataset names.

## T00 baseline

- Git HEAD: `49d59372dc4241ab1748273d61d6fcf87abbd3e8`.
- Starting workspace includes uncommitted JSON mapper reuse/strict reader optimizations and existing CLI/CSV removal work. Local snapshot is under `.gradle/refactor-baseline/T00` (ignored).
- Java 24 required; portable Temurin 24.0.2+12 is available under `.gradle/toolchains`.
- Existing app entrypoints: `QuestDataApplication`, `BatchApplication`, `BatchControl`, `L2DailyFeatureCli`, `L2DailyFeatureBatchCli`.
- Main resources: `application.yml`, QuestDB migrations. Batch resources: `batch-runtime.yml`, SQLite migrations, source contracts, trust certificate. Both use logging configuration.
- Existing golden tests: `FrozenRequestJsonSerializationTest`, `SyncRequestIdentityTest`, `NativeSourceTest`, `FrozenSourceParityTest`, `SqliteRuntimeTest`, `L2DailyFeaturePipelineTest` plus runner/recovery tests.
- Existing risks: `CliProcessTest` launches the removed `QuestDbWithDataApplication`; index membership planning tests reference a missing retained classification fixture; no direct L2 batch checkpoint/resume tests.
- `SyncJobCatalogApplicationTest` is excluded because it overwrites a retained artifact. Seven non-Live external test classes are also excluded explicitly by the offline runner.

### Verification

Baseline offline suite: **876 tests, 19 failures**, completed in 3m22s. Full XML, log and failure names are retained in `.gradle/refactor-baseline/T00`. These failures predate T01 and are not accepted as successful tests.

Known failure groups: Windows symlink/private-cookie fixture permissions (2); Windows Java executable fixture (2); missing L2 CSV/Python golden resources (6); stale main class in CLI process tests (2); retained catalog byte hash (1); missing index membership retained fixtures (6). T03 handles the stale entrypoint; T16/T18 address portable fixtures and explicitly report unavailable external parity evidence. T00 accepts an observed baseline, not a green regression gate.

A skipped or excluded live test is not evidence of live database compatibility.

## T01 — generator

Corrected the default package directory and added `--output-root`. Catalog/target validation now precedes writes and cleanup; generated marker ownership, path containment, case-insensitive collisions, Java identifiers/types and UTF-8 output are explicit. Cleanup is limited to generated Java files directly inside the three schema packages. The existing 184 source files were not regenerated.

Acceptance: `python -B -m unittest discover -s tools -p test_generate_domain_models.py -v`: **9 passed, 1 skipped** (Windows cannot create symlinks). Platform-independent path traversal rejection passed. Full catalog generated 184 files in a temporary directory and repeated with identical bytes. `git diff --check` passed.

## T02 — ledger management

`LedgerManagementService` now owns cancellation, history and run detail access. The CLI passes an optional override; the service otherwise uses `app.sync.ledger-path`, falling back to `var/sync-ledger.sqlite3`. Construction performs no filesystem/database I/O. Existing CLI constructors, response fields, pagination and missing-file error types remain compatible.

Acceptance: **20 tests passed, zero failures/skips** across `LedgerManagementServiceTest`, `ManagementJsonCommandTest`, `SyncRunLedgerTest`, `CommandOptionBoundaryTest`, `PlanningStartupTest`. Tests use temporary ledgers, cover configured/explicit/default paths, and prove cancellation does not change delivery state or revision. Evidence: `.gradle/refactor-baseline/T02`.

## T03 — startup mode

`data.bootstrap` now classifies configuration/business arguments and resolves mode after Spring ConfigData processing. External files, active profiles, system properties, environment variables and CLI properties share Spring's precedence. Commands select NONE; explicit `--web` selects REACTIVE; contradictory explicit `spring.main.web-application-type` and SERVLET are rejected. CLI contexts close after runners finish; Web contexts remain active. Batch has no global listener installed.

Acceptance: **27 tests passed, zero failures/skips**, including actual CLI subprocess exit/status tests, argument placement, external/profile/environment precedence, conflicts and context lifecycle. The stale CLI main class and subprocess encoding baseline failures are fixed. Final evidence: `.gradle/refactor-baseline/T03-final`. The earlier T03 attempt exposed a test assertion mismatch for Spring's conversion exception; the corrected test also verifies exit code 2.

The offline evidence copier now creates a separate timestamped XML directory per invocation, with `latest-results.txt`, preventing repeated runs from mixing old and new XML reports.

## T04 — architecture guard

Added `architectureTest` to Gradle `check`, package responsibility documentation, and an exact 456-edge initial debt register (335 application/storage, 109 repository/application, 7 entrypoint/infrastructure, 3 mapper, 1 domain, 1 repository/runner). This is a baseline of existing edges, not 456 newly introduced defects or a blanket package exemption.

Acceptance: **9 tests passed, zero failures/skips**. The gate initially failed with an empty register, then passed with the reviewed finite list. Self-tests cover new/stale exceptions, invalid wildcard exceptions, generic/array/annotation/lambda references, shared annotation/string UTF8 entries, inline constants with explicit/wildcard imports, repository/storage role equivalence, and port-container execution. Ordinary strings/comments are excluded. Review findings were fixed before acceptance. Evidence: `.gradle/refactor-baseline/T04`, with the final XML directory in `latest-results.txt`.

## T05 — pure policies and reversed dependencies

Moved four index universes and index-code normalization into `domain.policy`. Added eight explicit isolated-table policies while retaining dataset-specific exception types/messages and existing owner delegates. Repository references to concrete JobService methods/constants were removed. Static target identity now separates the unchanged pure hash algorithm from repository JDBC endpoint lookup; the old service entry points delegate for compatibility.

Acceptance: **144 domain/mapper/identity tests passed, zero failures/skips**, plus **9 architecture tests passed**. Tests freeze universe ordering/routes/aliases, original admission errors, and independently calculated identity hashes including id 0, null directory, encoded database paths and default port. No repository JobService references or domain/mapper service-universe imports remain. Architecture debt dropped **456 → 383 (73 removed, zero new edges)**; only obsolete exact entries were removed. Evidence: `.gradle/refactor-baseline/T05`.

## T06 — main ledger and publication storage

Extracted interval locking into the domain `IntervalLockStore` contract and SQLite implementation, with `DatasetIntervalLock` retaining source-level delegation. Centralized 24 coverage/checkpoint schema probes and the index-weight history check. Publication intent/mutex, physical target transitions, bounded journal summaries, monthly admission queries and revision-4 event authenticity checks now live behind repository APIs. The service package has no `DriverManager` or `jdbc:sqlite` use.

Acceptance: **174 tests passed across 27 classes, zero failures/skips**, plus **9 architecture tests passed**. Temporary SQLite tests cover transaction rollback after encoding/foreign-key failure, competing mutex reservations, stale revisions, reopen/recovery, parent cancellation limits, schema guard differences, target transition ordering, and the journal's 1001-row boundary. Two independent read-only reviews found no changed SQL transaction/exception behavior. Architecture debt dropped **383 → 230 (153 removed, zero new edges)**. Evidence: `.gradle/refactor-baseline/T06` and `T06-summaries`.

Compatibility detail: internal `Scope`/`Lease` record runtime names moved from `DatasetIntervalLock` to `IntervalLockStore`; source references through the facade still compile. All modules must be rebuilt together. Serialized fields and operational contracts are unchanged; no precompiled third-party internal-class ABI is promised.

## T07 — evidence file storage

Added byte-oriented `FileEvidenceStore` for bounded reads, SHA-256, strict UTF-8 immutable writes, durable writes, atomic replacement and lexical/real path ownership. It is used by 110 production classes, including the existing catalog/membership/gateway helpers, 38 sources, 12 staging implementations and publication/recovery/admission paths. Serialization expressions and dataset-specific limits remain at their owners. DailyBasic's source fingerprint remains a sub-projection; source row fingerprints and L2 streaming fingerprints are unchanged.

Byte-equality collisions now read at most the expected byte length plus one, preserving messages and causes. JSON-semantic collision checks retain their existing semantics and limits, including legacy uncapped comparisons where extra whitespace was previously admissible. Ordinary configuration/input streams and dataset-specific directory discovery remain outside this byte store. Guarded evidence reads use bounded streams to reject files that grow after size admission. Atomic replacement still requires ATOMIC_MOVE; temporary files are cleaned after partial-write failures, and cleanup errors are suppressed on the primary failure.

Acceptance: **122 tests across 23 classes: 120 passed, 2 skipped, zero failures**, plus **9 architecture tests passed**. The two skips are actual Windows symlink capability limitations; lexical traversal rejection is tested independently. Tests cover byte/JSON collision distinctions, UTF-8 malformed input, original bytes/hashes, prepared fingerprints, 13 source writer policies, stage recovery transitions, atomic failure, partial writes and cleanup failure. Evidence: `.gradle/refactor-baseline/T07-final`; architecture debt remains **230**, with no new edges. Independent reviews checked write arguments, fingerprint inputs, original limits and durable replacement behavior.

The first T07 run also selected `IndexMembershipMergeEvidenceTest`, which still fails on the missing retained fixture recorded in T00. Its failure is retained under `.gradle/refactor-baseline/T07`; it is not counted as a successful T07 test and remains a T18 fixture task.

## T08 — private QuestDB instance attestation

Extracted the duplicated D095/D098 Windows attestation into `PrivateQuestDbInstanceAttestor`. Each materialization port owns a separate attestor and PID history; immutable JSON reader and command-line pattern are shared. D098 keeps its fixture marker callback and error behavior. Enabled/host/port checks, explicit target admission, and the stricter installation/FULL paths retain their original ordering. PowerShell port formatting uses `Locale.ROOT` so command text stays ASCII regardless of the JVM locale.

Acceptance: **185 tests across 7 classes passed, zero failures/skips**, plus **9 architecture tests passed**. Tests use temporary directories and fake processes, covering both specs, loopback/PID/port/root rejection, repeated admission and PID replacement, fixture validation, timeout destruction, interruption, output limits, malformed/empty JSON and Arabic FORMAT locale. Port tests prove explicit target identity does not bypass private installation or FULL admission. No PowerShell listener or live database was contacted. Independent review compared the generated script and original check order. Evidence: `.gradle/refactor-baseline/T08-final`; architecture debt remains **230**.

## T09 — batch ledger operations

Removed the `SqliteLedger.jdbc()` escape hatch. Durable write reservation/delivery/verification, source-probe persistence and business creation-time reads now use explicit ledger operations. Intent plus reservation and VERIFIED plus reservation release remain atomic; UNKNOWN is committed before send. Business services own protocol decisions and JSON serialization. Batch step configuration keeps external I/O outside ambient transactions.

Acceptance: **103 tests passed, zero failures/skips**, including 33 new real SQLite failure/reopen tests, the API boundary test, existing source/parity tests, 12 selected offline runtime methods and 9 architecture tests. Trigger faults exercise reservation rollback, UNKNOWN/ACK failures, verification-release rollback, source completion/audit/failure recording, nullable columns and duplicate-collection prevention. No production/test consumer calls `ledger.jdbc()`. The two pre-existing Windows child-executable runtime tests remain T18 work and were not selected. Evidence: `.gradle/refactor-baseline/T09-final`; architecture debt remains 230.

## T10 — command families and entrypoint boundaries

Split the 160 command names/84 branches into 12 command families with an immutable exact-name registry. The production runner only filters startup arguments, parses lexical options and dispatches. Six direct Java constructors delegate through a compatibility factory; optional owners, lazy schedule initialization and CLI-only conditions remain. Shared private writers preserve four JSON profiles independently from compact/pretty formatting; result output still precedes incomplete-command errors.

Ledger and schedule responses now use application read models. Four materialization snapshots moved unchanged to distinct domain records, retaining their different settlement rules. Web dataset samples pass through `DatasetReadService` with the same raw rows, null source version, query, lazy subscription, worker scheduler and HTTP error mapping.

Acceptance: **291 tests across 36 classes passed, zero failures/skips**, including 9 architecture tests. The command manifest freezes all 160 names. Independent comparison against reconstructed T00 source found 79 branches identical and the other 5 explained by already accepted T02/T05 changes and the schedule response type. All 18 family/helper output transformations were reversed mechanically to recover their pre-transformation snapshots. Snapshot tests compare original record fields, JSON bytes, validation and helper rules. Architecture debt dropped **230 → 223 (all 7 T10 entrypoint edges removed, zero new edges)**. Evidence: `.gradle/refactor-baseline/T10-complete`; supporting snapshots and transformation evidence: `.gradle/refactor-baseline/T10`.

An earlier T10 run found one zero-filled cached test class (`LedgerSchemaCompatibilityTest$Probe.class`), causing two Spring scanning failures. All classes were rebuilt, their magic bytes checked, and the complete selected suite rerun successfully. The two existing retained-fixture CLI tests remain T18 work and were excluded from this gate.

## T11 — explicit dataset reader registration

Replaced the mapper selection chain with `ReadBindingCatalog` and a declaration of all 54 current representations: 20 typed mappings and 34 explicit `DatasetValues` mappings. Unknown READ datasets fail assembly rather than silently acquiring a generic representation. Registrations validate identifiers, versions, duplicate IDs and READ capability. Partial registries use only active definitions and retain their exact physical object, columns and policies. Spring registration extensions and the existing two-argument direct assembly entry both remain available. Twenty stateless mapper instances and immutable registration metadata are shared.

Acceptance: **146 tests across 24 classes passed, zero failures/skips**, including 9 architecture tests. A frozen pre-refactor mapping manifest and independent source inventory match the full Spring application (54 READ definitions, all version 1). Spring tests exercise extension registration/duplicates and prove assembly/planning makes no DataSource connection and creates no schedule ledger. Wrong mapper types and null values fail individual reads while later members proceed. The default L2 event-response representation remains generic. Evidence: `.gradle/refactor-baseline/T11`; architecture debt remains **223**, zero new edges.

## T12 — shared daily semantics

Added immutable `DatasetSemantics` and `DailySemantics.V1`. Main daily metadata, source fields and readback columns share the ordered field/key definition. The batch daily adapter validates the existing resource projection against those fields, dates and semantic version before binding; other 40 source contracts retain their loading path. Main YEAR versus batch DAY, schema version 1 versus daily-v1, typed LocalDate versus ISO archive text and both collection policies remain distinct.

Acceptance: **168 tests across 13 classes passed, zero failures/skips**, including 85 new characterization/contract cases and 9 architecture tests. Independent goldens were captured from preserved pre-T12 compiled classes with class SHA provenance, covering real DailySource receipt/reopen, request identity, SourceCollector frozen bytes/hash, binary codec, null and signed zero. Only three already unordered Set arrays are sorted in metadata goldens; business fields and keys are not reordered. Drift in any shared field component is rejected. An initial test attempt incorrectly deserialized the existing read-only marketAggregate JSON property; the fixture helper was corrected without changing production JSON or goldens. Evidence: `.gradle/refactor-baseline/T12`; architecture debt remains **223**, zero new edges.

## T13 — registered source strategies

`SourceContract` retains its 14-component serialized record and public methods while delegating request, row, coverage and storage policy to an explicit immutable 41-source registry. Provider-specific rules are grouped by responsibility. `SourceCollector` contains no registered dataset-name literals and creates a fresh collection session for each invocation; ST history is confined to that session. Raw budgets/retention, key sorting, Frozen/probe bytes and recovery file methods retain their original ordering.

Acceptance: **2,097 tests across 14 classes passed, zero failures/skips**, including 9 architecture tests. The 1,918-case independent oracle first passed against the preserved old implementation, then passed against all replacement strategies: 41 complete collectors with exact Frozen bytes/hash/request projections, 41 metadata/storage projections and 1,836 normalization outcomes. Another 25 cases cover exact registration, immutable policies, fresh sessions, nested/concurrent ST collection, interruption, failure/retry and raw-evidence boundaries. Existing range, paging, ledger, parity and T12 compatibility tests also pass. Independent review and text comparisons confirm the retained raw/hash/recovery segments. Evidence: `.gradle/refactor-baseline/T13`; architecture debt remains **223**, zero new edges.


## T14 — ETF package pilot

Moved daily/adj/factor orchestration, storage and mapping into `data.etf.application`, `storage` and `mapper`. Three typed target ports isolate identity/range SQL and create a fresh writer for each plan or run. `SyncRunExecution` preserves ledger, writer/source factory, locks and runner construction order, cancellation and parent/recovery IDs. Existing Plan fields, admission differences, budget capping, request/evidence serialization and SQL precision remain unchanged. The architecture gate now also checks port dependencies and executor invocation.

Acceptance: **421 distinct tests passed across 52 classes, zero failures/skips** (418 in the complete gate plus 3 added identity-order cases; all 34 application contract cases rerun). Includes 21 storage-boundary cases, 6 shared-run cases, a full Spring assembly with 54 readers and 3 ETF owners, existing CLI/group/cache/mapper/evidence/schema suites and 11 architecture tests. Spring assembly creates no connection, sender or ledger. Independent review compared all 27 moved classes against their pre-move snapshots; 15 algorithm classes are identical after removing package/import changes. Architecture debt dropped **223 → 211 (12 removed, zero new edges)**. Evidence: `.gradle/refactor-baseline/T14` and `T14-extra`; `git diff --check` passed.


## T15 — family rollout

### Other ETF/fund — complete

Moved the remaining 25 ETF basic/portfolio/share classes into the ETF business package. Basic uses a minimal snapshot target and `VerifiedWriteSession`; dated targets extend this contract without introducing fictitious snapshot dates. Portfolio's generic date methods delegate to its existing announcement-date reads. Shared completeness caps now belong to the pure Dataset contracts. SQL, preflight ordering, observedAt, source receipts, codec bytes and restoration decisions are unchanged.

Acceptance: **466 distinct tests across 56 classes passed, zero failures/skips** (464 in the complete family gate and 2 codec contract cases). Includes 22 new storage cases, 20 application cases, 2 codec cases and full six-owner Spring wiring with 54 readers and no external I/O. Independent comparison confirms 12 moved algorithm classes unchanged beyond package/imports, all three identity methods identical and both range queries unchanged beyond their result type. Architecture debt dropped **211 → 199 (12 removed, zero new edges)**. The initial architecture check caught a migration-generated unused import inferred from an owner-name string; it was removed without changing the string or adding an exception. Evidence: `.gradle/refactor-baseline/T15-etf` and `T15-etf-codec`; `git diff --check` passed.

### Stock/calendar — in progress

Migrate the 89 explicitly inventoried stock/calendar classes; extract target SQL, per-run writer factories and publication table operations. Cross-family group/schedule assembly and shared date utilities remain common components and will be integrated after all family ports are available. Existing `questdb-` identities, creation order, parent cancellation and distinct publication/recovery state machines remain compatibility requirements.

## Remote synchronization — 2026-10-08

The user explicitly authorized fetching, merging and pushing the current work. All existing local changes, including partial T15 stock/calendar work and earlier retained data changes, were checkpointed as `a2141de`. Merged the three remote commits through `8e45ca5` into `main`. Repository-local long-path support was enabled for retained evidence paths; the 3,161 files temporarily materialized by the first failed checkout were preserved in a stash and verified against identical remote Git blobs before retrying the merge.

Conflict resolution keeps the remote formal-table admission, source completeness guards, larger portfolio evidence budget, and bounded stock readback queries while retaining the local target/session interfaces. Adj has a dedicated session for formal-date compatibility checks. ST publication binding accepts the same admitted targets as planning; Suspend publicly admitted targets and internally bound replacement stages use separate policies. Missing Detail wiring and moved test references were repaired without exposing production test hooks.

The nine added CLI commands are registered in the existing families (169 names total). The two newly registered READ datasets receive explicit generic representations; the original 54 registrations remain intact and unknown READ datasets still fail assembly. Native Market/Regime queries and publication SQL are behind storage objects, streaming callbacks and cancellation positions are preserved, and numeric calculations are pure domain policies. Canonical evidence uses the canonical mapper without modifying shared ordinary JSON configuration. Independent reviews compared SQL, physical decoding, hash inputs, publication recovery and exception-close ordering with the remote implementation.

Remote `LaunchService` referenced an absent `MainStrategyDailyWork`, with no executable job or authoritative five-stage completion protocol in the repository. The merge repair keeps unregistered-job rejection and records `IN_DOUBT` if such a job is registered without that protocol; it does not infer `VERIFIED` from a Batch exit code. This remote feature remains incomplete.

Stock/calendar rollout is still in progress: Suspend's full target/session extraction and the remaining T15 acceptance work are pending. This synchronization is not completion of T15 or T16–T18.

Validation: production and test compilation pass. The selected merged suite covers 1,060 test cases across 127 classes; after four corrected catalog tests were rerun, the latest outcomes are **1,059 passed, zero failed, one skipped**. The skipped test requires an explicitly supplied retained recovery ledger. The full suite's four initial catalog failures were fixed by using temporary output files instead of colliding with retained audit files and updating the old 54/42 catalog counts to 56/44. The 11 architecture cases pass; only 38 confirmed stale exact exceptions were removed, so debt is **199 → 161 with zero new edges**. Non-artifact staged `git diff --check` passes. Four whitespace notices in imported retained evidence were preserved for byte/hash integrity. The two existing retained-fixture startup cases and external live/recovery checks were outside this selected merge gate. Evidence: `.gradle/refactor-baseline/git-sync-20261008-final`, `git-sync-20261008-catalog-final`, and `git-sync-20261008-final-summary.json`.
