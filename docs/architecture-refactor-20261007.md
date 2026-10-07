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
| T15 | Families in order: other ETF/fund; stock/calendar; index; flow/margin; macro; derived; L2 ingestion | In progress: derived |
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

### Stock/calendar — complete

Moved the 89 explicitly inventoried stock/calendar classes into business application, domain, port, storage and mapper packages. Nine target ports isolate identity/range SQL and create a fresh writer for each plan or run. Detail, ST and Suspend keep their separate publication/recovery state machines in application; table identity, snapshots, WAL waits, rename operations and private stage writers are exposed through storage ports. Shared stateless codecs and metadata remain reusable; JDBC query settings and writer state belong to individual sessions. Spring assembly binds all nine owners and 56 READ registrations without connecting to a DataSource, creating a sender or opening a ledger.

Compatibility review covers all 89 classes: 41 are token-identical after package/import/FQCN normalization, and all 48 remaining differences have individual explanations. Eight writer CODEC bodies match the original snapshot. The review separately identifies the actual remote changes in 14 classes, local merge repairs and the ST recovery fix below. Admission policies, stable logical and changing physical identities, request/source evidence bytes, different WAL waits and cancellation positions retain their dataset-specific behavior. Suspend's externally admitted target tables and internally bound replacement stages use separate constraints.

Fault injection exposed an existing ST empty-publication recovery defect: a hard stop during rename leaves a zero-row slice in FETCHED, which recovery previously rejected despite complete durable evidence. Recovery now accepts only a FETCHED slice whose `returnedRows` is an exact JSON integer zero, after checking immutable FETCHED events, source/raw receipts, stage hashes, complete dates and fingerprints. Full-table/window/outside readback still precedes final state transitions and lease release. Nonempty, malformed or corrupted evidence fails before further publication. Three hard-stop cases failed against the old implementation and now pass; fractional, string and boolean row counts are rejected.

Acceptance: **723 tests across 105 classes passed, zero failures/skips**, including **147 new cases in 11 contract classes**, the 11 architecture cases, stock/calendar storage and owners, publication crash/recovery, ETF regression, CLI, mapper/read binding and shared group assembly. Architecture debt dropped **161 → 149 (12 removed, zero new edges)**; only confirmed stale Suspend application/storage exceptions were removed. Evidence: `.gradle/refactor-baseline/T15-stock-acceptance`; original failure and focused recovery runs: `T15-stock-st-repro` and `T15-stock-st-fixed`; per-class static review: `T15-stock-final/compatibility-audit`. The two retained-fixture startup cases and live/recovery checks remain outside this gate. `git diff --check` passed.

Cross-family group/schedule assembly and shared date utilities remain common components and will be integrated after all family ports are available. This family acceptance does not complete T15 or T16–T18.

### Index — complete

The current migration inventory covers 105 production classes: 69 application, 27 storage and 9 mapper classes across IndexCatalog, IndexMembership, ThsIndex, ThsMember, IndexDailyMarket, IndexDailyBasic, IndexWeight, IndexMonthly and DcIndex. Mechanical migration also relocates 79 existing family test classes; package-private helpers remain private, with test-only accessors for shared compatibility tests. Original source bytes, move maps and architecture exception names are retained under `.gradle/refactor-baseline/T15-index`.

Nine typed targets now bind through `IndexConfiguration`. D019/D020/D021 have per-run write sessions; D021 resolves bounded StockDetail name references through a dedicated read port. The four static families use typed table/staging ports and pure immutable publication evidence. Monthly and DcIndex retain separate window replacement, physical generation and recovery protocols; Monthly READY/source recovery stays in application, and DcIndex reads calendar data through a read port. The original D019/D020 group-child prior/parent behavior remains characterized, pending any separately reviewed behavioral correction. Shared group construction remains part of the later cross-family assembly work.

The new contracts exposed an existing THS member empty-completion defect: both normal completion and stopped-writer recovery omitted the ledger's required empty-source fields and therefore could not reach VERIFIED_EMPTY. Only the zero-source ledger payload now adds `sourceComplete`, exact integer zero `returnedRows`/`submittedRows`, and the authenticated `responseEvidence`. The nonempty payload and original completion receipt expressions remain unchanged. An empty response still cannot delete stored board members. Two real SQLite recovery cases cover RUNNING and IN_DOUBT, source receipt reopening, all three ledger entries, unaffected-board readback, no staging/rename, and lease release after completion.

