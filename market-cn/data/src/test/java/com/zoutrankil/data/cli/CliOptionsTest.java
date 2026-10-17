package com.zoutrankil.data.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CliOptionsTest {
    @Test void acceptsBothValueFormsAndSplitsOnlyTheFirstEquals() {
        assertEquals(Map.of("--from", "2026-10-01", "--parameters", "a=b=c", "--offset", "-2"),
                CliOptions.parse(new String[]{"plan", "--from", "2026-10-01", "--parameters=a=b=c", "--offset", "-2"}));
    }

    @Test void retainsWhitespaceAndExplicitDashValuesWithoutMutatingArguments() {
        String[] arguments = {"plan", "--file=  path with spaces  ", "--literal=--next"};
        String[] original = arguments.clone();
        assertEquals(Map.of("--file", "  path with spaces  ", "--literal", "--next"), CliOptions.parse(arguments));
        assertArrayEquals(original, arguments);
    }

    @Test void commandTokenIsOutsideTheOptionsParserAndReturnedMapsAreIndependent() {
        assertTrue(CliOptions.parse(new String[]{}).isEmpty());
        var first = CliOptions.parse(new String[]{"unknown-command"});
        first.put("--added", "value");
        assertTrue(CliOptions.parse(new String[]{"unknown-command"}).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void preservesExactLexicalFailureMessages(String[] arguments, String message) {
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(arguments)).getMessage());
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(
                invalid("Unexpected argument: extra", "command", "extra"),
                invalid("Unexpected argument: ", "command", ""),
                invalid("Missing value for --file", "command", "--file"),
                invalid("Missing value for --file", "command", "--file", "--next=value"),
                invalid("Missing value for --=invalid", "command", "--=invalid"),
                invalid("Invalid or empty option: --=invalid", "command", "--=invalid", "value"),
                invalid("Invalid or empty option: --", "command", "--", "value"),
                invalid("Invalid or empty option: --file", "command", "--file="),
                invalid("Invalid or empty option: --file", "command", "--file", " \t "),
                invalid("Invalid or empty option: --File", "command", "--File=value"),
                invalid("Invalid or empty option: --1file", "command", "--1file=value"),
                invalid("Invalid or empty option: --file_name", "command", "--file_name=value"),
                invalid("Invalid or empty option: --file.name", "command", "--file.name=value"),
                invalid("Duplicate option: --file", "command", "--file=a", "--file=b"),
                invalid("Duplicate option: --file", "command", "--file", "a", "--file=b"),
                invalid("Duplicate option: --file", "command", "--file=a", "--file", "a"));
    }

    private static Arguments invalid(String message, String... arguments) {
        return Arguments.of(arguments, message);
    }
}
