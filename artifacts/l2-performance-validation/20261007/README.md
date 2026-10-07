# Native L2 performance evidence — 2026-10-07

Report: [performance and cleaning preparation](../../../docs/l2-performance-cleaning-20261007.md).

- `performance-summary.json` / `.md`: 09/28 fixed 15-symbol workload, original serial versus optimized 1/2/4/8 workers, two measured rounds after one warmup. All 150 measured output hashes agree per symbol-day.
- `real-batch-summary.json`: actual 45-symbol-day batch across 09/28–09/30, first run 73.319 seconds, direct resume 3.116 seconds; formal script resumed 45/45 with no recomputation. Full-row guards use the frozen 110-field schema.
- `benchmarks/*`: original probe reports, task/round/stage CSV and complete daily output.
- `batch-real-first-snapshot/*`: first-run manifests, progress and JSONL. `batch-real/<date>`: the successful formal Gradle resume, including per-symbol results/checkpoints.
- `batch-real/run-*`: formal script plan, arguments transport, native command log and summary. The measured 32.502-second script invocation includes isolated compilation and Gradle/JVM startup.
- `archive-metadata/*-symbols.json`: archive member sizes for the all-code workload model. Archive listings cover 23,732 symbol-days and 128.569 GB expanded CSV; stock/ETF-only scope is unconfirmed.
- `cleaning-plan.json`: three-day full-archive preparation. `PlanOnly` creates no raw/features files.
- `production-source-hashes.json`: SHA256 of production sources at evidence freeze. Runtime computation bytecode fingerprint is recorded in each batch manifest.
- `evidence-manifest.json`: file size and SHA256 of this evidence collection.

Java diagnostic and standard-library analysis sources are included. `README-benchmark.md` describes the workspace compile/run commands. Performance measurements used Java 24, `-Xms1g -Xmx24g`, Windows, and local NVMe; already-extracted files and warm cache for repeated probe rounds. Source CSV and build/cache trees are excluded.

The extrapolation is a planning model from 15 serial symbol-days, not a full-archive measurement. The 45-sample parallel batch was separately timed and was not inserted as serial fit data. Extraction and database ingestion are excluded. No new Python algorithm comparison is claimed; the earlier 09/24 correctness evidence is under `artifacts/l2-native-validation/20260924`.

Analysis scripts accept `--root` for a working copy of this directory. They rewrite their summary files; retain this frozen collection when reproducing an analysis. Rerunning native timing needs the corresponding extracted source CSV.
