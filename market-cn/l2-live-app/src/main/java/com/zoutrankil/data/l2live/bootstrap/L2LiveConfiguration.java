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
import com.zoutrankil.runtime.ProjectConfiguration;
import org.springframework.core.env.Environment;

/** 共用 YAML 和配置环境；兼容旧凭据文件，并允许命令行覆盖。 */
public final class L2LiveConfiguration {
    private static final Set<String> COMMANDS = Set.of("plan", "run", "session", "replay", "status", "export", "migrate-parquet");
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
    final ZoneId sessionZone;
    final String sessionExchange;
    final java.time.LocalTime sessionStart;
    final java.time.LocalTime sessionStop;

    private L2LiveConfiguration(String command, Map<String, String> options, Map<String, String> env, Environment config)
            throws IOException {
        this.command = command;
        sessionZone = com.zoutrankil.runtime.MarketClocks.forMarket(config,
                com.zoutrankil.data.domain.temporal.MarketId.CN, java.time.Clock.systemUTC()).zone();
        if (!ZoneId.of(config.getProperty("l2.session.zone",sessionZone.getId())).equals(sessionZone))
            throw new IllegalArgumentException("L2 session must use the configured A-share zone");
        sessionExchange = config.getProperty("l2.session.exchange", "SSE");
        if (!Set.of("SSE", "SZSE").contains(sessionExchange)) throw new IllegalArgumentException("INVALID_SESSION_EXCHANGE");
        sessionStart = java.time.LocalTime.parse(config.getProperty("l2.session.start", "09:00"));
        sessionStop = java.time.LocalTime.parse(config.getProperty("l2.session.stop", "15:05"));
        if (!sessionStart.isBefore(sessionStop)) throw new IllegalArgumentException("INVALID_SESSION_WINDOW");
        var effective = new LinkedHashMap<String, String>();
        for (String key : OPTIONS) {
            if (key.equals("broker")) continue; // 仍允许凭据文件或环境变量覆盖 MQTT 服务地址。
            String value = config.getProperty("l2." + key);
            if (value != null && !value.isBlank()) effective.put(key, value);
        }
        effective.put("poll-ms", Long.toString(config.getRequiredProperty("l2.poll-interval", Duration.class).toMillis()));
        effective.put("visibility-ms", Long.toString(config.getRequiredProperty("l2.visibility-timeout", Duration.class).toMillis()));
        effective.putAll(options);
        options = effective;
        root = Path.of(options.get("root")).toAbsolutePath().normalize();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ALIYUN_L2_USERNAME", config.getRequiredProperty("l2.username"));
        values.put("ALIYUN_L2_PASSWORD", config.getRequiredProperty("l2.password"));
        values.put("ALIYUN_L2_BROKER", config.getRequiredProperty("l2.broker"));
        values.put("ALIYUN_L2_PORT", config.getRequiredProperty("l2.port"));
        values.put("L2_LIVE_QUESTDB_URL", config.getRequiredProperty("l2.questdb-url"));
        values.put("L2_LIVE_QUESTDB_AUTHORIZATION", config.getRequiredProperty("l2.questdb-authorization"));
        values.putAll(credentialFile(Path.of(options.getOrDefault("credentials", root.resolve("credentials.env").toString()))));
        for (var entry : env.entrySet()) if (!entry.getValue().isBlank()) values.put(entry.getKey(), entry.getValue());
        username = values.getOrDefault("ALIYUN_L2_USERNAME", "");
        password = values.getOrDefault("ALIYUN_L2_PASSWORD", "");
        String broker = options.getOrDefault("broker", values.get("ALIYUN_L2_BROKER"));
        String port = values.get("ALIYUN_L2_PORT");
        brokerUri = broker.contains("://") ? broker : "tcp://" + broker + ":" + port;
        var parsedBroker = java.net.URI.create(brokerUri);
        if (!Set.of("tcp", "ssl").contains(parsedBroker.getScheme()) || parsedBroker.getHost() == null
                || parsedBroker.getUserInfo() != null || parsedBroker.getQuery() != null
                || parsedBroker.getFragment() != null || !Set.of("", "/").contains(parsedBroker.getPath()))
            throw new IllegalArgumentException("INVALID_BROKER_URI");
        questDb = options.getOrDefault("questdb", values.get("L2_LIVE_QUESTDB_URL"));
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
                    config.getRequiredProperty("l2.overrides"))),
                    java.time.Instant.now()));
    }

    public static L2LiveConfiguration parse(String[] args, Map<String, String> env) throws IOException {
        return parse(args, env, ProjectConfiguration.load("l2", env));
    }

    static L2LiveConfiguration parse(String[] args, Map<String, String> env, Environment config) throws IOException {
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
        return new L2LiveConfiguration(command, options, env, config);
    }

    boolean live() { return command.equals("run") || command.equals("session"); }

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
        result.put("calendarQuestDb", questDb);
        result.put("storage", "parquet");
        result.put("parquetRoot", root.resolve("parquet/batches").toString());
        result.put("database", root.resolve("raw-journal.sqlite3").toString());
        result.put("session", Map.of("zone", sessionZone.toString(), "exchange", sessionExchange,
                "start", sessionStart.toString(), "stop", sessionStop.toString(), "calendar", "exchange_calendar"));
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
