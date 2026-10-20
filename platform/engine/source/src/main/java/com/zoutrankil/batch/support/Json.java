package com.zoutrankil.batch.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class Json {
    public static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json() {}

    public static String write(Object object) {
        try {
            return MAPPER.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode contract", e);
        }
    }

    public static <T> T read(String text, Class<T> type) {
        try {
            return MAPPER.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid contract JSON", e);
        }
    }
}
