# D002 field and temporal mapping

Status: running; mapping only, not a completed dataset.

StockDetailInfo models all 18 stored fields, with ts_code as natural identity. TushareStockDetailDto contains the 17 requested upstream fields; the observation instant is supplied independently and is not claimed as source update time. StockDetailInfoMapper explicitly converts source, business values and the existing generated storage record. No generated file was edited.

Logical observed_at maps to physical update_time and requires an Instant representable exactly in microseconds. Logical listing_date/delisting_date map to the existing BASIC date strings and use LocalDate. The source parser accepts null/empty optional dates and strict valid yyyyMMdd otherwise. The storage compatibility parser additionally maps exactly the observed literal `None` to null for those two date columns only. Other malformed strings fail. Retained raw baseline samples preserve the original sentinel values.

Python temporal conversion was traced through questdb_model._coerce_dataframe_to_questdb_schema → temporal_spec_from_schema → TemporalColumnSpec.from_legacy → normalize_temporal_series. The _time suffix enables the legacy naive-UTC path; pandas conversion localizes such clock values to UTC. The connector overwrites preprocess_dataframe's UTC-aware update_time with datetime.now(). Java preserves existing stored epoch values without applying a speculative local timezone shift. This compatibility behavior does not prove historical observation instants represented the actual wall-clock event accurately. New Java observations must be explicit instants.

StockDetailInfoDataset declares the actual NONE/non-WAL/no-designated/no-dedup table and explicit temporal meanings. It is not registered and currently declares only READ capability; no append writer is admitted. Static-table incremental merge and publication still need implementation and real acceptance.

`local-D002-mapping-839e`: three tests passed. They cover all 18 values through domain/storage/typed-value round trips, strict invalid-date/precision rejection, and all three actual captured QuestDB samples. Epoch microseconds are unchanged; the two listed-stock delist_date sentinel values normalize explicitly. The preceding 839d test attempt failed because the test assumed the wrong public exception type and used a serialization-only mapper for Instant deserialization; both test issues were corrected. No new source request or data write occurred.
