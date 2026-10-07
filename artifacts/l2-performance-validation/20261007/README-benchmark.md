# Native L2 performance probe

This diagnostic invokes the existing Java CSV parser and native feature builders. It writes local benchmark files and daily feature JSONL, without database writes. Each worker parses one symbol-day, computes its features and releases its parsed records before taking another job.

## Compile (JDK 24)

Run from the repository root in PowerShell:

```powershell
$taskCp = (Get-Content 'artifacts/java-migration/D088/commands/compile-classpath.txt' -Raw).Trim()
$benchCp = "var/l2-pipeline-validation/gradle-build/classes/java/main;$taskCp"
New-Item -ItemType Directory -Force 'var/l2-performance-validation/classes' | Out-Null
& 'C:\Users\zouqiang\.jdks\jdk-24.0.2\bin\javac.exe' --release 24 -encoding UTF-8 -cp $benchCp -d 'var/l2-performance-validation/classes' 'var/l2-performance-validation/NativeL2Benchmark.java'
```

The isolated Gradle build supplies the current production classes. If those sources change, rebuild those classes first. Put this benchmark's classes before the production classpath when running.

## One date, fixed symbols

```powershell
& 'C:\Users\zouqiang\.jdks\jdk-24.0.2\bin\java.exe' --enable-native-access=ALL-UNNAMED -Xms1g -Xmx16g -cp "var/l2-performance-validation/classes;$benchCp" NativeL2Benchmark --source-root 'var/l2-performance-validation/raw' --date 20260928 --symbols '000001.SZ,600000.SH,300750.SZ,688981.SH,510300.SZ,159915.SZ,588000.SZ' --workers 1 --warmup 1 --rounds 2 --stage-profile false --output-dir 'var/l2-performance-validation/bench-w1'
```

Use workers `1`, `2`, `4`, then `8` in separate JVMs with the same heap settings and job set. `--dates 20260928,20260929,20260930` takes the same symbols on all three dates. Source suffixes must match the extracted directory names; output symbols are canonicalized by the production parser.

## Different symbols on each date

Create a JSON array such as:

```json
[
  {"date": "20260928", "symbol": "159915.SZ"},
  {"date": "20260929", "symbol": "510300.SZ"},
  {"date": "20260930", "symbol": "588000.SZ"}
]
```

Pass `--jobs-file path/to/jobs.json` instead of `--date` / `--dates` and `--symbols`. Each item is independently resolved under `--source-root/<date>/<symbol>`.

## Stage profile and JFR

Use `--stage-profile true` on a single representative or worst-case symbol. It records feature normalization, every native public builder, and typed row construction. P0/P1/P2/P4/P5 share `L2WideFeatures.compute` and are reported as one combined stage. This mode reproduces production builder orchestration; use `false` for the main throughput comparison to call `L2DailyFeaturePipeline.compute` directly.

Optional `--jfr path/to/profile.jfr` records JDK's `profile` configuration for the whole run. JFR adds profiling overhead, so compare concurrency without it and use it for bottleneck diagnosis. The explicit `--enable-native-access=ALL-UNNAMED` permits the Windows native RSS probe (`GetProcessMemoryInfo`) without subprocesses.

## Output

- `benchmark.json`: machine/JVM details, per-round totals, per-task metrics, stage details, memory-pool peaks, and measurement caveats.
- `rounds.csv`: elapsed and process CPU, throughput, GC, heap/RSS peaks per warmup or measurement round.
- `tasks.csv`: source bytes, raw/normalized row counts, parse/feature/output wall and thread CPU, thread allocations, heap snapshots, and SHA-256 for each symbol-day.
- `stages.csv`: per-builder wall/thread CPU/allocation bytes when stage profiling is enabled.
- `daily-features.jsonl`: complete native 110-field output from the final measurement round.

Warmup is excluded by filtering `phase=measure`. Per-task GC/heap metrics cover the whole JVM and overlap under concurrency; sum the per-round GC values, not task GC values. RSS and heap are sampled every 20 ms; memory-pool peaks supplement short missed heap peaks. The Windows peak working-set counter covers the JVM's entire lifetime, including warmup. Thread CPU omits GC/compiler threads; process CPU includes them. Reads are usually warm filesystem reads after warmup; extraction time and database writing are excluded.

## Probe smoke

The two small frozen ETF fixtures were run with JDK 24, workers 2, warmup 1, measured rounds 2, stage profiling and JFR. JSONL contains both complete 110-field outputs, RSS/heap/GC are populated, and checksums are stable across rounds. These fixture timings only validate the instrumentation and are not an estimate for full trading days.

## Refresh performance analysis

```powershell
& 'D:\work\fund_2\back-monitor\.venv\Scripts\python.exe' -X utf8 'var/l2-performance-validation/analyze_performance.py'
```

This lightweight standard-library script reads already-written `benchmarks/w1`, `optimized-w*` and `full-selected*` results. It can be rerun while measurements continue; only completed measured rounds are summarized, and missing rounds are marked provisional. It writes `performance-summary.json` and `performance-summary.md`.

`performance-schema.json` freezes the 110 output field names/types and versions. Exact full-row/SHA comparisons cover all measured rounds and workers; any mismatch exits with status 2 and a failed guard. The estimator uses a nonnegative constant + DEAL/ORDER/QUOTE MiB model from serial optimized measurements, and adds only worker CPU observations from any later full-selected run. Parallel task wall times do not enter the serial model. Archive forecasts cover every instrument in the three archives as an upper workload scenario until the cleaning universe is confirmed. Forecast intervals include an explicit 35% planning margin and model/round variability; they are not confidence intervals and exclude extraction and database ingestion.