Acceptance: **1,012 tests across 150 classes passed, zero failures/errors/skips**, including **158 new cases across 17 contract classes and one Spring wiring class**, all **723 stock/calendar/ETF/CLI regression cases**, shared evidence/publication contracts, EquityStyle integration and **11 architecture cases**. Architecture debt dropped **149 → 85 (64 removed, zero new edges)**; only confirmed stale index exceptions were removed. Independent review covered all 105 captured classes: 30 mechanically equal after package/import normalization, 73 explained extractions, and two classes with the documented empty-completion fix. **103 exact static assertions** pass, including all five complete CODEC bodies, 32 moved records/Scope/ranges, pure projections, physical implementation segments and budget values. Final hashes cover 165 current production files with no drift. Evidence: `.gradle/refactor-baseline/T15-index-acceptance`; static review: `T15-index/compatibility-audit`; original failed contracts: `T15-index/contracts`; focused fixture repairs: `T15-index/focused-repair`. Production and test compilation and `git diff --check` passed.

The final gate uses 150 exact Gradle selectors loaded from an ignored init script to avoid the Windows batch command length limit; the standard offline exclusion and enabling-environment cleanup policy is unchanged. Missing D005 retained-fixture cases, the two retained-fixture CLI startup classes, and live/recovery checks remain outside this gate. Shared group construction and the characterized D019/D020 group-child behavior remain separate follow-up work. This family acceptance does not complete T15 or T16–T18.

### Flow/margin — complete

Refactored eight datasets from 83 captured implementation classes into `data.flow` and `data.margin`, with application, port, storage, mapper and pure domain responsibilities. Eight typed targets isolate JDBC/QuestDB identity, range, table and writer factories; configuration retains the original keys/defaults and lazy clients. Sources, staging proof, publication decisions and recovery stay in application, while physical SQL/WAL/rename/drop remain storage-owned. Each run creates its own writer and evidence context. Pure rows, complete codecs, budgets and immutable records can be shared. Original public Row/Dataset/Key and provider DTO names remain stable.

Preserved family differences: formal Moneyflow is bounded BACKFILL only; Detail retains its 14-day formal repair and physical-before evidence; DC keeps version-2 paging and legacy-receipt rejection; THS uses complete real calendar coverage; Secs retains its calendar fingerprint and omission guard; Zrz remains retired/disabled. The paged calendar bridge and HSGT streaming calendar adapter preserve their original queries, bounds and MICROSECOND decoding. The four upsert owners still do not propagate parent-only cancellation; independent tests characterize that original behavior and verify explicit child cancellation. Cross-family cancellation propagation remains follow-up assembly work.

Two reproduced legacy defects were repaired. All/Zrz stage discard compared a table name with a hashed logical target identity and refused valid owned stages; the binding now compares the frozen run identity while retaining every other ownership/physical/intent check. HSGT hard interruption left genuine empty source slices in FETCHED, while recovery required them already complete; malformed/nonempty FETCHED could also be refused after physical renames. Recovery now authenticates frozen authority, immutable events, owned raw/stage receipts and the full source window before any remaining rename. Only a strict integral zero-row FETCHED is additionally admitted; physical publication/readback must finish before completing slices/parents and releasing the lease. Normal nonempty codecs, receipts, SQL and publication order are retained.

Acceptance: **1,216 tests across 166 classes passed, zero failures/errors/skips**, including **204 new cases across 16 classes**, all **1,012 prior index/stock/calendar/ETF/CLI regression invocations**, and **11 architecture cases**. The focused gate also passed **223 cases**. Architecture debt dropped **85 → 34 (51 exact edges removed, zero new edges)**. Independent review classifies the 83 original implementations as 12 mechanical, 66 explained extractions and five classes with the documented fixes; no unexplained differences remain. **330 source comparisons and 98 baseline/recovery-order checks pass**, including eight complete codecs and 21 records. The finalized family manifest covers 137 production files; all 1,199 production Java files were frozen before the final gate and verified unchanged afterward. The shared calendar algorithm and legacy bridge also pass three static comparisons.

