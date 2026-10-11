"""Exercises the ETF native MV on this task's private QuestDB 10.0.1 instance."""
import json
from pathlib import Path
import sys
import time

import pandas as pd
import psycopg2

sys.path.insert(0, "F:/repo/engine/back-monitor/src")
from quant_platform.data.adapters.questdb.etf_market_overview_mv import (
    EtfMarketOverviewMaterializedView, OUTPUT, VIEW, RECEIPT,
)
from quant_platform.data.adapters.questdb.market_barometer import QuestDbMarketBarometerRepository


class PrivateClient:
    def __init__(self):
        self.connection = psycopg2.connect(host="127.0.0.1", port=19812, user="admin", password="quest", dbname="qdb", connect_timeout=5)
        self.connection.autocommit = True
        self.operations = []

    def fetch_df(self, sql, params=None):
        self.operations.append(sql)
        with self.connection.cursor() as cursor:
            cursor.execute(sql, params)
            return pd.DataFrame(cursor.fetchall(), columns=[x.name for x in cursor.description])

    def execute_ddl(self, sql, params=None, **kwargs):
        self.operations.append(sql)
        with self.connection.cursor() as cursor:
            cursor.execute(sql, params)

    def get_upsert_keys(self, table):
        rows = self.fetch_df("SELECT * FROM table_columns(%s)", (table,))
        return list(rows.loc[rows.upsertKey, "column"])


def settled(service):
    deadline = time.monotonic() + 10
    while True:
        try:
            service.source_versions()
            return
        except RuntimeError:
            if time.monotonic() > deadline:
                raise
            time.sleep(0.1)


client = PrivateClient()
try:
    build = client.fetch_df("SELECT build()").iloc[0, 0]
    assert "QuestDB 10.0.1" in build
    client.execute_ddl("CREATE TABLE etf_share(timestamp TIMESTAMP,ts_code SYMBOL,fd_share DOUBLE) TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(timestamp,ts_code)")
    client.execute_ddl("CREATE TABLE etf_daily(timestamp TIMESTAMP,ts_code SYMBOL,close DOUBLE) TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(timestamp,ts_code)")
    client.execute_ddl("INSERT INTO etf_share VALUES ('2026-09-29','A',100.0),('2026-09-29','B',200.0),('2026-09-30','A',50.0),('2026-09-30','C',10.0)")
    client.execute_ddl("INSERT INTO etf_daily VALUES ('2026-09-29','A',2.0),('2026-09-29','B',3.0),('2026-09-30','A',5.0)")
    service = EtfMarketOverviewMaterializedView(client)
    settled(service)
    installed = service.install()
    refreshed = service.refresh(full=True, timeout=30)
    activated = service.activate()
    assert installed.passed and refreshed.passed and activated.passed
    first = service.read(f"SELECT * FROM {VIEW} ORDER BY trade_date")
    assert list(first.etf_count) == [2, 1]
    assert list(first.total_share) == [300.0, 50.0]
    assert list(first.total_size_yi) == [0.08, 0.025]
    # Only the joined right table changes. The base checkpoint still agrees.
    old_base = client.fetch_df("SELECT base_table_txn FROM materialized_views() WHERE view_name=%s", (OUTPUT,)).iloc[0, 0]
    client.execute_ddl("INSERT INTO etf_daily VALUES ('2026-09-29','B',4.0)")
    settled(service)
    new_base = client.fetch_df("SELECT base_table_txn FROM materialized_views() WHERE view_name=%s", (OUTPUT,)).iloc[0, 0]
    assert old_base == new_base
    rejected = service.status()
    assert not rejected.caught_up
    try:
        service.read(f"SELECT * FROM {VIEW}")
        raise AssertionError("Right-source-only correction was incorrectly accepted")
    except RuntimeError:
        pass
    repaired = service.refresh(full=True, timeout=30)
    second = QuestDbMarketBarometerRepository(client).fetch_view(VIEW)
    assert repaired.passed and list(second.total_size_yi) == [0.1, 0.025]
    receipt = service._receipt()
    result = {"status": "PASS", "instance": "private 127.0.0.1:19812",
              "build": build, "database_writes": "Private instance only",
              "join_and_count_distinct_supported": True,
              "base_checkpoint_unchanged_after_right_correction": True,
              "right_correction_read_rejected": True,
              "full_refresh_repaired_right_correction": True,
              "checked_rows": len(second), "source_version": str(receipt.source_version),
              "output_version": str(receipt.output_version),
              "rows": second.astype(str).to_dict("records"),
              "cache_operations": [x for x in client.operations if "cache" in x.lower()]}
    Path(__file__).with_name("result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))
finally:
    client.connection.close()
