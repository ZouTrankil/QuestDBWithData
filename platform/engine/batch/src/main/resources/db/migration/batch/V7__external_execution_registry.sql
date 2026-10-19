-- Child identity is reserved before any external process can be started.
-- A nonterminal row is never reclaimed as permission to launch a second child.
CREATE TABLE external_execution (
    instance_id varchar(64) NOT NULL REFERENCES business_instance(instance_id),
    stage varchar(100) NOT NULL,
    child_id varchar(64) NOT NULL UNIQUE,
    input_identity varchar(64) NOT NULL,
    state varchar(24) NOT NULL CHECK(state IN ('STARTING','RUNNING','EXITED','IN_DOUBT','BLOCKED')),
    pid bigint,
    process_started_at timestamp,
    heartbeat_at timestamp,
    started_at timestamp,
    finished_at timestamp,
    result_json text,
    log_path text,
    reason text,
    created_at timestamp NOT NULL DEFAULT current_timestamp,
    updated_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY(instance_id,stage)
);
CREATE INDEX external_execution_state_idx ON external_execution(state,updated_at);

CREATE TABLE external_execution_event (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    child_id varchar(64) NOT NULL REFERENCES external_execution(child_id),
    event varchar(40) NOT NULL,
    detail text NOT NULL,
    occurred_at timestamp NOT NULL DEFAULT current_timestamp
);