Evidence: `.gradle/refactor-baseline/T15-flow-margin-acceptance` (166 precise selectors, unchanged standard offline policy, exact prior invocation retention and source drift checks); source audit: `T15-flow-margin/compatibility-audit`; original discard and HSGT failing reproductions: `discard-repro` and `hsgt-fetched-repro`; fixture/request-comparison failures and repairs: `contracts-final` and `contracts-repaired`. Compilation and `git diff --check` pass. Missing retained D005/CLI fixtures and external live/recovery checks remain outside this family gate. D032 has no executable Java owner and was not invented during refactoring. This acceptance does not complete T15 or T16–T18.

### Raw macro — existing implementation scope verified

The D060–D078 inventory contains 19 generated schema projections, ten explicit batch source contracts and nine projections without a batch source contract. There are no independent raw-macro Owner/Source/Mapper/Repository implementations in the main application to migrate. Existing batch strategies retain their explicit registry and shared period policies; their configured DAY partitions and the US Treasury YEAR exception are not changed to match task-card prose. `sf_month` being an input to another owner does not establish a Java ingestion implementation. The dedicated Chinabond provider and current source contracts are included in offline validation.

Acceptance of the existing scope: **1,965 tests across six classes passed, zero failures/errors/skips**, including the shared source-strategy compatibility oracle, explicit source registration/isolation, ten exact macro methods, the Chinabond parser fixture and 11 architecture cases. The 132 captured files have matching current hashes, with no source changes. The two known Windows executable runtime cases were not selected; they remain T18 work. D104/D105 MacroCore is recorded as derived, with ten implementation candidates and seven registered debts; D106/D107 are schema-only. No new ingestion owner or source-readiness claim is made. Evidence: `.gradle/refactor-baseline/T15-macro` and its `offline-gate`/`offline-summary.json`.

### Derived — in progress

The first derived tranche migrates 49 production and 54 test/support files, then moves the two MV physical implementations after introducing a private-attestation bridge. Monthly owners now use typed source reads and fresh writer factories; native MV owners use one session for admission, PID attestation, refresh and readback; the delegated ETF owner keeps durable claims and no-resend recovery in application code while process control and physical JDBC readback use separate ports. Public dataset/row/key contracts stay in place. Three private shared read guards and nine native daily implementation candidates remain separately scheduled, so this tranche does not complete the derived rollout or T15.

Compatibility evidence covers the monthly readers' independent D103 query budgets and shared D104 absolute deadline, all monthly source records and canonical row projections, both complete MV physical implementations and calendar before/read/compare/after ordering, and the D101 durable claim and three stopped-process proofs. Monthly static checks pass 78/78; MV checks pass 65/65; D101 method review accounts for 129 methods, including two explained API/factory substitutions, and 14 read repositories/mappers compare equal after package/import normalization. Root integration passes 21/21 comparisons. The pure storage conversion was extracted unchanged to `DatasetStorageValues`, with the original reader entry point retained as a delegate. Sixteen Macro Live test source paths were updated to moved files without changing historical admission hashes. These static checks do not claim live data readiness.

Production and test compilation pass. Architecture scanning confirms exactly 21 removed derived edges, taking registered debt from 34 to 13 with zero new violations. The initial 84-selector offline gate ran 856 cases; its two failures were stale exception registration and a new reader fixture expecting one timeout configuration call where the original implementation performs two. Both were corrected; the 17-case focused rerun passes. Final acceptance: **1,745 tests across 212 classes passed, zero failures/errors/skips**, including **57 new cases in 13 contract/wiring classes** and every one of the preceding flow/margin gate's **1,216 invocations** after package-name normalization. All **1,242 production Java file hashes** remain unchanged throughout the final gate. The 213 exact Gradle selectors use the unchanged standard offline exclusions and environment cleanup policy; retained-state/live acceptance remains separately gated. The native daily derived tranche, L2 ingestion and shared group assembly still remain before T15 can close. Evidence: `.gradle/refactor-baseline/T15-derived` and `.gradle/T15-derived`.

## Remote synchronization — 2026-10-08

