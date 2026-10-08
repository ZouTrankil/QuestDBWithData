package com.zoutrankil.data.domain;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import java.io.IOException;
import java.time.*;
import java.util.regex.Pattern;

/** Explicit ISO temporal representation, independent of optional Jackson time modules. */
public final class JobDefinitionJson {
    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("[0-9]{4}-(0[1-9]|1[0-2])");
    private static final SimpleModule TEMPORAL_MODULE = temporalModule();
    private static final ObjectMapper MAPPER = createMapper(false);
    private static final ObjectMapper CANONICAL_MAPPER = createMapper(true);

    private JobDefinitionJson() {}

    /** Shared mapper preserving map iteration order. Do not mutate its configuration. */
    public static ObjectMapper mapper() { return MAPPER; }

    /** Shared mapper that sorts map keys for fingerprints. Do not mutate its configuration. */
    public static ObjectMapper canonicalMapper() { return CANONICAL_MAPPER; }

    private static ObjectMapper createMapper(boolean sortMapKeys) {
        var mapper = new ObjectMapper().registerModule(TEMPORAL_MODULE);
        if (sortMapKeys) mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return mapper;
    }

    private static SimpleModule temporalModule() {
        var temporal = new SimpleModule("job-definition-time");
        temporal.addSerializer(Duration.class, stringSerializer(Duration.class));
        temporal.addSerializer(ZoneId.class, stringSerializer(ZoneId.class));
        temporal.addSerializer(LocalDate.class, stringSerializer(LocalDate.class));
        temporal.addSerializer(Instant.class, stringSerializer(Instant.class));
        temporal.addSerializer(YearMonth.class,new StdScalarSerializer<YearMonth>(YearMonth.class) {
            @Override public void serialize(YearMonth value,JsonGenerator output,SerializerProvider provider) throws IOException {
                if(value.getYear()<1 || value.getYear()>9999)
                    provider.reportMappingProblem("YearMonth requires a positive four-digit YYYY-MM year");
                output.writeString(value.toString());
            }
        });
        temporal.addSerializer(SyncJobDefinition.FrozenRequest.class,
                new StdSerializer<SyncJobDefinition.FrozenRequest>(SyncJobDefinition.FrozenRequest.class) {
                    @Override public void serialize(SyncJobDefinition.FrozenRequest value, JsonGenerator output,
                                                    SerializerProvider provider) throws IOException {
                        output.writeStartObject(value);
                        provider.defaultSerializeField("definition", value.definition(), output);
                        provider.defaultSerializeField("mode", value.mode(), output);
                        provider.defaultSerializeField("parameters", value.parameters(), output);
                        provider.defaultSerializeField("from", value.from(), output);
                        provider.defaultSerializeField("to", value.to(), output);
                        provider.defaultSerializeField("logicalDate", value.logicalDate(), output);
                        output.writeEndObject();
                    }
                });
        temporal.addDeserializer(Duration.class,stringDeserializer(Duration.class,Duration::parse));
        temporal.addDeserializer(ZoneId.class,stringDeserializer(ZoneId.class,ZoneId::of));
        temporal.addDeserializer(LocalDate.class,stringDeserializer(LocalDate.class,LocalDate::parse));
        temporal.addDeserializer(Instant.class,stringDeserializer(Instant.class,Instant::parse));
        temporal.addDeserializer(YearMonth.class,stringDeserializer(YearMonth.class,value -> {
            if(!YEAR_MONTH_PATTERN.matcher(value).matches())
                throw new DateTimeException("Exact YYYY-MM month required");
            YearMonth month=YearMonth.parse(value);
            if(month.getYear()<1)throw new DateTimeException("Positive four-digit year required");
            return month;
        }));
        return temporal;
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
