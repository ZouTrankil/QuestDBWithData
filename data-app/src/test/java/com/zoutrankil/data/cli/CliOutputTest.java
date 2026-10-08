package com.zoutrankil.data.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliOutputTest {
    record TemporalValue(LocalDate date, Instant instant, YearMonth month, Duration duration) {}
    private static final TemporalValue TEMPORAL = new TemporalValue(LocalDate.of(2026, 10, 7),
            Instant.parse("2026-10-07T01:02:03.123456789Z"), YearMonth.of(2026, 10), Duration.ofMillis(1500));

    @ParameterizedTest
    @EnumSource(CliOutput.Profile.class)
    void compactAndPrettyRetainTheOriginalMapperRepresentation(CliOutput.Profile profile) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("z", null);
        payload.put("a", "中文\n\"quoted\"");
        payload.put("numeric", new BigDecimal("1.2300"));
        payload.put("state", CliOutput.Profile.PLAIN);
        for (boolean pretty : new boolean[]{false, true}) {
            ObjectMapper legacy = original(profile);
            String expected = (pretty ? legacy.writerWithDefaultPrettyPrinter() : legacy.writer()).writeValueAsString(payload);
            assertEquals(expected, CliOutput.json(payload, profile, pretty));
        }
    }

    @ParameterizedTest
    @EnumSource(value = CliOutput.Profile.class, names = {"MODULES", "MODULES_ISO", "JOB_DEFINITION"})
    void temporalValuesKeepTheChosenProfile(CliOutput.Profile profile) throws Exception {
        for (boolean pretty : new boolean[]{false, true}) {
            ObjectMapper legacy = original(profile);
            String expected = (pretty ? legacy.writerWithDefaultPrettyPrinter() : legacy.writer()).writeValueAsString(TEMPORAL);
            assertEquals(expected, CliOutput.json(TEMPORAL, profile, pretty));
        }
        JsonNode value = CliOutput.readTree(CliOutput.json(TEMPORAL, profile, false), profile);
        if (profile == CliOutput.Profile.MODULES) {
            assertTrue(value.path("date").isArray());
            assertTrue(value.path("instant").isNumber());
        } else {
            assertEquals("2026-10-07", value.path("date").textValue());
            assertEquals("2026-10-07T01:02:03.123456789Z", value.path("instant").textValue());
            assertEquals("2026-10", value.path("month").textValue());
        }
    }

    @Test void plainProfileDoesNotAcquireTemporalSerializationSupport() {
        assertThrows(JsonProcessingException.class, () -> new ObjectMapper().writeValueAsString(TEMPORAL));
        assertThrows(JsonProcessingException.class, () -> CliOutput.json(TEMPORAL, CliOutput.Profile.PLAIN, false));
    }

    @Test void jobProfileKeepsItsStrictPositiveYearMonthSerializer() {
        assertThrows(JsonProcessingException.class,
                () -> CliOutput.json(YearMonth.of(0, 1), CliOutput.Profile.JOB_DEFINITION, false));
    }

    @ParameterizedTest
    @EnumSource(CliOutput.Profile.class)
    void treeParsingRetainsEmptyNullAndTrailingTokenBehavior(CliOutput.Profile profile) throws Exception {
        assertEquals(original(profile).readTree(""), CliOutput.readTree("", profile));
        assertEquals(original(profile).readTree(" \t\r\n"), CliOutput.readTree(" \t\r\n", profile));
        assertTrue(CliOutput.readTree("null", profile).isNull());
        assertEquals(1, CliOutput.readTree("{\"value\":1} {\"later\":2}", profile).path("value").asInt());
        assertThrows(JsonProcessingException.class, () -> CliOutput.readTree("{", profile));
    }

    @Test void printingUsesTheCurrentStreamAndAddsExactlyOnePlatformNewline() throws Exception {
        // Initialize the shared writers before redirecting stdout.
        String expected = CliOutput.json(Map.of("value", 1), CliOutput.Profile.PLAIN, false);
        PrintStream original = System.out;
        var first = new ByteArrayOutputStream();
        var second = new ByteArrayOutputStream();
        try (var firstStream = new PrintStream(first, true, StandardCharsets.UTF_8);
             var secondStream = new PrintStream(second, true, StandardCharsets.UTF_8)) {
            System.setOut(firstStream);
            CliOutput.printJson(Map.of("value", 1), CliOutput.Profile.PLAIN, false);
            System.setOut(secondStream);
            CliOutput.printJson(Map.of("value", 1), CliOutput.Profile.PLAIN, false);
        } finally {
            System.setOut(original);
        }
        assertEquals(expected + System.lineSeparator(), first.toString(StandardCharsets.UTF_8));
        assertEquals(expected + System.lineSeparator(), second.toString(StandardCharsets.UTF_8));
    }

    @Test void sharedMapperConfigurationIsNotExposed() {
        for (var method : CliOutput.class.getDeclaredMethods()) {
            if (!Modifier.isPrivate(method.getModifiers()))
                assertFalse(ObjectMapper.class.isAssignableFrom(method.getReturnType()), method.toString());
        }
        for (var field : CliOutput.class.getDeclaredFields()) assertTrue(Modifier.isPrivate(field.getModifiers()), field.toString());
    }

    private static ObjectMapper original(CliOutput.Profile profile) {
        return switch (profile) {
            case PLAIN -> new ObjectMapper();
            case MODULES -> new ObjectMapper().findAndRegisterModules();
            case MODULES_ISO -> new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            case JOB_DEFINITION -> JobDefinitionJson.mapper();
        };
    }
}