The user explicitly authorized fetching, merging and pushing the current work. All existing local changes, including partial T15 stock/calendar work and earlier retained data changes, were checkpointed as `a2141de`. Merged the three remote commits through `8e45ca5` into `main`. Repository-local long-path support was enabled for retained evidence paths; the 3,161 files temporarily materialized by the first failed checkout were preserved in a stash and verified against identical remote Git blobs before retrying the merge.

Conflict resolution keeps the remote formal-table admission, source completeness guards, larger portfolio evidence budget, and bounded stock readback queries while retaining the local target/session interfaces. Adj has a dedicated session for formal-date compatibility checks. ST publication binding accepts the same admitted targets as planning; Suspend publicly admitted targets and internally bound replacement stages use separate policies. Missing Detail wiring and moved test references were repaired without exposing production test hooks.

The nine added CLI commands are registered in the existing families (169 names total). The two newly registered READ datasets receive explicit generic representations; the original 54 registrations remain intact and unknown READ datasets still fail assembly. Native Market/Regime queries and publication SQL are behind storage objects, streaming callbacks and cancellation positions are preserved, and numeric calculations are pure domain policies. Canonical evidence uses the canonical mapper without modifying shared ordinary JSON configuration. Independent reviews compared SQL, physical decoding, hash inputs, publication recovery and exception-close ordering with the remote implementation.

Remote `LaunchService` referenced an absent `MainStrategyDailyWork`, with no executable job or authoritative five-stage completion protocol in the repository. The merge repair keeps unregistered-job rejection and records `IN_DOUBT` if such a job is registered without that protocol; it does not infer `VERIFIED` from a Batch exit code. This remote feature remains incomplete.

At the synchronization checkpoint, stock/calendar rollout was still in progress. Its subsequent extraction and acceptance are recorded above. This synchronization is not completion of T15 or T16–T18.

Validation: production and test compilation pass. The selected merged suite covers 1,060 test cases across 127 classes; after four corrected catalog tests were rerun, the latest outcomes are **1,059 passed, zero failed, one skipped**. The skipped test requires an explicitly supplied retained recovery ledger. The full suite's four initial catalog failures were fixed by using temporary output files instead of colliding with retained audit files and updating the old 54/42 catalog counts to 56/44. The 11 architecture cases pass; only 38 confirmed stale exact exceptions were removed, so debt is **199 → 161 with zero new edges**. Non-artifact staged `git diff --check` passes. Four whitespace notices in imported retained evidence were preserved for byte/hash integrity. The two existing retained-fixture startup cases and external live/recovery checks were outside this selected merge gate. Evidence: `.gradle/refactor-baseline/git-sync-20261008-final`, `git-sync-20261008-catalog-final`, and `git-sync-20261008-final-summary.json`.

Delivery completed as `b86c39ef7588f6771921cd530546d8ebca4638e1`. Because the local Git credential helper could not authenticate, the authenticated GitHub connector uploaded the validated Git tree and advanced `main` without force, conditional on the fetched remote head. The resulting tree exactly matches the locally validated merge tree `514faefe6c8816251ad7f8dd7b742b5ea2021aa0`; its parent is `8e45ca5`. Fetch verified the remote commit and tree before the clean local branch was advanced to it. The original local checkpoint/merge history remains under `backup/pre-github-sync-20261008`, and the temporary long-path stash remains preserved. Local and remote `main` matched immediately after delivery. Later refactor work remains local. Delivery evidence: `.gradle/refactor-baseline/git-sync-20261008-delivery.json`.


## Subsequent synchronization checkpoint, 2026-10-08

The requested synchronization includes subsequent stock/calendar extraction, completed INDEX and flow/margin rollout, raw-macro existing-scope acceptance, and the first completed derived tranche. Fresh fetches still show remote `main` at `b86c39e`, with no additional remote commits to merge. Production/test compilation, the 1,745-case cumulative offline gate and staged `git diff --check` pass. No live database or retained-state acceptance was enabled. Exact-tree comparison and conditional non-forced remote ref advancement are recorded in `.gradle/refactor-baseline/git-sync-20261008-second`; the delivery receipt records the final published commit. T15 remains in progress and T16–T18 are still pending.
