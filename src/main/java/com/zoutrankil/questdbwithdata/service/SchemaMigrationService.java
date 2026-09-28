package com.zoutrankil.questdbwithdata.service;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** Runs migrations once per CLI process, before database business operations. */
@Service
public class SchemaMigrationService {
    private final Flyway flyway;
    private boolean migrated;

    public SchemaMigrationService(@Lazy Flyway flyway) {
        this.flyway = flyway;
    }

    public synchronized void migrate() {
        if (!migrated) {
            flyway.migrate();
            migrated = true;
        }
    }
}
