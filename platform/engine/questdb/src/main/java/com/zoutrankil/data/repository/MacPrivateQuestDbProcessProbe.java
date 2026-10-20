package com.zoutrankil.data.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** macOS 用真实监听归属与 JDK 进程参数交叉核验，不解析 shell 拼接的命令行。 */
final class MacPrivateQuestDbProcessProbe {
    record Identity(long pid, Instant startedAt, String executable, List<String> arguments) {
        Identity {
            arguments = List.copyOf(arguments);
        }
    }

    record Listeners(long pid, List<String> addresses) {
        Listeners {
            addresses = List.copyOf(addresses);
        }
    }

    @FunctionalInterface
    interface CommandStarter {
        Process start(List<String> command) throws IOException;
    }

    @FunctionalInterface
    interface ProcessInspector {
        Identity inspect(long pid) throws IOException;
    }

    private final CommandStarter commandStarter;
    private final ProcessInspector inspector;
    private Identity admitted;

    MacPrivateQuestDbProcessProbe() {
        this(
                command -> new ProcessBuilder(command).redirectErrorStream(true).start(),
                MacPrivateQuestDbProcessProbe::inspectSystemProcess);
    }

    MacPrivateQuestDbProcessProbe(CommandStarter commandStarter, ProcessInspector inspector) {
        this.commandStarter = Objects.requireNonNull(commandStarter);
        this.inspector = Objects.requireNonNull(inspector);
    }

    /** 连续两次观察一致后才固定身份，PID 复用也由进程启动时刻区分。 */
    void verify(PrivateQuestDbInstanceAttestor.Spec spec, Path root)
            throws IOException, InterruptedException {
        Identity first = observe(spec, root);
        Identity second = observe(spec, root);
        if (!first.equals(second) || admitted != null && !admitted.equals(first))
            throw new IllegalStateException("私有 QuestDB 监听进程或启动代际发生变化");
        admitted = first;
    }

