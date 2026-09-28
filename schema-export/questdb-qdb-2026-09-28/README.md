# QuestDB schema snapshot — qdb — 2026-09-28

Read-only export from the currently configured QuestDB instance. Server build: QuestDB 10.0.1. The export does not contain row data or credentials.

## Objects

| Object type | Count | Export |
| --- | ---: | --- |
| Table | 321 | [tables.sql](tables.sql) |
| View | 10 | [views.sql](views.sql) |
| Materialized view | 2 | [materialized-views.sql](materialized-views.sql) |
| Live view | 0 | [live-views.sql](live-views.sql) |
| **Total** | **333** | [schema.sql](schema.sql) preserves server dependency order |

## Domain catalog

[domain-catalog.json](domain-catalog.json) records each object's type, columns, QuestDB storage type, Java projection type, designated timestamp, dedup keys, WAL flag, partition, and candidate Java class. `table-domains.json`, `view-domains.json`, and `materialized-view-domains.json` split those contracts by object type. SQL definitions remain in the separate DDL files above.

The generator [`tools/generate_domain_models.py`](../../tools/generate_domain_models.py) produces Java records under `domain/table`, `domain/view`, and `domain/materializedview`. It produced 172 table row projections, 10 view read models, and 2 materialized-view read models. The 149 table objects named as WAL drills, backups, or staging artifacts remain in the full export/catalog but are excluded from generated application models.

These generated records use boxed Java types so nullable database values remain representable. QuestDB DDL does not provide the business nullability, units, or source-field semantics; review those before treating a projection as a canonical business domain model.

This is a physical-schema snapshot, not a migration to replay into another environment. The table inventory includes backup, stage, and WAL test/drill objects; those are exported faithfully but should not automatically become application domain classes. Review and promote business tables into canonical domain models separately.

## Object lists

### Materialized views

- `mv_market_breadth_daily_v1`
- `mv_retail_sentiment_daily_v1`

### Views

- `v_backtest_daily`
- `v_etf_market_overview_daily`
- `v_macro_core_monthly`
- `v_macro_liquidity_credit_monthly`
- `v_market_breadth_daily`
- `v_market_breadth_monthly`
- `v_regime_features_monitor_daily`
- `v_regime_features_monthly`
- `v_regime_market_monthly`
- `v_retail_sentiment_daily`
