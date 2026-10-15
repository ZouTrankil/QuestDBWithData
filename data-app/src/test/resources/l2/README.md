`pyarrow-21-daily-snappy.parquet` is an independent interoperability reference generated once with PyArrow 21.0.0, Parquet 2.6, Snappy compression. Tests read the checked-in bytes using Java only. No interpreter is used by production ingestion or by these tests.

The fixture follows the frozen 110-field `L2DailyFeatureField` order: one `000001.SZ` row at naive `timestamp[ns]` midnight on 2026-09-21; metric strings `pyarrow-reference-21.0.0`, boolean `true`, int64 `9`, doubles `1.25`, and null `mfi_score`. The test supplies a temporary Java-written manifest receipt for its own local path.
