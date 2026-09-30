package com.zoutrankil.data.domain;

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
        temporal.addSerializer(Instant.class, stringSerializer(Instant.class));
        temporal.addDeserializer(Duration.class,stringDeserializer(Duration.class,Duration::parse));
        temporal.addDeserializer(ZoneId.class,stringDeserializer(ZoneId.class,ZoneId::of));
        temporal.addDeserializer(LocalDate.class,stringDeserializer(LocalDate.class,LocalDate::parse));
        temporal.addDeserializer(Instant.class,stringDeserializer(Instant.class,Instant::parse));
        return new ObjectMapper().registerModule(temporal);
    }
    private static <T> JsonDeserializer<T> stringDeserializer(Class<T> type,java.util.function.Function<String,T> parse) {
        return new com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer<>(type) {
            @Override public T deserialize(com.fasterxml.jackson.core.JsonParser input,DeserializationContext context)
                    throws IOException {
                if(!input.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING))
                    return context.reportInputMismatch(type,"Expected explicit ISO temporal string");
                String value=input.getText();
                try { return parse.apply(value); }
                catch(DateTimeException invalid) { throw context.weirdStringException(value,type,"Invalid ISO temporal value"); }
            }
        };
    }
    private static <T> JsonSerializer<T> stringSerializer(Class<T> type) {
        return new StdScalarSerializer<>(type) {
            @Override public void serialize(T value, JsonGenerator output, SerializerProvider provider) throws IOException {
                output.writeString(value.toString());
            }
        };
    }
}
