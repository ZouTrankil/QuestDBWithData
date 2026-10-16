# Native Java L2 Parquet ingestion

The five `data-app` L2 Parquet sources read local files in-process using Apache Parquet 1.17.1. Their Spring configuration requires only `dataset-root`; `python-executable` and `reader-script` are no longer bound or used. The old three-argument Java constructors remain as compatibility entrypoints and delegate to the native reader.

| Configuration prefix | Source contract |
| --- | --- |
| `app.sync.l2-manifest` | 15-field D085 manifest |
| `app.sync.l2-daily-features` | 110-field D086 daily features |
| `app.sync.l2-intraday-bar-features` | 60-field D087 minute features |
| `app.sync.l2-event-response-features` | 73-field D088 event response |
| `app.sync.l2-t0-training-labels` | 62-field D089 training labels |

Each prefix accepts `.dataset-root`, defaulting to `F:/repo/engine/back-monitor/artifacts/level2_t0_dataset`. The native reader preserves null metrics, signed int64 values, naive timestamp nanoseconds and the existing exchange-time mapping. It rejects nested/repeated fields, schema/order drift, infinity, malformed manifest JSON, unsafe linked paths and duplicate complete business keys. Existing source row, file, byte, page, cancellation and deadline bounds remain enforced. Frozen inspections bind content hashes, schemas and source roots; source inventories are rechecked before the first consumer callback. D087/D088/D089 choose the latest receipt per symbol and reject an unsuccessful latest receipt instead of falling back to an older successful batch.

The isolated D085–D089 job owners retain their target and ledger contracts. `FormalL2DailyFeaturesIngestion` adds a separate fixed-table path for the existing `l2_daily_features`:

- `validate(day[, cancelled])` reads the currently available certified daily source and compares all 110 canonical fields with bounded target-key reads. It requires exact daily row coverage and a stable, settled physical WAL frontier. It performs no writes or local-ledger initialization.
- `ingestAndValidate(day, cancelled)` acquires the shared persistent date lock and fills only keys proven missing from that source. Existing value conflicts and target keys absent from the source stop ingestion. Batches obey both the 200-row ceiling and the actual 1 MiB transport-byte budget. Every submitted batch and the whole requested date must pass readback.
- The public `Evidence` record supplies source/schema/root fingerprints, row counts, verified field count, physical target identity/version and readback digest. New execution evidence is recorded in the existing SQLite run ledger; earlier table history is never assigned synthetic receipts. Unknown delivery retains an `IN_DOUBT` date lock for explicit reconciliation.

The formal writer cannot create/drop tables, overwrite a confirmed existing value or enumerate all historical dates. A table row or an older receipt cannot substitute for missing current Parquet/manifest input. Source certification here proves coverage of the selected manifest universe; broader strategy universe and calendar coverage remain the responsibility of the caller's input acceptance gate.

Single-date CLI entrypoints expose the same formal service:

```text
validate-formal-l2-day --date YYYY-MM-DD
ingest-formal-l2-day --date YYYY-MM-DD
```

The native archive batch also emits a day-level JSONL with a `COMPLETE` manifest and aggregate SHA-256. Once that pair is verified, `ingest-formal-l2-jsonl-day --date YYYY-MM-DD --source-root PATH` can publish that day through the same formal writer, date lock, conflict checks, WAL settlement, and exact readback. It checks every row against the manifest's successful symbol receipt and per-symbol result SHA-256 before submission; it still fills only confirmed missing `(ts,symbol)` keys.

For a completed archive batch, `tools/ingest_l2_native_jsonl_formal.ps1` first verifies every requested day's manifest/hash, then invokes the single-day command serially and saves per-day writer evidence plus a post-ingestion `SELECT * FROM l2_daily_features ORDER by ts desc;` result.

Validation is read-only. Ingestion fills certified missing keys under the existing persistent date lock. Both reject future dates and require the configured Parquet/manifest source for the requested date. Successful output includes the native evidence and keeps `factorReady=false`: this command does not certify the broader factor or strategy universe. If delivery becomes uncertain, reconcile the original retained lock before retrying ingestion.

`NativeL2ParquetReaderTest` includes a checked-in Parquet 2.6/Snappy reference generated independently with PyArrow 21.0.0. Generation used Python once; production ingestion and the interoperability test read the bytes using Java only. Hadoop's shaded client API/runtime supplies the Parquet library's compatibility classes; local reads use `LocalInputFile` and a plain Parquet configuration.
