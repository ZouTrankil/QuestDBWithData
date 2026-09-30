-- Durable observations backing monthly continuity watermarks. A row is inserted
-- only in the same SQLite transaction that publishes a physically verified source result.
CREATE TABLE monthly_source_coverage (
    dataset varchar(64) NOT NULL,
    definition_version varchar(256) NOT NULL,
    scope_identity varchar(64) NOT NULL,
    observation_month date NOT NULL,
    instance_id varchar(64) NOT NULL REFERENCES business_instance(instance_id),
    input_fingerprint varchar(64) NOT NULL,
    evidence_artifact text NOT NULL,
    available_at timestamp NOT NULL,
    verified_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY(dataset, definition_version, scope_identity, observation_month),
    CHECK (strftime('%d', observation_month) = '01'),
    CHECK (length(scope_identity) = 64),
    CHECK (length(input_fingerprint) = 64)
);
CREATE INDEX monthly_source_coverage_by_month
    ON monthly_source_coverage(dataset, observation_month);
