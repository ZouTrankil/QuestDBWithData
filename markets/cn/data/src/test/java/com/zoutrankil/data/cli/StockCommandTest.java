package com.zoutrankil.data.cli;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockCommandTest {
    @Test void publishedNamesRemainExactAndHelpExplainsEveryAction() {
        var family = new StockCommands(null, null, null, null, null, null, null, null);
        assertEquals(20, family.commands().size());
        for (var command : StockCommand.values()) {
            assertTrue(family.commands().contains(command.command()));
            assertSame(command, StockCommand.require(command.command()));
            assertTrue(CliUsage.text().contains(command.command() + "：" + command.desc()));
        }
        assertThrows(UnsupportedOperationException.class, () -> family.commands().clear());
    }

    @Test void unknownNamesAndMissingScopeNeverReachASyncService() {
        var family = new StockCommands(null, null, null, null, null, null, null, null);
        for (String name : new String[]{"unknown", "RUN_DAILY_JOB", "RUN-DAILY-JOB"}) {
            assertEquals(CliUsage.text(), assertThrows(IllegalArgumentException.class,
                    () -> family.execute(name, Map.of())).getMessage());
        }
        for (var command : StockCommand.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> family.execute(command.command(), Map.of()), command.command());
        }
    }
}
