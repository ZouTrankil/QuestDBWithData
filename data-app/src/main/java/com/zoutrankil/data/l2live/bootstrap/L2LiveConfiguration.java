package com.zoutrankil.data.l2live.bootstrap;

import com.zoutrankil.data.l2live.application.L2Subscriptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone collector configuration; no dotenv interpolation and no credentials in diagnostics. */
public final class L2LiveConfiguration {
    private static final Set<String> COMMANDS = Set.of("plan", "run", "replay", "status", "export");
    private static final Set<String> OPTIONS = Set.of("root", "credentials", "broker", "symbols", "overrides",
            "questdb", "seconds", "batch-size", "poll-ms", "visibility-ms", "lateness-ms", "retained-minutes", "wait-credentials");
    final String command;
    final Path root;
    final String brokerUri;
    final String username;
    final String password;
    final List<String> symbols;
    final String questDb;
    final String questDbAuthorization;
    final long seconds;
    final int batchSize;
    final Duration pollInterval;
    final Duration visibilityTimeout;
    final Duration lateness;
    final int retainedMinutes;
    final boolean waitForCredentials;

    private L2LiveConfiguration(String command, Map<String, String> options, Map<String, String> env)
            throws IOException {
        this.command = command;
        root = Path.of(options.getOrDefault("root", "var/l2-live")).toAbsolutePath().normalize();
        Map<String, String> values = credentialFile(Path.of(options.getOrDefault("credentials",
                root.resolve("credentials.env").toString())));
        for (var entry : env.entrySet()) if (!entry.getValue().isBlank()) values.put(entry.getKey(), entry.getValue());
        username = values.getOrDefault("ALIYUN_L2_USERNAME", "");
        password = values.getOrDefault("ALIYUN_L2_PASSWORD", "");
        String broker = options.getOrDefault("broker", values.getOrDefault("ALIYUN_L2_BROKER", "lv2.base32.cn"));
        String port = values.getOrDefault("ALIYUN_L2_PORT", "1883");
        brokerUri = broker.contains("://") ? broker : "tcp://" + broker + ":" + port;
        var parsedBroker = java.net.URI.create(brokerUri);
        if (!Set.of("tcp", "ssl").contains(parsedBroker.getScheme()) || parsedBroker.getHost() == null
                || parsedBroker.getUserInfo() != null || parsedBroker.getQuery() != null
                || parsedBroker.getFragment() != null || !Set.of("", "/").contains(parsedBroker.getPath()))
            throw new IllegalArgumentException("INVALID_BROKER_URI");
        questDb = options.getOrDefault("questdb", values.getOrDefault("L2_LIVE_QUESTDB_URL", "http://127.0.0.1:9000"));
        var db = java.net.URI.create(questDb);
        if (!Set.of("http", "https").contains(db.getScheme()) || db.getHost() == null
                || db.getUserInfo() != null || db.getQuery() != null || db.getFragment() != null)
            throw new IllegalArgumentException("INVALID_QUESTDB_URI");
        questDbAuthorization = values.getOrDefault("L2_LIVE_QUESTDB_AUTHORIZATION", "");
        seconds = bounded(options, "seconds", 0, 0, 86400 * 7);
        batchSize = (int) bounded(options, "batch-size", 100, 1, 200);
        pollInterval = Duration.ofMillis(bounded(options, "poll-ms", 100, 10, 10_000));
        visibilityTimeout = Duration.ofMillis(bounded(options, "visibility-ms", 10_000, 100, 60_000));
        lateness = Duration.ofMillis(bounded(options, "lateness-ms", 2_000, 0, 60_000));
        retainedMinutes = (int) bounded(options, "retained-minutes", 10, 2, 120);
        String wait = options.getOrDefault("wait-credentials", "false");
        if (!Set.of("true", "false").contains(wait)) throw new IllegalArgumentException("INVALID_WAIT_CREDENTIALS_OPTION");
        waitForCredentials = Boolean.parseBoolean(wait);
        symbols = List.copyOf(options.containsKey("symbols") ? symbols(options.get("symbols"))
                : L2Subscriptions.load(Path.of(options.getOrDefault("overrides",
                    "F:/repo/engine/back-monitor/config/trading/aliyun_l2_subscription_overrides.json")),
                    java.time.Instant.now()));
    }

