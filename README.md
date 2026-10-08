# QuestDBWithData

Java data-ingestion project. The first vertical slice fetches Tushare Pro's current listed-stock directory with Spring WebClient, maps it to a Java model, and sends it to QuestDB through the native Java QWP client.

## Requirements

- JDK 24
- Tushare Pro token with access to `stock_basic`; configure it in `src/main/resources/application.yml`
- QuestDB credentials in `src/main/resources/application.yml` for QuestDB commands

## Run

Set `app.tushare.token` and QuestDB credentials in `src/main/resources/application.yml`, then run:

```bash
./gradlew run --args='sync-stock-basic-questdb'
```

This creates/uses the isolated `java_tushare_stock_basic_qwp_test` table, writes one UTC daily snapshot, and reads back the row count for that snapshot. The table uses `(snapshot_ts, ts_code)` as its deduplication key, so rows with the same daily key are upserted. Row-count polling is a limited visibility check: unchanged counts cannot confirm that a repeated sync's new field values have been applied. The command does not touch production tables. The previous `sync-stock-basic-jdbc` command remains as an alias.

The QuestDB table and latest view are managed by Flyway SQL migrations in `src/main/resources/db/migration/questdb/`. Run `./gradlew run --args='migrate-questdb-schema'` explicitly, or let database sync/latest-query commands migrate before use. Existing databases require schema reconciliation and an explicit baseline; see the Flyway guide.

QuestDB ingestion uses the native QWP WebSocket client on the YAML-configured QWP port (normally 9000). PostgreSQL JDBC/PGWire (normally 8812) creates the table schema and polls for the submitted snapshot to become queryable. QWP writes are asynchronous, so the command waits for visibility with a 10-second deadline and fails if the row count does not catch up.

Configuration is Spring-managed through `@ConfigurationProperties` and `application.yml`; no `.env` file is read. The YAML contains placeholder credentials that you should replace with the values for your local services.

See [docs/questdb-usage.md](docs/questdb-usage.md) for QuestDB conventions, [docs/model-schema-conventions.md](docs/model-schema-conventions.md) for time/key semantics and Pydantic-to-Java conversion, [docs/code-structure.md](docs/code-structure.md) for package responsibilities, and [docs/view.md](docs/view.md) for views, WAL recovery, and data-engineering notes.

For the current migration status, including implemented framework capabilities and data/business functions not yet migrated, see [docs/java-migration-status.md](docs/java-migration-status.md). The generic sync/read/write and scheduling infrastructure is ahead of the original stock_basic vertical slice, but the 184 audited business data objects have not yet passed their individual migration acceptance.

## Spring WebFlux API

`app.web.enabled: true` in `application.yml` makes `com.zoutrankil.data.QuestDataApplication` start the WebFlux API without arguments, including from IntelliJ. Start it from Gradle with:

```bash
APP_API_TOKEN='replace-with-at-least-24-characters' ./gradlew webRun
```

It listens on `APP_PORT` (default `8080`). `GET /api/v1/health` is public for probes. When `APP_API_TOKEN` is set, all other `/api/v1/**` routes require `Authorization: Bearer <token>`; leaving it empty is convenient for local development only.

CLI commands still run through the same main class when a command is supplied, for example `./gradlew run --args='show-sync-job-definitions'`.

### Persistent main-strategy daily Batch

The main Spring Web application starts `main_strategy_daily` automatically after
`ApplicationReadyEvent`. This also applies to an IntelliJ launch with no arguments.
The default `app.sync.batch.enabled` and `startup-catchup` are `true`.
Its ordered stages are existing Java source owners, market sentiment calculation,
regime calculation, backtest materialization, and physical data acceptance.
Source requests reuse the existing WebFlux client and shared request budget.

The calendar selects the latest completed SSE session after the 20:30 Asia/Shanghai
source cutoff. Quartz runs at 20:35 and retries eligible unfinished work every
15 minutes from 21:00 through 23:45, with a finite retry budget. SQLite stores
business instances, stage certificates, Spring Batch executions and Quartz state.
Completed stages are read back before reuse; uncertain writes require the existing
owner's reconciliation operation. A process exit code alone cannot complete a batch.

