# QuestDBWithData

Java data-ingestion project. The first vertical slice fetches Tushare Pro's current listed-stock directory with Spring WebClient, maps it to a Java model, writes a local CSV snapshot, or sends it to QuestDB through the native Java QWP client.

## Requirements

- JDK 24
- Tushare Pro token with access to `stock_basic`; configure it in `src/main/resources/application.yml`
- QuestDB credentials in `src/main/resources/application.yml` for QuestDB commands

## Run

Set `app.tushare.token` in `src/main/resources/application.yml`, then run:

```bash
./gradlew run --args='sync-stock-basic'
```

To choose explicit files:

```bash
./gradlew run --args='sync-stock-basic --output var/stock_basic.csv'
```

The command calls Tushare over HTTPS, checks the API response code, and atomically writes the returned rows to `var/stock_basic.csv`.

To write and verify the result in QuestDB, configure QuestDB in `src/main/resources/application.yml` and run:

```bash
./gradlew run --args='sync-stock-basic-questdb'
```

This creates/uses the isolated `java_tushare_stock_basic_qwp_test` table, writes one UTC daily snapshot, and reads back the row count for that snapshot. The table uses `(snapshot_ts, ts_code)` as its deduplication key, so retrying the same day's sync is idempotent. The command does not touch production tables. The previous `sync-stock-basic-jdbc` command remains as an alias.

The QuestDB table and its `java_tushare_stock_basic_latest_qwp_test` view are defined as version-controlled DDL in `QuestDbSchemaInitializer` and created automatically before the first write. You can initialize them directly with `./gradlew run --args='create-questdb-schema'`, then query the view with `./gradlew run --args='show-stock-basic-latest'`.

QuestDB ingestion uses the native QWP WebSocket client on the YAML-configured QWP port (normally 9000). PostgreSQL JDBC/PGWire (normally 8812) creates the table schema and polls for the submitted snapshot to become queryable. QWP writes are asynchronous, so the command waits for visibility with a 10-second deadline and fails if the row count does not catch up.

Configuration is Spring-managed through `@ConfigurationProperties` and `application.yml`; no `.env` file is read. The YAML contains placeholder credentials that you should replace with the values for your local services.

See [docs/questdb-usage.md](docs/questdb-usage.md) for QuestDB conventions, [docs/model-schema-conventions.md](docs/model-schema-conventions.md) for time/key semantics and Pydantic-to-Java conversion, [docs/code-structure.md](docs/code-structure.md) for package responsibilities, and [docs/view.md](docs/view.md) for views, WAL recovery, and data-engineering notes.

Flyway integration for versioned table/view migrations is documented in [docs/flyway-schema-management.md](docs/flyway-schema-management.md). It is a proposed integration; the application currently still uses `QuestDbSchemaInitializer`.

The current `stock_basic` request uses `exchange=''` and `list_status='L'`; this is the current listed-stock universe, not a point-in-time historical universe.
