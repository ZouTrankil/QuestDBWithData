# D002 typed read and legacy null compatibility

`StockDetailInfoReadRepository` registers the complete 18-column definition and returns full domain rows with a bounded `ts_code` keyset cursor. The registered generic read group supports explicit partial projections. The common bounded reader now has an explicit per-column legacy null-sentinel contract; only D002 `delist_date` declares the observed physical literal `None`. It maps that value to logical null and includes the sentinel in an `IS NULL` filter without weakening strict parsing for new source rows or other date columns.

`local-D002-read-live`: one opt-in read-only integration test passed against actual `stock_detail_info`. Two typed pages returned four unique keys (`000001.SZ`–`000004.SZ`) and matched independent full physical rows from `StockDetailInfoStorage`; listed rows had logical null delisting dates and `000003.SZ` retained its real 2002-06-14 delisting date. A read-group projection with `delisting_date IS NULL` returned the expected two listed keys with `LocalDate` listing dates and null delisting dates. Query/cursor summary is `read-live.json`. No target write occurred.

The typed repository requires all 18 logical columns to construct a domain row. Callers needing fewer fields use the read group, which returns typed `DatasetValues` for the requested projection.

`local-D002-read-regression` passed 12 focused existing definition, bounded-reader, mapping and read-group tests with zero failures after the column-level compatibility change.
