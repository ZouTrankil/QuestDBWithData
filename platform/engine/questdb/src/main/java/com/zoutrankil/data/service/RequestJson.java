package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;

/** Shared immutable reader for request trees, rejecting duplicate keys and trailing tokens. */
final class RequestJson {
    private static final ObjectReader READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .reader();

    private RequestJson() {}

    static JsonNode readTree(byte[] input) throws IOException { return READER.readTree(input); }
    static JsonNode readTree(String input) throws IOException { return READER.readTree(input); }
}
