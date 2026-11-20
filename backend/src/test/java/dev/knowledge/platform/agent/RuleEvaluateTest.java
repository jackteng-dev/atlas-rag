package dev.knowledge.platform.agent;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluateTest {
    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource({
            "11, gte, 10, true", "10, gte, 10, true", "9, gte, 10, false",
            "9, lte, 10, true", "10, lte, 10, true", "11, lte, 10, false",
            "11, gt, 10, true", "10, gt, 10, false", "9, gt, 10, false",
            "9, lt, 10, true", "10, lt, 10, false", "11, lt, 10, false",
            "-1.5, lt, -1.25, true", "-1.5, gt, -1.25, false"
    })
    void comparesNumericStringsIncludingEqualityBoundaries(String actual, String op, String expected, boolean result) {
        assertThat(AgentRunHandler.evaluate(actual, op, expected)).isEqualTo(result);
    }

    @ParameterizedTest
    @MethodSource("mixedNumbers")
    void comparesNumbersAndNumericStrings(Object actual, String op, Object expected, boolean result) {
        assertThat(AgentRunHandler.evaluate(actual, op, expected)).isEqualTo(result);
    }

    static Stream<Arguments> mixedNumbers() {
        return Stream.of(
                Arguments.of(12, "gt", 10L, true),
                Arguments.of(new BigDecimal("1.25"), "lte", "1.25", true),
                Arguments.of("2.5", "gte", 2.5d, true),
                Arguments.of(2L, "lt", 1.5d, false));
    }

    @ParameterizedTest
    @CsvSource({"Approved, APPROVED, true", "Approved, rejected, false", "'  ok', ok, false", "12, 12, true"})
    void equalityIgnoresCaseButPreservesOtherText(String actual, String expected, boolean result) {
        assertThat(AgentRunHandler.evaluate(actual, "eq", expected)).isEqualTo(result);
    }

    @ParameterizedTest
    @CsvSource({"'Safety APPROVED today', approved, true", "'Safety approved today', rejected, false",
            "'abc', 'abcd', false", "'abc', '', true"})
    void containsFindsCaseInsensitiveSubstrings(String actual, String expected, boolean result) {
        assertThat(AgentRunHandler.evaluate(actual, "contains", expected)).isEqualTo(result);
    }

    @ParameterizedTest
    @MethodSource("existenceCases")
    void existsRequiresNonNullNonBlankValue(Object actual, boolean expected) {
        assertThat(AgentRunHandler.evaluate(actual, "exists", null)).isEqualTo(expected);
    }

    static Stream<Arguments> existenceCases() {
        return Stream.of(Arguments.of(null, false), Arguments.of("", false), Arguments.of(" \t\n", false),
                Arguments.of("present", true), Arguments.of(0, true), Arguments.of(false, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gte", "lte", "gt", "lt", "eq", "contains", "exists", "unknown"})
    void nullActualNeverMatches(String op) {
        assertThat(AgentRunHandler.evaluate(null, op, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gte", "lte", "gt", "lt"})
    void nonNumericOperandsAndNullExpectedFailClosed(String op) {
        assertThat(AgentRunHandler.evaluate("not a number", op, 10)).isFalse();
        assertThat(AgentRunHandler.evaluate(10, op, "not a number")).isFalse();
        assertThat(AgentRunHandler.evaluate(10, op, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "", "GTE", ">="})
    void unknownOperatorFailsClosed(String op) {
        assertThat(AgentRunHandler.evaluate(10, op, 5)).isFalse();
        assertThat(AgentRunHandler.evaluate("text", op, "text")).isFalse();
    }
}
