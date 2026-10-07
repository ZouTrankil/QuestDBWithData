package com.zoutrankil.data.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;

/** Shared JSON configuration retaining each command's existing representation. */
public final class CliOutput {
    public enum Profile { PLAIN, MODULES, MODULES_ISO, JOB_DEFINITION }

    private static final Codec PLAIN = new Codec(new ObjectMapper());
    private static final Codec MODULES = new Codec(new ObjectMapper().findAndRegisterModules());
    private static final Codec MODULES_ISO = new Codec(new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private static final Codec JOB_DEFINITION = new Codec(JobDefinitionJson.mapper());

    private CliOutput() {}

    public static void printJson(Object value, Profile profile, boolean pretty) throws JsonProcessingException {
        System.out.println(json(value, profile, pretty));
    }

    public static String json(Object value, Profile profile, boolean pretty) throws JsonProcessingException {
        Codec codec = codec(profile);
        return (pretty ? codec.pretty : codec.compact).writeValueAsString(value);
    }

    public static JsonNode readTree(String json, Profile profile) throws JsonProcessingException {
        // Keep the original ObjectMapper tree-reading semantics, including empty input.
        return codec(profile).mapper.readTree(json);
    }

    private static Codec codec(Profile profile) {
        return switch (profile) {
            case PLAIN -> PLAIN;
            case MODULES -> MODULES;
            case MODULES_ISO -> MODULES_ISO;
            case JOB_DEFINITION -> JOB_DEFINITION;
        };
    }

    private static final class Codec {
        private final ObjectMapper mapper;
        private final ObjectWriter compact;
        private final ObjectWriter pretty;

        private Codec(ObjectMapper mapper) {
            this.mapper = mapper;
            this.compact = mapper.writer();
            this.pretty = mapper.writerWithDefaultPrettyPrinter();
        }
    }
}
