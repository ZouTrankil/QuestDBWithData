package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Binds one operation to a checked local data root and one Windows QuestDB process. */
final class PrivateQuestDbInstanceAttestor {
    private static final ObjectReader JSON_READER = new ObjectMapper().reader();
    private static final Pattern DATA_ROOT_ARGUMENT =
            Pattern.compile("(?:^|\\s)-d\\s+(?:\"([^\"]+)\"|(\\S+))(?=\\s|$)");
    private static final RootCheck NO_ROOT_CHECK = root -> {};

    record Spec(String datasetCode, Path workspaceVar, Path dataRoot, int pgPort, int qwpPort) {
        Spec {
            Objects.requireNonNull(datasetCode);
            Objects.requireNonNull(workspaceVar);
            Objects.requireNonNull(dataRoot);
        }
    }

    @FunctionalInterface
    interface RootCheck {
        void verify(Path realRoot) throws IOException;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(String script) throws IOException;
    }

    private final Spec spec;
    private final RootCheck rootCheck;
    private final BooleanSupplier windows;
    private final ProcessStarter processStarter;
    private Long privateProcessId;

    PrivateQuestDbInstanceAttestor(Spec spec, RootCheck rootCheck) {
        this(spec, rootCheck,
                () -> System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                PrivateQuestDbInstanceAttestor::startPowerShell);
    }

    /** Package-local seam for deterministic tests; production always uses the system process checks. */
    PrivateQuestDbInstanceAttestor(Spec spec, RootCheck rootCheck, BooleanSupplier windows,
                                 ProcessStarter processStarter) {
        this.spec = Objects.requireNonNull(spec);
        this.rootCheck = rootCheck == null ? NO_ROOT_CHECK : rootCheck;
        this.windows = Objects.requireNonNull(windows);
        this.processStarter = Objects.requireNonNull(processStarter);
    }

    void attest() {
        String code = spec.datasetCode();
        if (!windows.getAsBoolean())
            throw new IllegalStateException(code + " implicit private-instance admission requires Windows process attestation");
        try {
            Path root = spec.dataRoot().toAbsolutePath().normalize().toRealPath();
            Path workspaceVar = spec.workspaceVar().toAbsolutePath().normalize().toRealPath();
            if (!root.startsWith(workspaceVar))
                throw new IllegalStateException(code + " private data root is outside workspace var");
            rootCheck.verify(root);
            var config = new LinkedHashMap<String, String>();
            for (String line : Files.readAllLines(root.resolve("conf/server.conf"), StandardCharsets.UTF_8)) {
                String text = line.replace("\uFEFF", "").trim();
                int equal = text.indexOf('=');
                if (equal > 0 && !text.startsWith("#"))
                    config.put(text.substring(0, equal).trim(), text.substring(equal + 1).trim());
            }
            if (!("127.0.0.1:" + spec.qwpPort()).equals(config.get("http.net.bind.to"))
                    || !("127.0.0.1:" + spec.pgPort()).equals(config.get("pg.net.bind.to")))
                throw new IllegalStateException(code + " private data root does not bind the expected loopback listeners");
            String script = String.format(Locale.ROOT, """
                    $ErrorActionPreference='Stop'
                    $listeners=@(Get-NetTCPConnection -LocalPort %d,%d -State Listen -ErrorAction Stop)
                    $records=@(foreach($port in @(%d,%d)) {
                      $matches=@($listeners | Where-Object LocalPort -eq $port)
                      if($matches.Count -ne 1) { throw 'Ambiguous private listener' }
                      $listener=$matches[0]
                      $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
                      [pscustomobject]@{port=$port;address=$listener.LocalAddress;pid=$proc.ProcessId;name=$proc.Name;command=$proc.CommandLine}
                    })
                    ConvertTo-Json -InputObject $records -Compress
                    """, spec.qwpPort(), spec.pgPort(), spec.qwpPort(), spec.pgPort());
            Process process = processStarter.start(script);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(code + " private listener attestation timed out");
            }
            byte[] output = process.getInputStream().readNBytes(32_769);
            if (process.exitValue() != 0 || output.length > 32_768)
                throw new IllegalStateException(code + " private listener attestation failed");
            var records = JSON_READER.readTree(output);
            if (!records.isArray() || records.size() != 2)
                throw new IllegalStateException(code + " private listeners differ");
            Long observedPid = null;
            var observedPorts = new HashSet<Integer>();
            for (var record : records) {
                long processId = record.path("pid").asLong(-1);
                String name = record.path("name").asText("").toLowerCase(Locale.ROOT);
                if (!"127.0.0.1".equals(record.path("address").asText()) || processId < 1
                        || !Set.of("questdb.exe", "java.exe").contains(name)
                        || observedPid != null && observedPid != processId
                        || !observedPorts.add(record.path("port").asInt(-1)))
                    throw new IllegalStateException(code + " private listeners do not belong to one loopback QuestDB process");
                var argument = DATA_ROOT_ARGUMENT.matcher(record.path("command").asText(""));
                if (!argument.find())
                    throw new IllegalStateException(code + " private process has no explicit data root");
                String directory = argument.group(1) == null ? argument.group(2) : argument.group(1);
                if (!root.equals(Path.of(directory).toAbsolutePath().normalize().toRealPath()) || argument.find())
                    throw new IllegalStateException(code + " private process belongs to a different data root");
                observedPid = processId;
            }
            if (!observedPorts.equals(Set.of(spec.qwpPort(), spec.pgPort()))
                    || privateProcessId != null && !privateProcessId.equals(observedPid))
                throw new IllegalStateException(code + " private QuestDB process changed during this operation");
            privateProcessId = observedPid;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new CancellationException(code + " private listener attestation cancelled");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot attest " + code + " private QuestDB data root and process", failure);
        }
    }

    private static Process startPowerShell(String script) throws IOException {
        Path powershell = Path.of(Objects.requireNonNull(System.getenv("SystemRoot")),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        return new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start();
    }
}