    public static L2LiveConfiguration parse(String[] args, Map<String, String> env) throws IOException {
        String command = args.length == 0 ? "plan" : args[0];
        if (!COMMANDS.contains(command)) throw new IllegalArgumentException("UNKNOWN_COMMAND");
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length)
                throw new IllegalArgumentException("OPTION_REQUIRES_VALUE");
            String key = args[i].substring(2);
            if (!OPTIONS.contains(key) || options.putIfAbsent(key, args[++i]) != null)
                throw new IllegalArgumentException("UNKNOWN_OR_DUPLICATE_OPTION");
        }
        return new L2LiveConfiguration(command, options, env);
    }

    public void requireLiveCredentials() {
        if (username.isBlank() || password.isBlank()) throw new IllegalArgumentException("SUBSCRIPTION_CREDENTIALS_MISSING");
        if (username.toLowerCase(java.util.Locale.ROOT).startsWith("level2_test"))
            throw new IllegalArgumentException("PUBLIC_TEST_ACCOUNT_NOT_ALLOWED");
        if (symbols.isEmpty()) throw new IllegalArgumentException("NO_ACTIVE_SUBSCRIPTION_SYMBOLS");
    }

    public Map<String, Object> plan() {
        var result = new LinkedHashMap<String, Object>();
        result.put("producer", "java.aliyun_l2_live");
        result.put("broker", brokerUri);
        result.put("credentialsConfigured", !username.isBlank() && !password.isBlank());
        result.put("symbols", symbols);
        result.put("topics", symbols.stream().flatMap(symbol -> List.of("trans/", "order/", "market/").stream()
                .map(prefix -> prefix + symbol.substring(0, 6))).toList());
        result.put("rawRoot", root.toString());
        result.put("questDb", questDb);
        result.put("eventTable", "l2_live_events_v2");
        result.put("minuteTable", "l2_live_micro_state_1m_v2");
        result.put("qos", 0);
        result.put("singleAccountTerminal", true);
        result.put("businessSequenceScope", "unverified");
        result.put("liveConnected", false);
        return result;
    }

    private static Map<String, String> credentialFile(Path path) throws IOException {
        var result = new LinkedHashMap<String, String>();
        if (!Files.exists(path)) return result;
        if (!Files.isRegularFile(path) || Files.size(path) > 16_384)
            throw new IllegalArgumentException("INVALID_CREDENTIAL_FILE");
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            line = line.replaceFirst("^\\uFEFF", "").strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("INVALID_CREDENTIAL_FILE");
            String key = line.substring(0, separator).strip();
            if (!key.matches("(?:ALIYUN_L2|L2_LIVE)_[A-Z_]+")) continue;
            String value = line.substring(separator + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) value = value.substring(1, value.length() - 1);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("DUPLICATE_CREDENTIAL_VARIABLE");
        }
        return result;
    }

    private static long bounded(Map<String, String> options, String key, long defaultValue, long min, long max) {
        long value;
        try { value = Long.parseLong(options.getOrDefault(key, Long.toString(defaultValue))); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("INVALID_NUMERIC_OPTION"); }
        if (value < min || value > max) throw new IllegalArgumentException("NUMERIC_OPTION_OUT_OF_RANGE");
        return value;
    }

    private static List<String> symbols(String text) {
        var result = new ArrayList<String>();
        for (String input : text.split(",", -1)) {
            String symbol = input.strip().toUpperCase(java.util.Locale.ROOT);
            String code = symbol.split("\\.", -1)[0];
            if (!code.matches("[0-9]{6}") || code.matches("(?:110|111|113|118|123|127|128).*"))
                throw new IllegalArgumentException("UNSUPPORTED_SYMBOL");
            String exchange = code.startsWith("5") || code.startsWith("6") || code.startsWith("9") ? "SH"
                    : code.startsWith("0") || code.startsWith("1") || code.startsWith("3") ? "SZ" : "";
            if (exchange.isEmpty() || symbol.contains(".") && !symbol.equals(code + "." + exchange))
                throw new IllegalArgumentException("SYMBOL_EXCHANGE_MISMATCH");
            symbol = code + "." + exchange;
            if (result.contains(symbol)) throw new IllegalArgumentException("DUPLICATE_SYMBOL");
            result.add(symbol);
        }
        if (result.size() > 16) throw new IllegalArgumentException("SUBSCRIPTION_CAPACITY_EXCEEDED");
        return result;
    }

}
