package com.zoutrankil.data.l2live.application;

import java.time.Instant;
import java.util.Objects;

/** 完整保留 MQTT 接收报文；持久化必须是后续处理的第一步。 */
public record ReceivedMessage(String topic, byte[] payload, Instant receivedAt, String sessionId,
                              int qos, boolean retained, boolean duplicate, boolean subscriptionAuthorized) {
    public ReceivedMessage {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Receive session required");
        if (qos < 0 || qos > 2) throw new IllegalArgumentException("Invalid received MQTT QoS");
        payload = payload.clone();
    }

    /** 旧调用方没有接收时刻的订阅授权证据。 */
    public ReceivedMessage(String topic, byte[] payload, Instant receivedAt, String sessionId,
                           int qos, boolean retained, boolean duplicate) {
        this(topic, payload, receivedAt, sessionId, qos, retained, duplicate, false);
    }

    @Override public byte[] payload() { return payload.clone(); }
}
