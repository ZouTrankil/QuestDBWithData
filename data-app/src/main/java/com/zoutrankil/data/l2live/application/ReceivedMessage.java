package com.zoutrankil.data.l2live.application;

import java.time.Instant;
import java.util.Objects;

/** Exact MQTT receive envelope; persistence owns the first processing step. */
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

    /** Legacy callers have no receive-time authorization proof. */
    public ReceivedMessage(String topic, byte[] payload, Instant receivedAt, String sessionId,
                           int qos, boolean retained, boolean duplicate) {
        this(topic, payload, receivedAt, sessionId, qos, retained, duplicate, false);
    }

    @Override public byte[] payload() { return payload.clone(); }
}
