package com.zoutrankil.data.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.strategy.input.StrategyInputSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

class MainStrategyConfiguredProtocolTest {
    @TempDir Path root;
    private String sha(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private MainStrategyDataService service(JdbcTemplate jdbc, Path coverage, String hash) {
        return new MainStrategyDataService(jdbc,root.resolve("artifacts").toString(),root.resolve("recipe.json").toString(),root.resolve("pins.json").toString(),root.toString(),root.resolve("plans.sqlite3").toString(),"2025-07-01",10000,coverage.toString(),hash);
    }
    @Test
    void protocolSelectionUsesExactApprovedBytesWithoutDatabaseOrArtifactWrites() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        Path file=Files.writeString(root.resolve("header.json"),"{\"schema\":\"java.strategy.source_coverage.v2\",\"inputSchema\":\"java.strategy.inputs.v2\",\"keyEncoding\":\"java.strategy.source_keys.v2\"}");
        var service=service(jdbc,file,sha(file));
        assertEquals(StrategyInputSchema.V2,service.configuredInputSchema());
        Files.writeString(file,"{\"schema\":\"java.strategy.source_coverage.v1\"}");
        assertThrows(IllegalStateException.class,service::configuredInputSchema);
        assertFalse(Files.exists(root.resolve("artifacts"))); verifyNoInteractions(jdbc);
    }
    @Test
    void approvedAssemblyHeaderTakesPrecedenceButIncompleteOrUnknownHeadersFail() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var service=service(jdbc,root.resolve("missing"),"0".repeat(64));
        Path file=Files.writeString(root.resolve("approved.json"),"{\"schema\":\"java.strategy.coverage_inputs.v1\"}");
        service.configureCoverageAssembly(file.toString(),sha(file));
        assertEquals(StrategyInputSchema.V1,service.configuredInputSchema());
        for(String header: new String[]{"{\"schema\":\"java.strategy.coverage_inputs.v2\"}","{\"schema\":\"java.strategy.coverage_inputs.v9\"}"}) {
            Files.writeString(file,header); service.configureCoverageAssembly(file.toString(),sha(file));
            assertThrows(IllegalStateException.class,service::configuredInputSchema);
        }
        verifyNoInteractions(jdbc);
    }
}
