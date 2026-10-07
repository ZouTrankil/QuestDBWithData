# Native L2 performance summary

Guard: **passed**; 150 measured task outputs, 15 unique symbol-days.

| Run | Rounds | Median seconds (min–max) | Symbols/s | Parse % | Heap peak GiB | RSS peak GiB | GC seconds median | Allocated GiB median |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| optimized-w1 | 2/2 | 55.8 (55.5–56.1) | 0.269 | 76.1 | 5.3 | 6.8 | 1.77 | 25.0 |
| optimized-w2 | 2/2 | 30.7 (30.2–31.2) | 0.488 | 73.7 | 9.4 | 11.2 | 1.95 | 25.1 |
| optimized-w4 | 2/2 | 23.2 (23.0–23.3) | 0.648 | 74.5 | 15.5 | 18.0 | 2.06 | 25.1 |
| optimized-w8 | 2/2 | 21.1 (21.1–21.2) | 0.710 | 75.0 | 10.9 | 18.6 | 2.27 | 25.0 |
| w1 | 2/2 | 91.0 (90.5–91.5) | 0.165 | 85.3 | 6.6 | 7.6 | 3.52 | 129.2 |

## All-archive workload scenario

The cleaning universe is unconfirmed. These forecasts include all 23,732 symbol-days in the three archives, including instruments outside stock/ETF scope. Times below exclude extraction and database ingestion.

- 20260928: serial ≈ 80 minutes, planning range 30–110 minutes (7926 instruments).
- 20260929: serial ≈ 70 minutes, planning range 20–110 minutes (7918 instruments).
- 20260930: serial ≈ 70 minutes, planning range 20–110 minutes (7888 instruments).
- All three dates: serial ≈ 230 minutes, planning range 90–310 minutes.
- 2 workers: ≈ 130 minutes, planning range 40–180 minutes; selected-job speedup 1.82×.
- 4 workers: ≈ 90 minutes, planning range 30–130 minutes; selected-job speedup 2.41×.
- 8 workers: ≈ 90 minutes, planning range 30–120 minutes; selected-job speedup 2.64×.

Wall fit sample count: 15; weighted absolute residual 12.0%. The interval combines a fixed ±35% planning margin, empirical residuals, leave-one-out predictions and round variation. It is not a confidence interval.

## Limits

- The measured rounds exclude JVM warmup and use already-extracted files, usually warm OS cache. Extraction, cold storage, database ingestion and verification are not included.
- The serial wall model starts with only 15 selected symbol-days on 09/28. Even a later 45-sample CPU fit does not validate serial wall behavior on 09/29 or 09/30.
- Largest symbols are stragglers; same-sample speedup need not transfer to thousands of smaller symbols or a filtered stock/ETF universe.
- The constant and quote-size terms can be hard to separate; small-symbol fixed costs/JIT effects may dominate full-archive extrapolation.
- The ranges are deliberately broad planning envelopes, not statistical confidence intervals or guaranteed runtimes.
- Worker thread CPU excludes GC/compiler CPU. Process CPU and per-round GC are the aggregate JVM measures; do not sum overlapping per-task GC.
- Heap/RSS are sampled every 20 ms, and memory-pool peaks differ from simultaneous heap peak. Windows lifetime RSS includes warmup.
- Pending or partial runs are marked provisional. Rerun this script after the benchmark writes more complete rounds.
