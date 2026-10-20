package com.zoutrankil.data.l2live.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.l2live.bootstrap.L2LiveProcessor;
import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class ForcedCrashRecoveryTest {
    @TempDir Path root;

    @Test
    void forceKillAfterPublicationReplaysFrozenBatchExactlyOnce() throws Exception {
        var classpath = new LinkedHashSet<String>();
        for (ClassLoader loader = getClass().getClassLoader();
                loader != null;
                loader = loader.getParent())
            if (loader instanceof URLClassLoader urls)
                for (var url : urls.getURLs()) classpath.add(Path.of(url.toURI()).toString());
        // Gradle 的隔离测试加载器不保证是 URLClassLoader，从实际类和 JAR 资源补齐子进程路径。
        classpath.add(
                Path.of(
                                CrashChild.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString());
        classpath.add(
                Path.of(
                                RawJournal.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString());
        var manifests = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements())
            if (manifests.nextElement().openConnection() instanceof JarURLConnection jar)
                classpath.add(Path.of(jar.getJarFileURL().toURI()).toString());
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                String.join(File.pathSeparator, classpath),
                                CrashChild.class.getName(),
                                root.toString())
                        .redirectError(root.resolve("child-stderr.log").toFile())
                        .start();
        try {
            var ready =
                    CompletableFuture.supplyAsync(
                                    () -> {
                                        try (var reader =
                                                new BufferedReader(
                                                        new InputStreamReader(
                                                                process.getInputStream()))) {
                                            for (String line; (line = reader.readLine()) != null; )
                                                if (line.startsWith("READY "))
                                                    return line.substring(6);
                                            throw new IllegalStateException(
                                                    "子进程未到达发布点："
                                                            + Files.readString(
                                                                    root.resolve(
                                                                            "child-stderr.log")));
                                        } catch (Exception e) {
                                            throw new CompletionException(e);
                                        }
                                    })
                            .get(30, TimeUnit.SECONDS);
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            try (var journal = new RawJournal(root)) {
                assertEquals(1, journal.status().pending());
                assertEquals(ready, journal.preparedBatch().orElseThrow().id());
                var frozen = journal.preparedBatch().orElseThrow().accumulatorState();
                var accumulator =
                        new L2MinuteAccumulator(Duration.ofSeconds(2), 10, Clock.systemUTC());
                var replay =
                        new L2LiveProcessor(
                                journal,
                                new LocalLiveSink(journal),
                                new AliyunL2WireParser(),
                                accumulator,
                                200);
                assertEquals(1, replay.processOnce());
                assertEquals(0, replay.processOnce());
                assertEquals(1, journal.status().completed());
                assertEquals(frozen, journal.committedAccumulatorState());
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    public static class CrashChild {
        public static void main(String[] args) throws Exception {
            try (var journal = new RawJournal(Path.of(args[0]))) {
                var event =
                        ParquetPersistenceTest.append(
                                journal,
                                "trans/510500",
                                "510500.SH,B,93000001,1,15460,1000,100,101");
                var accumulator =
                        new L2MinuteAccumulator(Duration.ofSeconds(2), 10, Clock.systemUTC());
                var state = accumulator.accept(event).stream().toList();
                var batch = journal.prepareBatch(List.of(event), state, accumulator.snapshot());
                new LocalLiveSink(journal).write(batch.events(), batch.states());
                System.out.println("READY " + batch.id());
                System.out.flush();
                new CountDownLatch(1).await();
            }
        }
    }
}
