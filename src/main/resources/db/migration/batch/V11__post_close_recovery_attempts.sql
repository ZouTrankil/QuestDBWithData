CREATE TABLE post_close_recovery_attempt (
    instance_id varchar(64) PRIMARY KEY REFERENCES business_instance(instance_id),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    last_attempt_epoch_ms bigint,
    next_attempt_epoch_ms bigint
);

CREATE TABLE post_close_recovery_trigger (
    instance_id varchar(64) NOT NULL REFERENCES business_instance(instance_id),
    trigger_id varchar(256) NOT NULL,
    attempt_no integer NOT NULL CHECK(attempt_no > 0),
    claimed_epoch_ms bigint NOT NULL,
    PRIMARY KEY(instance_id, trigger_id),
    UNIQUE(instance_id, attempt_no)
);
