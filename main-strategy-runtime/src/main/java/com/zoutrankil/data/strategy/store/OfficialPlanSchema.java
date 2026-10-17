package com.zoutrankil.data.strategy.store;

/** 兼容第 3 版类型化正式账本表，并增加 Java 导入校验回执。 */
final class OfficialPlanSchema {
    private OfficialPlanSchema() { }
    static final String V3 = """
CREATE TABLE IF NOT EXISTS strategy_plan_schema_versions (
                version INTEGER PRIMARY KEY,
                applied_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS strategy_plans (
                plan_id TEXT PRIMARY KEY,
                content_sha256 TEXT NOT NULL UNIQUE,
                schema_version TEXT NOT NULL,
                plan_kind TEXT NOT NULL,
                available INTEGER NOT NULL CHECK (available IN (0, 1)),
                status TEXT NOT NULL,
                strategy TEXT NOT NULL,
                signal_date TEXT NOT NULL,
                execution_date TEXT NOT NULL,
                valuation_date TEXT,
                rebalance_trigger TEXT NOT NULL,
                portfolio_state TEXT NOT NULL,
                state_trigger_reason TEXT NOT NULL,
                diagnostic_top_candidates_excluded INTEGER NOT NULL
                    CHECK (diagnostic_top_candidates_excluded IN (0, 1)),
                diagnostic_payload_path TEXT NOT NULL,
                published_at TEXT NOT NULL,
                is_current INTEGER NOT NULL DEFAULT 1 CHECK (is_current IN (0, 1)),
                UNIQUE(strategy, plan_kind, signal_date, execution_date, content_sha256)
            );

            CREATE TABLE IF NOT EXISTS strategy_plan_targets (
                plan_id TEXT NOT NULL REFERENCES strategy_plans(plan_id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
                symbol TEXT NOT NULL,
                name TEXT NOT NULL,
                asset_type TEXT NOT NULL DEFAULT 'etf',
                target_weight REAL NOT NULL CHECK (target_weight >= 0 AND target_weight <= 1),
                base_target_weight REAL CHECK (
                    base_target_weight IS NULL OR
                    (base_target_weight >= 0 AND base_target_weight <= 1)
                ),
                selection_score REAL,
                component_l2_score_smooth REAL,
                component_buy_breadth_smooth REAL,
                trend_score REAL,
                liquidity_score REAL,
                score_component_l2_contrib REAL,
                score_buy_breadth_contrib REAL,
                score_trend_contrib REAL,
                score_liquidity_contrib REAL,
                weight_component_l2 REAL,
                weight_buy_breadth REAL,
                weight_trend REAL,
                weight_liquidity REAL,
                optimized_score_quality REAL,
                dynamic_overlay REAL,
                state_dynamic_overlay REAL,
                top10_high_vol_weight REAL CHECK (
                    top10_high_vol_weight IS NULL OR
                    (top10_high_vol_weight >= 0 AND top10_high_vol_weight <= 1)
                ),
                component_crowding_flag INTEGER CHECK (
                    component_crowding_flag IS NULL OR
                    component_crowding_flag IN (0, 1)
                ),
                self_overheat_flag INTEGER CHECK (
                    self_overheat_flag IS NULL OR self_overheat_flag IN (0, 1)
                ),
                PRIMARY KEY(plan_id, ordinal),
                UNIQUE(plan_id, symbol)
            );

            CREATE TABLE IF NOT EXISTS strategy_plan_target_components (
                plan_id TEXT NOT NULL,
                target_ordinal INTEGER NOT NULL,
                ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
                symbol TEXT NOT NULL,
                PRIMARY KEY(plan_id, target_ordinal, ordinal),
                UNIQUE(plan_id, target_ordinal, symbol),
                FOREIGN KEY(plan_id, target_ordinal)
                    REFERENCES strategy_plan_targets(plan_id, ordinal)
                    ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS strategy_plan_aliases (
                strategy TEXT NOT NULL,
                plan_kind TEXT NOT NULL,
                alias TEXT NOT NULL,
                plan_id TEXT NOT NULL REFERENCES strategy_plans(plan_id),
                updated_at TEXT NOT NULL,
                PRIMARY KEY(strategy, plan_kind, alias)
            );

            CREATE TABLE IF NOT EXISTS strategy_plan_sources (
                plan_id TEXT NOT NULL REFERENCES strategy_plans(plan_id) ON DELETE CASCADE,
                source_snapshot_path TEXT NOT NULL,
                source_sha256 TEXT NOT NULL,
                recorded_at TEXT NOT NULL,
                PRIMARY KEY(plan_id, source_snapshot_path)
            );

            CREATE TABLE IF NOT EXISTS strategy_plan_decisions (
                plan_id TEXT PRIMARY KEY REFERENCES strategy_plans(plan_id) ON DELETE CASCADE,
                overlay_id TEXT NOT NULL DEFAULT '',
                mode TEXT NOT NULL DEFAULT '',
                order_side_effect TEXT NOT NULL DEFAULT '',
                reason TEXT NOT NULL DEFAULT '',
                factor_platform_run_id TEXT NOT NULL DEFAULT '',
                factor_platform_mode_requested TEXT NOT NULL DEFAULT '',
                daily_main_only INTEGER CHECK (daily_main_only IS NULL OR daily_main_only IN (0, 1)),
                weekly_main_plan INTEGER CHECK (weekly_main_plan IS NULL OR weekly_main_plan IN (0, 1)),
                intrweek_switch_rule_enabled INTEGER CHECK (
                    intrweek_switch_rule_enabled IS NULL OR
                    intrweek_switch_rule_enabled IN (0, 1)
                ),
                note TEXT NOT NULL DEFAULT '',
                avg_pair_corr REAL,
                avg_component_overlap REAL,
                single_cap REAL,
                dynamic_hint_role TEXT NOT NULL DEFAULT '',
                dynamic_hint_not_total_exposure_ceiling INTEGER CHECK (
                    dynamic_hint_not_total_exposure_ceiling IS NULL OR
                    dynamic_hint_not_total_exposure_ceiling IN (0, 1)
                ),
                dynamic_hint_target_etf_weight REAL,
                dynamic_hint_cash_weight REAL,
                base_total_weight REAL,
                target_etf_weight REAL,
                cash_weight REAL,
                macro_state TEXT NOT NULL DEFAULT '',
                macro_monitor_date TEXT,
                macro_data_quality_flag TEXT NOT NULL DEFAULT '',
                macro_ceiling REAL,
                t200_live_ceiling REAL,
                final_ceiling_now REAL,
                watch_enabled INTEGER CHECK (watch_enabled IS NULL OR watch_enabled IN (0, 1)),
                watch_level TEXT NOT NULL DEFAULT '',
                target_plan_reference TEXT NOT NULL DEFAULT '',
                qmt_position_policy TEXT NOT NULL DEFAULT '',
                running_high_policy TEXT NOT NULL DEFAULT '',
                t200_drawdown REAL,
                t200_target_exposure REAL,
                t320_drawdown REAL,
                t320_target_exposure REAL,
                policy_source TEXT NOT NULL DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS strategy_plan_reasons (
                plan_id TEXT NOT NULL REFERENCES strategy_plans(plan_id) ON DELETE CASCADE,
                category TEXT NOT NULL,
                ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
                reason TEXT NOT NULL,
                PRIMARY KEY(plan_id, category, ordinal)
            );

            CREATE INDEX IF NOT EXISTS ix_strategy_plans_timeline
            ON strategy_plans(plan_kind, strategy, execution_date, signal_date);

            INSERT OR IGNORE INTO strategy_plan_schema_versions(version, applied_at)
            VALUES (1, CURRENT_TIMESTAMP);
        INSERT OR IGNORE INTO strategy_plan_schema_versions(version, applied_at) VALUES (2, CURRENT_TIMESTAMP);
        INSERT OR IGNORE INTO strategy_plan_schema_versions(version, applied_at) VALUES (3, CURRENT_TIMESTAMP);
        PRAGMA user_version=3;
        """;
    static final String IMPORT_RECEIPTS = """
        CREATE TABLE IF NOT EXISTS strategy_plan_import_receipts (
            plan_id TEXT PRIMARY KEY REFERENCES strategy_plans(plan_id) ON DELETE CASCADE,
            canonical_payload TEXT NOT NULL,
            source_snapshot_path TEXT NOT NULL,
            source_sha256 TEXT NOT NULL,
            score_run_id TEXT NOT NULL,
            score_panel_sha256 TEXT NOT NULL,
            score_coverage_sha256 TEXT NOT NULL,
            recipe_sha256 TEXT NOT NULL,
            max_complete_date TEXT NOT NULL,
            recipe_code_revision TEXT NOT NULL,
            recorded_at TEXT NOT NULL
        );
        """;
}