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

Useful smoke-test routes are `GET /api/v1/info`, `GET /api/v1/datasets`, `GET /api/v1/jobs`, `GET /api/v1/stock-basic/latest`, and `POST /api/v1/stock-basic/sync`. The last two access QuestDB or Tushare and run on a bounded-elastic scheduler so blocking database/client calls do not occupy the WebFlux event loop.

Open [api.http](api.http) in IntelliJ and run the requests to check service health, QuestDB connectivity, dataset definitions, and a bounded sample of actual QuestDB rows. The sample endpoint accepts `limit=1..100` and reads only registered datasets with the READ capability. The stock synchronization request is commented out in the file because it writes data.

Flyway configuration, version pins and existing-database adoption are documented in [docs/flyway-schema-management.md](docs/flyway-schema-management.md). Follow [docs/model-development-workflow.md](docs/model-development-workflow.md) to define a new model and its read/write mappings. Runtime plugin tests pass; migrations have not yet been exercised against a live QuestDB instance.

Spring Batch job metadata uses a local SQLite database; no PostgreSQL server is required for Batch. The PostgreSQL JDBC driver remains in the main application only for QuestDB PGWire schema/query operations, and is excluded from the standalone Batch distribution.

The current configured database schema was exported read-only from QuestDB 10.0.1. Browse the [schema snapshot and table/view/materialized-view domain catalog](schema-export/questdb-qdb-2026-09-28/README.md); Java schema projections are generated by `tools/generate_domain_models.py`.

The current `stock_basic` request uses `exchange=''` and `list_status='L'`; this is the current listed-stock universe, not a point-in-time historical universe.
