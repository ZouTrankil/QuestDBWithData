package com.zoutrankil.data.l2live.storage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;

import java.security.MessageDigest;
import java.util.HexFormat;

final class LiveStorageJson {
    static final ObjectMapper MAPPER =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.USE_LONG_FOR_INTS)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .addMixIn(L2CleanEvent.class, CleanEventMixin.class);

    private abstract static class CleanEventMixin {
        @JsonIgnore
        abstract boolean isValid();
    }

    private LiveStorageJson() {}

    static byte[] bytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid frozen live-L2 value", e);
        }
    }

    static <T> T read(byte[] bytes, Class<T> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid retained live-L2 artifact", e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
