package com.zoutrankil.data.l2live.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** Provider credentials stay in memory and are never part of status/toString output. */
public record L2MqttSettings(String brokerUri, @JsonIgnore String username, @JsonIgnore String password, List<String> symbols,
                             int maxTopics, int keepAliveSeconds) {
    public static final String DEFAULT_BROKER_URI = "tcp://lv2.base32.cn:1883";

    public L2MqttSettings {
        try {
            URI uri = URI.create(brokerUri);
            if (!("tcp".equals(uri.getScheme()) || "ssl".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath() != null && !uri.getPath().isEmpty())
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid L2 MQTT broker URI; credentials belong in separate settings");
        }
        if (username == null || username.isBlank() || username.indexOf('\0') >= 0
                || password == null || password.isBlank() || password.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Private L2 MQTT credentials required");
        username = username.strip();
        if (username.matches("(?i)level2_test[0-9]*"))
            throw new IllegalArgumentException("Public shared L2 test accounts are not permitted");
        if (maxTopics < 3 || maxTopics > L2Subscriptions.MAX_TOPICS)
            throw new IllegalArgumentException("L2 MQTT topic limit must be between 3 and 48");
        if (keepAliveSeconds < 1 || keepAliveSeconds > 65535)
            throw new IllegalArgumentException("Invalid L2 MQTT keepalive interval");
        symbols = L2Subscriptions.normalizeSymbols(symbols);
        if (symbols.isEmpty() || symbols.size() > L2Subscriptions.MAX_SYMBOLS || symbols.size() * 3 > maxTopics)
            throw new IllegalArgumentException("Explicit L2 symbols must fit the configured topic allowance");
    }

    /** The provider requires MQTT client ID to be identical to the account username. */
    public String clientId() { return username; }
    public List<String> topics() { return L2Subscriptions.topics(symbols); }

    public static L2MqttSettings fromEnvironment(Map<String, String> environment, List<String> symbols) {
        String clientId = java.util.Objects.toString(environment.get("ALIYUN_L2_CLIENT_ID"), "");
        String username = java.util.Objects.toString(environment.get("ALIYUN_L2_USERNAME"), clientId);
        if (!clientId.isBlank() && !clientId.strip().equals(username.strip()))
            throw new IllegalArgumentException("L2 MQTT client ID must equal username");
        String host = environment.getOrDefault("ALIYUN_L2_BROKER", "lv2.base32.cn").strip();
        String uri = host.contains("://") ? host : "tcp://" + host + ":" + environment.getOrDefault("ALIYUN_L2_PORT", "1883");
        try {
            return new L2MqttSettings(uri, username, environment.get("ALIYUN_L2_PASSWORD"), symbols,
                    Integer.parseInt(environment.getOrDefault("ALIYUN_L2_MAX_TOPICS", "48")),
                    Integer.parseInt(environment.getOrDefault("ALIYUN_L2_KEEPALIVE_SECONDS", "20")));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid numeric L2 MQTT configuration");
        }
    }

    @Override public String toString() {
        return "L2MqttSettings[brokerUri=" + brokerUri + ", username=<redacted>, password=<redacted>, symbols="
                + symbols + ", maxTopics=" + maxTopics + ", keepAliveSeconds=" + keepAliveSeconds + "]";
    }
}
