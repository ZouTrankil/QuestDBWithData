package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private Json() {}
    static String write(Object object) {
        try { return MAPPER.writeValueAsString(object); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot encode contract", e); }
    }
    static <T> T read(String text, Class<T> type) {
        try { return MAPPER.readValue(text, type); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid contract JSON", e); }
    }
}
