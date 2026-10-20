package com.zoutrankil.data.strategy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.strategy.input.StrategyInputSchema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

class MainStrategyConfiguredProtocolTest {
    @TempDir Path root;

    private MainStrategyDataService service(DataSource source) {
        return new MainStrategyDataService(
                new JdbcTemplate(source),
                root.resolve("artifacts").toString(),
                root.resolve("recipe.json").toString(),
                root.resolve("pins.json").toString(),
                root.toString(),
                root.resolve("plans.sqlite3").toString(),
                "2025-07-01",
                10000);
    }

    @Test
    void defaultProtocolCanBeFrozenBeforeTheCurrentSourceApprovalExists() {
        var source = mock(DataSource.class);
        var service = service(source);
        assertEquals(StrategyInputSchema.V1, service.configuredInputSchema());
        service.configureBatchInputSchema("v2");
        assertEquals(StrategyInputSchema.V2, service.configuredInputSchema());
        assertFalse(Files.exists(root.resolve("artifacts")));
        assertFalse(Files.exists(root.resolve("recipe.json")));
        verifyNoInteractions(source);
    }

    @Test
    void unknownProtocolCannotReplaceTheExplicitSelection() {
        var source = mock(DataSource.class);
        var service = service(source);
        service.configureBatchInputSchema("v2");
        for (String invalid : new String[] {"v3", "V2", " v2", ""})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.configureBatchInputSchema(invalid));
        assertEquals(StrategyInputSchema.V2, service.configuredInputSchema());
        verifyNoInteractions(source);
    }
}
