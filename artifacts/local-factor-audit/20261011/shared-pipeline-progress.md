# Java factor pipeline parity — 2026-10-11

## Verified
- MultiPoolFactorPipeline streams one date / pool / factor result, sharing raw columns and reusing pool scratch and a batch-scoped projection cache.
- Synthetic two-pool / two-factor check: 4 sections, 2 projection builds, 2 cache hits; independent output parity and warmup exclusion passed.
- Cached neutralization matches Python's original NumPy SVD projector on 800 symbols / 12 industries: max absolute error 1.8318679906315083e-15.
- Real local QuestDB snapshot for 000001.SZ, 309 dates: all 45 implemented Java daily features match Python pandas fallback at rtol=1e-9 / atol=1e-11 with equal missing masks.
- Local back-monitor .venv does not have Polars. Its dispatcher currently selects pandas. Benchmark-only Polars 1.43.2 installation is isolated under benchmark-deps, matching uv.lock.

## Limits and remaining work
- Single-symbol feature parity does not cover whole-market features, PIT joins, all factor families, pool membership, missing-data edge cases, or formal publication.
- Pool memberships must come from frozen PIT resolution; the numerical pipeline does not invent memberships or certify input coverage.
- Incremental output-date filtering exists in the numerical pipeline; source revision detection, missing-date planning, restart checkpoints and formal writer integration remain.
- Existing timings are component probes with differing scope, not an end-to-end Java/Python speed comparison.
- Need real multi-security data, equivalent features, cold/warm runs, I/O and compute breakdown, and peak-memory measurement before claiming a performance advantage.

- Polars 1.43.2 parity completed: same 309-date snapshot, all 45 Java features matched (daily-polars-parity.json). Benchmark dependency installation completed successfully.

## Verified storage contract correction
Live factor_observation has signal_date/entity_type/entity_id/factor_id/definition_version as dedup keys, factor_value, available_at, quality_status, scope, and l2_source_lineage_digest. There is no pool key. Python QuestDB adapter canonicalizes value_raw to factor_value and accepts only GLOBAL/preferred all_market source rows. Pool-specific incremental sections are compute planning only; physical raw publication coverage must be checked independently by global business key. Java GlobalFactorObservations maps raw values without applying direction or normalization. No formal writes were made during this contract inspection.

Java inspect-factor-observations live read passed for 2026-09-30 / etf / component_l2_inflow_breadth / etf_factor_maturity_v1: 1580 rows and unique keys; independent HTTP count 1580. Fixed LocalDateTime summary serialization using explicit ISO text. This verifies reader behavior on one existing section, not expected universe completeness or publication.
