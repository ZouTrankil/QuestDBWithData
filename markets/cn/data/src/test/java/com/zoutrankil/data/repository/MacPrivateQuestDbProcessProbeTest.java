package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 检查拒绝错目录、伪入口、监听歧义和进程换代；真实本机探针另测非 QuestDB 进程拒收。 */
class MacPrivateQuestDbProcessProbeTest {
    @TempDir Path temporary;
    static final Instant START = Instant.parse("2026-10-20T00:00:00Z");
    static final String LISTENERS = "p42\nf7\nn127.0.0.1:18822\nf8\nn127.0.0.1:19010\n";

    @ParameterizedTest
    @ValueSource(strings = {"D095", "D098"})
    void nativeProbeRepeatsBothSystemChecksAndPinsTheFullProcessGeneration(String code)
            throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root with spaces")).toRealPath();
        int pg = code.equals("D095") ? 18812 : 18822;
        int qwp = code.equals("D095") ? 19000 : 19010;
        var selected =
                new PrivateQuestDbInstanceAttestor.Spec(code, root.getParent(), root, pg, qwp);
        var commands = new ArrayList<List<String>>();
        var identities = new ArrayList<Long>();
        var probe =
                new MacPrivateQuestDbProcessProbe(
                        command -> {
                            commands.add(command);
                            return process(
                                    LISTENERS
                                            .replace("18822", Integer.toString(pg))
                                            .replace("19010", Integer.toString(qwp)));
                        },
                        pid -> {
                            identities.add(pid);
                            return identity(root);
                        });
        probe.verify(selected, root);
        probe.verify(selected, root);
        assertEquals(4, commands.size());
        assertTrue(
                commands.stream()
                        .allMatch(
                                c ->
                                        c.equals(
                                                List.of(
                                                        "/usr/sbin/lsof",
                                                        "-nP",
                                                        "-a",
                                                        "-iTCP:" + pg,
                                                        "-iTCP:" + qwp,
                                                        "-sTCP:LISTEN",
                                                        "-Fpn"))));
        assertEquals(List.of(42L, 42L, 42L, 42L), identities);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "p42\nn127.0.0.1:18822",
                "p42\nn*:18822\nn127.0.0.1:19010",
                "p42\nn[::1]:18822\nn127.0.0.1:19010",
                "p42\nn127.0.0.1:18822\np43\nn127.0.0.1:19010",
                "p42\nn127.0.0.1:18822\nn127.0.0.1:18822",
                "p42\nn127.0.0.1:18822\nn127.0.0.1:19010\nunknown",
                "p9223372036854775808\nn127.0.0.1:18822\nn127.0.0.1:19010"
            })
    void ambiguousOrNonLoopbackListenersCannotReachProcessAdmission(String output) {
        assertThrows(
                IllegalStateException.class,
                () -> MacPrivateQuestDbProcessProbe.parseListeners(output));
    }

    @Test
    void wrongPortCannotBeAcceptedJustBecauseBothListenersAreLocal() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root")).toRealPath();
        var probe =
                new MacPrivateQuestDbProcessProbe(
                        command -> process(LISTENERS.replace("19010", "19011")),
                        pid -> {
                            fail("错端口不能进入进程准入");
                            return null;
                        });
        assertThrows(IllegalStateException.class, () -> probe.verify(spec(root), root));
    }

    @Test
    void reusedPidAndMidObservationChangesBothFail() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root")).toRealPath();
        var n = new AtomicInteger();
        var changed =
                new MacPrivateQuestDbProcessProbe(
                        command -> process(LISTENERS),
                        pid ->
                                new MacPrivateQuestDbProcessProbe.Identity(
                                        42,
                                        START.plusSeconds(n.getAndIncrement()),
                                        "/jdk/bin/java",
                                        identity(root).arguments()));
        assertThrows(IllegalStateException.class, () -> changed.verify(spec(root), root));
        n.set(0);
        var reused =
                new MacPrivateQuestDbProcessProbe(
                        command -> process(LISTENERS),
                        pid ->
                                new MacPrivateQuestDbProcessProbe.Identity(
                                        42,
                                        START.plusSeconds(n.get()),
                                        "/jdk/bin/java",
                                        identity(root).arguments()));
        reused.verify(spec(root), root);
        n.incrementAndGet();
        assertThrows(IllegalStateException.class, () -> reused.verify(spec(root), root));
    }

    @Test
    void metadataRequiresExactPidJavaExecutableStartTimeAndExplicitQuestDbMain() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root")).toRealPath();
        var good = identity(root);
        var variants =
                List.of(
                        new MacPrivateQuestDbProcessProbe.Identity(
                                43, START, "/jdk/bin/java", good.arguments()),
                        new MacPrivateQuestDbProcessProbe.Identity(
                                42, null, "/jdk/bin/java", good.arguments()),
                        new MacPrivateQuestDbProcessProbe.Identity(
                                42, START, "/bin/python", good.arguments()),
                        new MacPrivateQuestDbProcessProbe.Identity(
                                42,
                                START,
                                "/jdk/bin/java",
                                List.of(
                                        "other.Main",
                                        "io.questdb.ServerMain",
                                        "-d",
                                        root.toString())),
                        new MacPrivateQuestDbProcessProbe.Identity(
                                42,
                                START,
                                "/jdk/bin/java",
                                List.of(
                                        "-jar",
                                        "unverified.jar",
                                        "io.questdb.ServerMain",
                                        "-d",
                                        root.toString())));
        for (var variant : variants) {
            var probe =
                    new MacPrivateQuestDbProcessProbe(
                            command -> process(LISTENERS), pid -> variant);
            assertThrows(IllegalStateException.class, () -> probe.verify(spec(root), root));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"class", "module", "moduleEquals"})
    void jvmOptionsAndExplicitMainAreParsedBeforeTheServerArguments(String launch)
            throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root with spaces")).toRealPath();
        var args =
                new ArrayList<>(
                        List.of(
                                "--sun-misc-unsafe-memory-access=allow",
                                "-Xmx256m",
                                "-Dfixture=true",
                                "--add-opens=java.base/java.nio=io.questdb",
                                "-p",
                                "/module path"));
        args.addAll(
                switch (launch) {
                    case "class" -> List.of("io.questdb.ServerMain");
                    case "module" -> List.of("-m", "io.questdb/io.questdb.ServerMain");
                    default -> List.of("--module=io.questdb/io.questdb.ServerMain");
                });
        args.addAll(List.of("-d", root.toString()));
        var probe =
                new MacPrivateQuestDbProcessProbe(
                        command -> process(LISTENERS),
                        pid ->
                                new MacPrivateQuestDbProcessProbe.Identity(
                                        42, START, "/jdk/bin/java", args));
        assertDoesNotThrow(() -> probe.verify(spec(root), root));
    }

    @Test
    void rootsMustBeUniqueAbsoluteAndResolveToTheOriginalDirectory() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root")).toRealPath();
        Path other = Files.createDirectory(temporary.resolve("other"));
        var variants =
                List.of(
                        List.of("-d", "root"),
                        List.of("-d", other.toString()),
                        List.of("-d", root.toString(), "-d", root.toString()),
                        List.of("-d"),
                        List.<String>of());
        for (var variant : variants) {
            var args = new ArrayList<>(List.of("io.questdb.ServerMain"));
            args.addAll(variant);
            var probe =
                    new MacPrivateQuestDbProcessProbe(
                            command -> process(LISTENERS),
                            pid ->
                                    new MacPrivateQuestDbProcessProbe.Identity(
                                            42, START, "/jdk/bin/java", args));
            assertThrows(IllegalStateException.class, () -> probe.verify(spec(root), root));
        }
        Path alias = temporary.resolve("alias");
        Files.createSymbolicLink(alias, root);
        var probe =
                new MacPrivateQuestDbProcessProbe(
                        command -> process(LISTENERS),
                        pid ->
                                new MacPrivateQuestDbProcessProbe.Identity(
                                        42,
                                        START,
                                        "/jdk/bin/java",
                                        List.of("io.questdb.ServerMain", "-d", alias.toString())));
        assertDoesNotThrow(() -> probe.verify(spec(root), root));
    }

    @Test
    void missingFailedOversizedTimedOutAndInterruptedSystemCallsAreRejected() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root")).toRealPath();
        for (int kind = 0; kind < 4; kind++) {
            var process = process(kind == 1 ? "x".repeat(32769) : LISTENERS);
            if (kind == 0) when(process.exitValue()).thenReturn(1);
            if (kind == 2) when(process.waitFor(10, TimeUnit.SECONDS)).thenReturn(false);
            if (kind == 3)
                when(process.waitFor(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
            var probe =
                    new MacPrivateQuestDbProcessProbe(command -> process, pid -> identity(root));
            if (kind == 3)
                assertThrows(InterruptedException.class, () -> probe.verify(spec(root), root));
            else assertThrows(IllegalStateException.class, () -> probe.verify(spec(root), root));
            if (kind >= 2) verify(process).destroyForcibly();
        }
    }

    @Test
    void nativePathStillRunsFixtureAndConfigurationBeforeAnyProcessProbe() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("var"));
        Path root = Files.createDirectory(workspace.resolve("root"));
        Files.createDirectory(root.resolve("conf"));
        var events = new ArrayList<String>();
        var owner =
                new PrivateQuestDbInstanceAttestor(
                        spec(root),
                        real -> events.add("fixture"),
                        () -> false,
                        script -> {
                            fail("macOS不能调用PowerShell");
                            return null;
                        },
                        (selected, real) -> events.add("process"));
        Files.writeString(
                root.resolve("conf/server.conf"),
                "http.net.bind.to=0.0.0.0:19010\npg.net.bind.to=127.0.0.1:18822\n");
        assertThrows(IllegalStateException.class, owner::attest);
        assertEquals(List.of("fixture"), events);
        Files.writeString(
                root.resolve("conf/server.conf"),
                "http.net.bind.to=127.0.0.1:19010\npg.net.bind.to=127.0.0.1:18822\n");
        assertDoesNotThrow(owner::attest);
        assertEquals(List.of("fixture", "fixture", "process"), events);
        var rejected =
                new PrivateQuestDbInstanceAttestor(
                        spec(root),
                        real -> {
                            throw new IOException("fixture");
                        },
                        () -> false,
                        script -> {
                            fail();
                            return null;
                        },
                        (selected, real) -> fail("fixture不符不能继续"));
        assertThrows(IllegalStateException.class, rejected::attest);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void realLsofAndProcessHandleRejectARealJavaListenerWithoutQuestDbMain() throws Exception {
        try (var first = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var second = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Path root = Files.createDirectory(temporary.resolve("root")).toRealPath();
            var spec =
                    new PrivateQuestDbInstanceAttestor.Spec(
                            "D098", temporary, root, first.getLocalPort(), second.getLocalPort());
            var error =
                    assertThrows(
                            IllegalStateException.class,
                            () -> new MacPrivateQuestDbProcessProbe().verify(spec, root));
            assertTrue(error.getMessage().contains("启动类"));
        }
    }

    private PrivateQuestDbInstanceAttestor.Spec spec(Path root) {
        return new PrivateQuestDbInstanceAttestor.Spec(
                "D098", root.getParent(), root, 18822, 19010);
    }

    private static MacPrivateQuestDbProcessProbe.Identity identity(Path root) {
        return new MacPrivateQuestDbProcessProbe.Identity(
                42,
                START,
                "/jdk/bin/java",
                List.of("-m", "io.questdb/io.questdb.ServerMain", "-d", root.toString()));
    }

    private static Process process(String output) {
        var process = mock(Process.class);
        try {
            when(process.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);
        } catch (InterruptedException impossible) {
            throw new AssertionError(impossible);
        }
        when(process.exitValue()).thenReturn(0);
        when(process.getInputStream())
                .thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        return process;
    }
}
