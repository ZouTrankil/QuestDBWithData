package com.zoutrankil.data.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CliProcessTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temp;
    private int run(String name, String... args) throws Exception {
        var command = new ArrayList<String>();
        String javaExecutable=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";
        command.add(Path.of(System.getProperty("java.home"),"bin",javaExecutable).toString());
        command.addAll(List.of("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
        command.add("-cp"); command.add(System.getProperty("cli.runtimeClasspath"));
        command.add("com.zoutrankil.data.QuestDataApplication");
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectError(temp.resolve(name+".err").toFile())
                .redirectOutput(temp.resolve(name+".log").toFile());
        builder.environment().put("APP_SYNC_LEDGER_PATH",temp.resolve(name+".sqlite").toString());
        builder.environment().put("SPRING_MAIN_BANNER_MODE","off");
        builder.environment().put("LOGGING_LEVEL_ROOT","ERROR");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(45,TimeUnit.SECONDS),"CLI process did not terminate within its test deadline");
            System.out.println("Process diagnostic log: " + temp.resolve(name+".log"));
            return process.exitValue();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    @Test void actualMainExitsZeroForPlanAndTwoForBadInput() throws Exception {
        Path parameters = Files.writeString(temp.resolve("parameters.json"),"{\"codes\":[\"000001.SZ\"]}");
        assertEquals(0,run("plan","plan-sync-job","--job","data.stock_basic","--version","2",
                "--logical-date","2026-09-29","--parameters-file",parameters.toString()));
        String planned = Files.readString(temp.resolve("plan.log"));
        assertTrue(planned.contains("\"PLANNED\""));
        assertTrue(planned.contains("\"executed\" : false"));
        assertEquals("PLANNED",new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(planned).get("status").textValue());
        assertFalse(Files.exists(temp.resolve("plan.sqlite")));
        assertEquals(2,run("bad","plan-sync-job","--job","data.stock_basic","--version","2",
                "--logical-date","bad-date","--parameters-file",parameters.toString()));
        assertTrue(Files.readString(temp.resolve("bad.err")).contains("\"exitCode\":2"));
        assertEquals("",Files.readString(temp.resolve("bad.log")));
        assertFalse(Files.exists(temp.resolve("bad.sqlite")));
    }
    @Test void actualMainDistinguishesRuntimeFailureFromBlockedScheduleWithoutSourceCalls() throws Exception {
        assertEquals(1,run("missing","show-sync-history","--ledger",temp.resolve("absent.sqlite").toString()));
        assertTrue(Files.readString(temp.resolve("missing.err")).contains("\"exitCode\":1"));
        assertFalse(Files.exists(temp.resolve("absent.sqlite")));
        var store = new com.zoutrankil.data.repository.SyncScheduleStore(temp.resolve("blocked.sqlite"));
        var definition = new com.zoutrankil.data.domain.SyncScheduleDefinition("blocked.schedule",
                com.zoutrankil.data.domain.SyncScheduleDefinition.Target.JOB,"data.stock_basic",2,true,
                java.time.ZoneOffset.UTC,com.zoutrankil.data.domain.SyncScheduleDefinition.Kind.DAILY,
                java.time.LocalTime.MIDNIGHT,Set.of(),
                com.zoutrankil.data.domain.SyncScheduleDefinition.DayRule.CALENDAR,
                com.zoutrankil.data.domain.SyncScheduleDefinition.Misfire.RUN_ONCE,
                java.time.Duration.ofDays(1),Map.of("codes","000001.SZ"));
        store.put(definition);
        var prior = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        assertTrue(store.claim(definition.scheduleId(),prior,
                com.zoutrankil.data.repository.SyncScheduleStore.State.CLAIMED,"unresolved old run"));
        assertEquals(3,run("blocked","schedule-tick"));
        assertTrue(Files.readString(temp.resolve("blocked.err")).contains("\"exitCode\":3"));
        var output = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(temp.resolve("blocked.log")));
        assertEquals("SKIPPED_REENTRY",output.get(0).get("state").textValue());
        assertTrue(output.get(0).get("runId").isNull());
    }
    @Test void wrappedIncompleteOutcomesStayDistinctFromRuntimeAndInputFailures() {
        assertEquals(3,CliExitStatus.failureCode(new RuntimeException(new IncompleteCommandException("IN_DOUBT"))));
        assertEquals(2,CliExitStatus.failureCode(new RuntimeException(new IllegalArgumentException("bad input"))));
        assertEquals(1,CliExitStatus.failureCode(new RuntimeException(new java.io.IOException("unavailable"))));
        assertEquals(1,CliExitStatus.failureCode(new IllegalStateException("startup failed")));
    }
}
