CREATE TABLE source_probe (
    request_id varchar(256) PRIMARY KEY,
    request_fingerprint varchar(64) NOT NULL,
    request_json text NOT NULL,
    state varchar(32) NOT NULL,
    result_json text,
    error_type varchar(256),
    started_at timestamp NOT NULL DEFAULT current_timestamp,
    completed_at timestamp
);
-- Survives service restart and database restore; distinct metadata environments never share test targets.
CREATE TABLE runtime_identity (
    singleton integer PRIMARY KEY CHECK(singleton=1),
    namespace varchar(32) NOT NULL UNIQUE
);
