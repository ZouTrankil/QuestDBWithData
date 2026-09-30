-- SQLite metadata ledger schema. SQLite has one namespace per database file.
CREATE TABLE business_instance (
    instance_id varchar(64) PRIMARY KEY,
    job varchar(100) NOT NULL,
    logical_date date NOT NULL,
    definition_version varchar(256) NOT NULL,
    revision varchar(256) NOT NULL,
    supersedes varchar(64) REFERENCES business_instance(instance_id),
    revision_reason text,
    input_identity varchar(64) NOT NULL,
    request_json text NOT NULL,
    business_state varchar(32) NOT NULL,
    batch_execution_id bigint,
    reason text,
    created_at timestamp NOT NULL DEFAULT current_timestamp,
    updated_at timestamp NOT NULL DEFAULT current_timestamp
);
CREATE TABLE trigger_request (
    request_id varchar(256) PRIMARY KEY,
    instance_id varchar(64) NOT NULL REFERENCES business_instance(instance_id),
    request_json text NOT NULL,
    received_at timestamp NOT NULL DEFAULT current_timestamp
);
CREATE TABLE stage_result (
    instance_id varchar(64) NOT NULL REFERENCES business_instance(instance_id),
    stage varchar(100) NOT NULL,
    business_state varchar(32) NOT NULL,
    evidence_json text,
    reason text,
    updated_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY(instance_id,stage)
);
CREATE TABLE audit_event (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    instance_id varchar(64),
    action varchar(100) NOT NULL,
    detail text NOT NULL,
    occurred_at timestamp NOT NULL DEFAULT current_timestamp
);
CREATE TABLE write_intent (
    batch_id varchar(64) PRIMARY KEY,
    instance_id varchar(64) NOT NULL REFERENCES business_instance(instance_id),
    target varchar(128) NOT NULL,
    owner varchar(128) NOT NULL,
    source_fingerprint varchar(64) NOT NULL,
    artifact text NOT NULL,
    expected_rows bigint NOT NULL CHECK(expected_rows >= 0),
    delivery varchar(32) NOT NULL,
    attempt integer NOT NULL DEFAULT 0,
    proof text,
    updated_at timestamp NOT NULL DEFAULT current_timestamp
);
CREATE TABLE schedule_definition (
    id varchar(100) PRIMARY KEY,
    cron varchar(100) NOT NULL,
    zone varchar(100) NOT NULL,
    calendar_version varchar(256) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1
);
-- A durable reservation is not a lease. A crashed or partitioned sender keeps ownership.
CREATE TABLE target_reservation (
    target varchar(128) PRIMARY KEY,
    batch_id varchar(64) NOT NULL UNIQUE REFERENCES write_intent(batch_id)
);
