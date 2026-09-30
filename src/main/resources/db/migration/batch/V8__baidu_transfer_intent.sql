-- Baidu share-to-drive is outside the SQLite/QuestDB transaction boundary.
-- UNKNOWN is reconciled by inspecting the configured remote path; it is never a retry token.
CREATE TABLE baidu_transfer_intent (
    logical_date varchar(8) PRIMARY KEY,
    remote_path text NOT NULL,
    source_fs_id bigint NOT NULL,
    source_size_bytes bigint NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('INTENT','UNKNOWN','VERIFIED')),
    created_at timestamp NOT NULL DEFAULT current_timestamp,
    updated_at timestamp NOT NULL DEFAULT current_timestamp
);
