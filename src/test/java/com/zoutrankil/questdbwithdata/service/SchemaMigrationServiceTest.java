package com.zoutrankil.questdbwithdata.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchemaMigrationServiceTest {
    @Test
    void successfulMigrationRunsOncePerProcess() {
        Flyway flyway = mock(Flyway.class);
        var service = new SchemaMigrationService(flyway);
        service.migrate();
        service.migrate();
        verify(flyway, times(1)).migrate();
    }

    @Test
    void failurePropagatesAndDoesNotMarkSchemaAsReady() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenThrow(new FlywayException("migration failed"))
                .thenReturn(mock(MigrateResult.class));
        var service = new SchemaMigrationService(flyway);
        assertThrows(FlywayException.class, service::migrate);
        assertDoesNotThrow(service::migrate);
        verify(flyway, times(2)).migrate();
    }
}
