# QuestDBWithData

Java data-ingestion project. The first vertical slice fetches Tushare Pro's current listed-stock directory and writes a local CSV snapshot. QuestDB persistence will be added after this source sync is verified.

## Requirements

- JDK 24
- Tushare Pro token with access to `stock_basic`

## Run

Put `TUSHARE_TOKEN` in `.env` (copy `.env.example`) or export it in the shell, then run:

```bash
./gradlew run --args='sync-stock-basic'
```

To choose explicit files:

```bash
./gradlew run --args='sync-stock-basic --env-file ../back-monitor/.env --output var/stock_basic.csv'
```

The command calls Tushare over HTTPS, checks the API response code, and atomically writes the returned rows to `var/stock_basic.csv`. It does not write to QuestDB yet. Tokens are read from the environment or the selected env file and are never printed.

The current `stock_basic` request uses `exchange=''` and `list_status='L'`; this is the current listed-stock universe, not a point-in-time historical universe.
