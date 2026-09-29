package com.zoutrankil.questdbwithdata.domain;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import java.io.IOException;
import java.time.*;

/** Explicit ISO temporal representation, independent of optional Jackson time modules. */
public final class JobDefinitionJson {
    private JobDefinitionJson() {}
    public static ObjectMapper mapper() {
        var temporal = new SimpleModule("job-definition-time");
        temporal.addSerializer(Duration.class, stringSerializer(Duration.class));
        temporal.addSerializer(ZoneId.class, stringSerializer(ZoneId.class));
        temporal.addSerializer(LocalDate.class, stringSerializer(LocalDate.class));
        return new ObjectMapper().registerModule(temporal);
    }
    private static <T> JsonSerializer<T> stringSerializer(Class<T> type) {
        return new StdScalarSerializer<>(type) {
            @Override public void serialize(T value, JsonGenerator output, SerializerProvider provider) throws IOException {
                output.writeString(value.toString());
            }
        };
    }
}