    private Identity observe(PrivateQuestDbInstanceAttestor.Spec spec, Path root)
            throws IOException, InterruptedException {
        if (spec.pgPort() < 1
                || spec.pgPort() > 65535
                || spec.qwpPort() < 1
                || spec.qwpPort() > 65535
                || spec.pgPort() == spec.qwpPort()) throw new IllegalStateException("私有监听端口不合法");
        var command =
                List.of(
                        "/usr/sbin/lsof",
                        "-nP",
                        "-a",
                        "-iTCP:" + spec.pgPort(),
                        "-iTCP:" + spec.qwpPort(),
                        "-sTCP:LISTEN",
                        "-Fpn");
        var process = commandStarter.start(command);
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("私有监听进程核验超时");
            }
        } catch (InterruptedException cancelled) {
            process.destroyForcibly();
            throw cancelled;
        }
        byte[] bytes = process.getInputStream().readNBytes(32769);
        if (process.exitValue() != 0 || bytes.length > 32768)
            throw new IllegalStateException("私有监听进程输出缺失或超过预算");
        var listeners = parseListeners(new String(bytes, StandardCharsets.UTF_8));
        if (listeners.addresses().size() != 2
                || !listeners.addresses().contains("127.0.0.1:" + spec.pgPort())
                || !listeners.addresses().contains("127.0.0.1:" + spec.qwpPort()))
            throw new IllegalStateException("私有双端口未由同一进程独占 loopback 监听");
        var identity = inspector.inspect(listeners.pid());
        if (identity == null
                || identity.pid() != listeners.pid()
                || identity.startedAt() == null
                || identity.executable() == null
                || !Path.of(identity.executable()).getFileName().toString().equals("java"))
            throw new IllegalStateException("私有监听缺少真实 Java 进程与启动时刻");
        var args = serverArguments(identity.arguments());
        int found = -1;
        for (int i = 0; i < args.size(); i++)
            if (args.get(i).equals("-d")) {
                if (found >= 0 || i + 1 == args.size() || args.get(i + 1).isBlank())
                    throw new IllegalStateException("私有进程没有唯一明确的数据目录参数");
                found = i + 1;
            }
        // 相对 -d 会相对另一个进程的 cwd 解析，不能拿当前 JVM 的目录猜归属。
        if (found < 0
                || !Path.of(args.get(found)).isAbsolute()
                || !root.equals(Path.of(args.get(found)).toRealPath()))
            throw new IllegalStateException("私有进程的数据目录与已核验真实目录不同");
        return identity;
    }

    /** 只接明确主类的启动方式；业务参数里夹带同名字符串不能冒充 JVM 主类。 */
    private static List<String> serverArguments(List<String> args) {
        var paired =
                Set.of(
                        "-p",
                        "--module-path",
                        "-cp",
                        "-classpath",
                        "--class-path",
                        "--add-modules",
                        "--limit-modules",
                        "--add-exports",
                        "--add-opens",
                        "--add-reads",
                        "--enable-native-access",
                        "--upgrade-module-path");
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-m") || arg.equals("--module")) {
                if (i + 1 < args.size()
                        && args.get(i + 1).equals("io.questdb/io.questdb.ServerMain"))
                    return args.subList(i + 2, args.size());
                break;
            }
            if (arg.startsWith("--module=")) {
                if (arg.equals("--module=io.questdb/io.questdb.ServerMain"))
                    return args.subList(i + 1, args.size());
                break;
            }
            if (paired.contains(arg)) {
                if (++i == args.size() || args.get(i).isBlank()) break;
            } else if (arg.startsWith("-D") && arg.length() > 2
                    || arg.startsWith("-X")
                    || Set.of(
                                    "--enable-preview",
                                    "--sun-misc-unsafe-memory-access=allow",
                                    "-ea",
                                    "-da",
                                    "-esa",
                                    "-dsa",
                                    "-server")
                            .contains(arg)
                    || arg.startsWith("-ea:")
                    || arg.startsWith("-da:")
                    || arg.startsWith("--")
                            && arg.contains("=")
                            && paired.contains(arg.substring(0, arg.indexOf('=')))) {
                // 这些是已知 JVM 选项；未知选项、argfile 和 jar manifest 不猜入口。
            } else {
                if (arg.equals("io.questdb.ServerMain")) return args.subList(i + 1, args.size());
                break;
            }
        }
        throw new IllegalStateException("私有监听进程不是明确的 QuestDB 启动类");
    }

    static Listeners parseListeners(String output) {
        long pid = -1;
        var addresses = new ArrayList<String>();
        for (String line : output.split("\n")) {
            if (line.isEmpty()) continue;
            if (line.charAt(0) == 'p'
                    && pid == -1
                    && line.substring(1).matches("[1-9][0-9]{0,18}")) {
                try {
                    pid = Long.parseLong(line.substring(1));
                } catch (NumberFormatException overflow) {
                    throw new IllegalStateException("监听 PID 超出范围", overflow);
                }
            } else if (line.charAt(0) == 'n'
                    && pid > 0
                    && line.substring(1).matches("127\\.0\\.0\\.1:[0-9]{1,5}")
                    && !addresses.contains(line.substring(1))) {
                addresses.add(line.substring(1));
            } else if (line.charAt(0) == 'f' && pid > 0 && line.substring(1).matches("[0-9]+")) {
                // lsof 的字段格式可带 fd 编号；真正的归属取 p 与 n。
            } else throw new IllegalStateException("监听输出含未知、重复或非 loopback 记录");
        }
        if (pid < 1 || addresses.size() != 2) throw new IllegalStateException("私有监听输出不是唯一进程的双端口");
        return new Listeners(pid, addresses);
    }

    private static Identity inspectSystemProcess(long pid) throws IOException {
        var handle = ProcessHandle.of(pid).orElseThrow(() -> new IOException("监听进程已退出"));
        var info = handle.info();
        var result =
                new Identity(
                        pid,
                        info.startInstant().orElseThrow(() -> new IOException("未知进程启动时刻")),
                        info.command().orElseThrow(() -> new IOException("未知进程可执行文件")),
                        Arrays.asList(
                                info.arguments().orElseThrow(() -> new IOException("未知进程原始参数"))));
        if (!handle.isAlive()) throw new IOException("读取元数据期间进程已退出");
        return result;
    }
}
