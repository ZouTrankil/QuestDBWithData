package com.zoutrankil.data.bootstrap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StartupArgumentsTest {
    @Test void springOptionsBeforeAndAfterTheCommandDoNotReachBusinessParsing() {
        var parsed = StartupArguments.parse("--spring.profiles.active=offline", "plan-sync-job", "--job",
                "data.stock_basic", "--app.web.enabled=true", "--version=2", "--logging.level.root=ERROR");
        assertArrayEquals(new String[]{"plan-sync-job", "--job", "data.stock_basic", "--version=2"}, parsed.commandArray());
        assertFalse(parsed.web());
        assertTrue(parsed.hasCommand());
    }

    @Test void configurationOnlyArgumentsAreNotACommand() {
        var parsed = StartupArguments.parse("--server.port=0", "--spring.profiles.active=web", "--debug");
        assertFalse(parsed.hasCommand());
        assertFalse(parsed.web());
    }

    @Test void ambiguousStartupArgumentsAreRejected() {
        for (String[] args : new String[][]{
                {"--spring.profiles.active", "offline"}, {"--web=false"}, {"--web", "--web"},
                {"--web", "show-sync-job"}, {"--job", "data.stock_basic"}})
            assertThrows(IllegalArgumentException.class, () -> StartupArguments.parse(args));
    }
}
