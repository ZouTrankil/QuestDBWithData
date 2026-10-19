CREATE TABLE l2_archive_ledger (
  archive_sha256 TEXT PRIMARY KEY,
  source_path TEXT NOT NULL,
  frozen_path TEXT NOT NULL,
  trade_date TEXT NOT NULL,
  archive_size_bytes INTEGER NOT NULL,
  archive_modified_millis INTEGER NOT NULL,
  member_count INTEGER NOT NULL,
  symbol_count INTEGER NOT NULL,
  status TEXT NOT NULL,
  revision_of_sha256 TEXT REFERENCES l2_archive_ledger(archive_sha256),
  first_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(source_path, archive_sha256)
);
CREATE INDEX l2_archive_ledger_date_idx ON l2_archive_ledger(trade_date, first_seen_at);
CREATE TABLE l2_archive_observation (
  source_path TEXT PRIMARY KEY,
  size_bytes INTEGER NOT NULL,
  modified_millis INTEGER NOT NULL,
  content_sha256 TEXT NOT NULL,
  observed_at_millis INTEGER NOT NULL
);
CREATE TABLE l2_archive_member (
  archive_sha256 TEXT NOT NULL REFERENCES l2_archive_ledger(archive_sha256) ON DELETE RESTRICT,
  member_path TEXT NOT NULL,
  symbol TEXT NOT NULL,
  file_name TEXT NOT NULL,
  row_count INTEGER NOT NULL,
  raw_bytes INTEGER NOT NULL,
  member_sha256 TEXT NOT NULL,
  trade_date_mismatch_rows INTEGER NOT NULL,
  PRIMARY KEY(archive_sha256, member_path)
);
CREATE INDEX l2_archive_member_symbol_idx ON l2_archive_member(archive_sha256, symbol, file_name);
