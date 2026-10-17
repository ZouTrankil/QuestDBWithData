package com.zoutrankil.data.l2live.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/** 读取已有的显式订阅规则，不自行扩大允许名单。 */
public final class L2Subscriptions {
    public static final int MAX_SYMBOLS = 16;
    public static final int MAX_TOPICS = 48;
    public static final List<String> CHANNELS = List.of("trans", "order", "market");
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Candidate(String symbol, int priority) {}

    private L2Subscriptions() {}

    /** 按日期检查时使用本地午夜；实时调用方应传入当次记录的 Instant。 */
    public static List<String> load(Path path, LocalDate asOf) throws IOException {
        return load(path, Objects.requireNonNull(asOf).atStartOfDay(EXCHANGE_ZONE).toInstant());
    }

    public static List<String> load(Path path, Instant asOf) throws IOException {
        Objects.requireNonNull(asOf);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1_048_576)
            throw new IOException("A bounded regular L2 subscription config is required");
        JsonNode document;
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > 1_048_576) throw new IOException("L2 subscription config exceeds bounded read");
            document = JSON.readTree(bytes);
        }
        catch (IOException invalid) { throw new IOException("Invalid L2 subscription JSON"); }
        if (document == null || !document.isObject()) throw new IOException("L2 subscription config must be an object");
        Set<String> allowed = symbolSet(document.get("allow_symbols"));
        Set<String> blocked = symbolSet(document.get("block_symbols"));
        JsonNode forced = document.get("force_symbols");
        if (forced == null) return List.of();
        if (!forced.isArray()) throw new IOException("L2 force_symbols must be an array");
        var candidates = new HashMap<String, Candidate>();
        for (JsonNode node : forced) {
            if (!node.isObject() && !node.isTextual()) throw new IOException("Invalid L2 force entry");
            JsonNode symbol = node.isTextual() ? node : node.get("symbol");
            if (symbol == null || !symbol.isTextual()) throw new IOException("Explicit L2 force symbol required");
            String normalized;
            try { normalized = normalizeSymbol(symbol.asText()); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unsupported L2 force symbol"); }
            if (node.isObject() && node.hasNonNull("expires_at")) {
                JsonNode expires = node.get("expires_at");
                if (!expires.isTextual()) throw new IOException("L2 expiry must be an offset timestamp");
                Instant expiry;
                try { expiry = OffsetDateTime.parse(expires.asText()).toInstant(); }
                catch (RuntimeException invalid) { throw new IOException("L2 expiry must be an offset timestamp"); }
                if (!expiry.isAfter(asOf)) continue;
            }
            if (blocked.contains(normalized) || !allowed.isEmpty() && !allowed.contains(normalized)) continue;
            JsonNode priorityNode = node.isObject() ? node.get("priority") : null;
            if (priorityNode != null && !priorityNode.isNull() && (!priorityNode.isIntegralNumber() || !priorityNode.canConvertToInt()))
                throw new IOException("L2 priority must be an integer");
            int priority = priorityNode == null ? 0 : priorityNode.asInt(0);
            Candidate old = candidates.get(normalized);
            if (old == null || priority > old.priority()) candidates.put(normalized, new Candidate(normalized, priority));
        }
        return candidates.values().stream().sorted(Comparator.comparingInt(Candidate::priority).reversed()
                .thenComparing(Candidate::symbol)).limit(MAX_SYMBOLS).map(Candidate::symbol).toList();
    }

    private static Set<String> symbolSet(JsonNode node) throws IOException {
        if (node == null) return Set.of();
        if (!node.isArray()) throw new IOException("L2 symbol filters must be arrays");
        var result = new HashSet<String>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw new IOException("L2 symbol filters require strings");
            try { result.add(normalizeSymbol(item.asText())); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unsupported L2 symbol filter"); }
        }
        return Set.copyOf(result);
    }

    /** 供应商后缀不可靠，根据六位证券代码推断交易所。 */
    public static String normalizeSymbol(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("Unsupported L2 symbol");
        String code = symbol.strip().toUpperCase(Locale.ROOT).split("\\.", -1)[0];
        if (!code.matches("[0-9]{1,6}")) throw new IllegalArgumentException("Unsupported L2 symbol");
        code = "0".repeat(6 - code.length()) + code;
        for (String prefix : List.of("110", "111", "113", "118", "123", "127", "128"))
            if (code.startsWith(prefix)) throw new IllegalArgumentException("Convertible bonds are outside the L2 subscription policy");
        if (code.startsWith("5") || code.startsWith("6") || code.startsWith("9") || code.startsWith("11")) return code + ".SH";
        if (code.startsWith("00") || code.startsWith("12") || code.startsWith("15") || code.startsWith("16") || code.startsWith("30")) return code + ".SZ";
        throw new IllegalArgumentException("Only supported SH/SZ L2 symbols are permitted");
    }

    static List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null) throw new IllegalArgumentException("Explicit L2 symbols required");
        var result = new LinkedHashSet<String>();
        for (String symbol : symbols) result.add(normalizeSymbol(symbol));
        return List.copyOf(result);
    }

    public static List<String> topics(List<String> symbols) {
        var result = new ArrayList<String>();
        for (String symbol : normalizeSymbols(symbols))
            for (String channel : CHANNELS) result.add(channel + "/" + symbol.substring(0, 6));
        return List.copyOf(result);
    }
}
