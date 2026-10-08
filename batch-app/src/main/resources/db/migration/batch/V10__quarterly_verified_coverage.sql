-- GDP observations are recorded only with their physically verified certificate.
CREATE TABLE quarterly_source_coverage (
    dataset varchar(64) NOT NULL,
    definition_version varchar(256) NOT NULL,
    scope_identity varchar(64) NOT NULL,
    observation_quarter_end date NOT NULL,
    instance_id varchar(64) NOT NULL REFERENCES business_instance(instance_id),
    input_fingerprint varchar(64) NOT NULL,
    evidence_artifact text NOT NULL,
    available_at timestamp NOT NULL,
    verified_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY(dataset, definition_version, scope_identity, observation_quarter_end),
    CHECK (strftime('%m', observation_quarter_end) IN ('03','06','09','12')),
    CHECK (observation_quarter_end = date(observation_quarter_end, 'start of month', '+1 month', '-1 day')),
    CHECK (length(scope_identity) = 64),
    CHECK (length(input_fingerprint) = 64)
);
CREATE INDEX quarterly_source_coverage_by_period
    ON quarterly_source_coverage(dataset, observation_quarter_end);