`v_backtest_daily` is the native Materialized View target, backed by the existing
`backtest_daily` enriched table. The first conversion preserves the complete source
history and all 13 business fields. No ordinary intermediate or alias VIEW is
installed. The Java materialization owner refreshes the MV after verified base
publication; deleting old DAY partitions requires a FULL refresh.
Market sentiment and regime remain calculation tables managed by their existing
Java MATERIALIZE jobs, both registered as daily eligible.

Set the QuestDB address and the persistent source ledger before starting the
application. The current local acceptance uses these IntelliJ VM options:

```text
-Dapp.questdb.host=127.0.0.1
-Dapp.sync.ledger-path=var/main-strategy-java-refresh/20261007/sync-ledger.sqlite3
```

Keep this source ledger when restarting: it contains the publication authority and
retained recovery evidence. The scheduler's separate metadata defaults to
`var/main-strategy-batch.sqlite`. Web mode binds the main-strategy owners to their
formal table names; finite CLI commands retain their isolated defaults.

Control and inspect the same running pipeline through:

- `GET /api/v1/batch/main-strategy`
- `GET /api/v1/batch/main-strategy/instances/{instanceId}`
- `POST /api/v1/batch/main-strategy/catch-up`
- `POST /api/v1/batch/main-strategy/instances/{instanceId}/reconcile-publication`
- `POST /api/v1/batch/main-strategy/pause`
- `POST /api/v1/batch/main-strategy/resume`

These routes use the existing API authentication. Persisted pause survives a
restart. Set `APP_SYNC_BATCH_ENABLED=false` to disable this runtime, or
`APP_SYNC_BATCH_STARTUP_CATCHUP=false` to keep scheduling without a startup catchup.
An uncertain native publication requires explicit reconciliation with the body
`{"stage":"MarketSentimentDaily","runId":"original-run-id","writerStopped":true}`.
The runtime requires an idle writer, the original frozen run, unchanged source
proofs and physical publication verification. It preserves the uncertain receipt
and appends a verified recovery link; use `catch-up` afterward for the remaining
stages. Ordinary retries never replay an uncertain publication.
The accepted Level2 temporary JSONL directory need not be kept for ordinary daily
jobs. Retained ingestion and archive receipts, an unchanged WAL frontier and a
complete current typed quality fingerprint can certify already imported dates.
Fresh source comparisons or re-imports require regenerating the deleted output.
Future Level2 dates require their own accepted cleaning/import evidence; this
batch waits when that dependency is missing. ETF disclosures use the actual latest
announcement before the target date, with freshness and completeness limitations
reported separately. Incomplete whole-market financing data stays optional and
does not become a fabricated complete source.

Useful smoke-test routes are `GET /api/v1/info`, `GET /api/v1/datasets`, `GET /api/v1/jobs`, `GET /api/v1/stock-basic/latest`, and `POST /api/v1/stock-basic/sync`. The last two access QuestDB or Tushare and run on a bounded-elastic scheduler so blocking database/client calls do not occupy the WebFlux event loop.

Open [api.http](api.http) in IntelliJ and run the requests to check service health, QuestDB connectivity, dataset definitions, and a bounded sample of actual QuestDB rows. The sample endpoint accepts `limit=1..100` and reads only registered datasets with the READ capability. The stock synchronization request is commented out in the file because it writes data.

Flyway configuration, version pins and existing-database adoption are documented in [docs/flyway-schema-management.md](docs/flyway-schema-management.md). Follow [docs/model-development-workflow.md](docs/model-development-workflow.md) to define a new model and its read/write mappings. Runtime plugin tests pass; migrations have not yet been exercised against a live QuestDB instance.

Spring Batch job metadata uses a local SQLite database; no PostgreSQL server is required for Batch. The PostgreSQL JDBC driver remains in the main application only for QuestDB PGWire schema/query operations, and is excluded from the standalone Batch distribution.

The current configured database schema was exported read-only from QuestDB 10.0.1. Browse the [schema snapshot and table/view/materialized-view domain catalog](schema-export/questdb-qdb-2026-09-28/README.md); Java schema projections are generated by `tools/generate_domain_models.py`.

The current `stock_basic` request uses `exchange=''` and `list_status='L'`; this is the current listed-stock universe, not a point-in-time historical universe.
