# ETF_ONLY_TOP2 Java data ownership

The main strategy's formal data preparation runs in Java. Python retains strategy evaluation and research reference algorithms; its default daily adapters request the Java publications and read their frozen artifacts. The production path consumes Java's final scores, static-score view and already aligned weight/state rows without recomputing overlays or aligning training tables in Python. It checks the four local training file hashes again before and after evaluation.

## Producers and authority

| Data | Java owner | Evidence |
| --- | --- | --- |
| Five L2 Parquet families | `NativeL2ParquetReader` and native source adapters | Frozen manifests, scalar schema, complete business keys, source fingerprints |
| Formal `l2_daily_features` | `FormalL2DailyFeaturesIngestion` | All 110 values, source and target identity, date lock, WAL readback; fills confirmed missing keys only |
| Main stock/ETF scores | `QuestDbStrategyInputs`, `L2StockScoreProducer`, `EtfOnlyTop2ScoreProducer` | Bounded complete calendar grids, source generations/schema/WAL, announcement-date PIT holdings, four accepted training SHA256 identities |
| `score_panel` and `score_coverage` | `ScoreArtifactPublisher` | Immutable native Parquet and provenance, paired SQLite alias transaction, footer/schema/date/hash checks |
| `market.etf.rotation.input_package` | `NativeMarketInputPackagePublisher` | Exactly `calendar`, `etf_market`, `market_context`; same score run and recipe, complete candidate keys, frozen source proof |
| Official weekly authority | `OfficialPlanStore` | Canonical v1 JSON, compatible SQLite v3, semantic/source hashes, frozen score provenance, conflict checks and nonregressing alias |

The market-context role reads `regime_features_monitor_daily`. It records the current revision and `updated_at` availability, with `point_in_time=declared` and `revision_visibility=latest_only`; freezing current values does not prove historical observation time. The sentiment score input separately reads `market_sentiment_daily`.

Weekly authority binds its original scoring run. Advancing daily aliases does not require a weekly plan to be rewritten or rebound. Import resolves the exact signal-date scoring frontier; repeating an existing weekly plan preserves its original provenance.

Historical requests resolve an exact already published daily frontier rather than substituting a later alias. Every score run requires its own complete market package; a committed score pair alone cannot become a new official authority. Package run receipts make retries idempotent and historical reads independent of current aliases. Current daily inputs and first plan imports check all four accepted recipe/training hashes. Historical weekly authorities retain their original training identity.

Weekly freshness uses the frozen SSE calendar: the signal and execution must match the latest adjacent open sessions crossing an ISO week as of the acceptance date. This includes holiday gaps and ISO year transitions. A stale plan or a calendar without the next open session fails closed. The daily completion certificates bind the exact canonical plan identity and the same run's official-plan stage; they also recheck the complete current score source clocks, schema and the direct raw-source backtest MV.

## Entrypoints

```powershell
./gradlew mainStrategyData --args="status"
./gradlew mainStrategyData --args="produce-scores --target 2026-10-09"
./gradlew mainStrategyData --args="import-plan --source F:/repo/engine/back-monitor/artifacts/official-canonical.json --source-sha256 <sha256> --score-run <verified-java-run>"
./gradlew mainStrategyData --args="inspect-plans"
```

`produce-scores` reads sources and publishes the two scoring roles plus the three-role market product. It does not fabricate a plan or train replacement weights. The Java HTTP composition runs in the independent main-strategy process, launched by `mainStrategyRun`; endpoints under `/api/v1/main-strategy/data` are `GET /`, `GET /scores`, `POST /scores`, `GET /input-package`, `GET /plans`, and `POST /plans/import`. Existing API token authentication applies. The import request carries `sourcePath`, `sourceSha256`, and optional `scoreRunId`; the source must remain inside the configured artifact root.

The main persistent batch definition is version 2, with stages `Sources`, `MarketSentimentDaily`, `RegimeFeaturesMonitorDaily`, `BacktestDaily`, `L2DailyFeatures`, `ScoreInputs`, `OfficialPlanData`, `MainStrategyAcceptance`. Older version 1 run receipts are not accepted as version 2 completion evidence.

The existing coordinator requires an explicit revision for changed configuration or completed evidence; its public revision/supersession operation is not provided by this data migration. Existing completed runs are never silently overwritten.

## Configuration

Java uses `app.sync.batch.research-root`, `promoted-recipe`, `promoted-recipe-pins`, `promoted-artifact-root`, `official-plan-db`, `score-start-date`, and `score-source-row-budget`. Defaults target the existing `F:/repo/engine/back-monitor/artifacts` tree and signal start `2025-07-01`. The CLI accepts explicit path/start/budget options; QuestDB credentials come from `APP_QUESTDB_HOST`, `APP_QUESTDB_PG_PORT`, `APP_QUESTDB_USERNAME`, `APP_QUESTDB_PASSWORD`.

The accepted pin document contains lowercase SHA256 values for `recipe`, `factor_weights`, `factor_states`, `l2_factor_weights`. Pin values must be supplied from accepted training artifacts. Missing files, future training observations, mismatching hashes, calendar gaps or unsettled WAL prevent publication.

CLI heap defaults to 16 GiB and is configurable with `-PmainStrategyDataHeap=...`. SQL has per-source budgets and a cumulative budget checked while consuming rows. L2 score reads project only scoring fields present in the pinned schema; ingestion still checks all 110 formal fields.

Python uses `JAVA_DATA_API_URL`, `JAVA_DATA_API_TOKEN` and optional timeouts. Formal plan writes route through Java when the database is the default main-strategy database or the explicit `JAVA_MAIN_STRATEGY_PLAN_DB`. Other explicit research/test plan roots preserve their independent research writer. Java-owned score catalogs are opened read-only in the default Python adapter; failed Java calls have no local calculation or SQLite writer fallback.

## Validation and current availability

The Python oracle covers 72 stock scoring rows, 24 ETF panel rows and six coverage dates, including PIT disclosures, ST/suspension and overlay factors. Real PyArrow Snappy fixtures test native L2 and frozen weight decoding. Transaction tests cover score receipt collision, source changes before commit, plan conflicts, historical weekly bindings, exact historical dates, changed accepted training files, stale weekly plans and missing artifacts. A separate synthetic interoperability fixture exercises the actual Spring Boot Jackson 3 response serialization and real Python readers against Java Parquet/catalog/SQLite outputs; it is not a production recipe or market sample.

Evidence is under `artifacts/main-strategy-java/20261011/`. The read-only formal status check still reports missing accepted recipe/pins and official plan authority, and no formal Java score pair. The fresh source check finds core daily, ETF, L2, backtest MV, sentiment and regime frontiers at 2026-09-30 versus the calendar's latest open date 2026-10-09. Sentiment history starts at 2026-05-06, later than the configured 2025-07-01 strategy start. The initial broad aggregate requests timed out on three large tables; the separately recorded bounded 2026-09-28..2026-10-11 queries verified those frontiers. Code and fixture validation do not certify production data readiness.

The current backtest input is `mv_backtest_daily`, a native `REFRESH IMMEDIATE` MV directly over four raw sources. The `stk_limit` source commits last so the joined prices, suspension and ST data are available before a refresh. `BacktestDaily` now only verifies this MV; there is no Java backtest result-table publication or refresh owner. QuestDB listens only to its selected base: side-table-only changes require a complete corresponding raw-source batch and are not advertised as four-table automatic incremental maintenance.
