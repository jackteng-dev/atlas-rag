package dev.knowledge.platform.unit;

import dev.knowledge.ApiException;
import dev.knowledge.platform.identity.Passwords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PasswordsTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Abcdef12!"})
    void rejectsNullEmptyAndTooShortPasswords(String password) {
        assertWeakPassword(password, "member@example.test");
    }

    @Test
    void acceptsInclusiveLengthBoundsAndRejectsOneAboveMaximum() {
        assertThatCode(() -> Passwords.validate("Abcdef123!", "member@example.test")).doesNotThrowAnyException();
        assertThatCode(() -> Passwords.validate("Abcde123!" + "x".repeat(119), "member@example.test"))
                .doesNotThrowAnyException();
        assertWeakPassword("Abcde123!" + "x".repeat(120), "member@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice-42!safe", "safe-aLiCe-42!", "Safe-42!ALICE"})
    void rejectsEmailNameAnywhereInPasswordIgnoringCase(String password) {
        assertWeakPassword(password, "ALICE@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaaaaa", "ababababab", "abcdabcdabcd"})
    void rejectsFewerThanFiveDistinctCharacters(String password) {
        assertWeakPassword(password, null);
    }

    @Test
    void acceptsFiveDistinctCharactersAndAllowsAbsentEmail() {
        assertThatCode(() -> Passwords.validate("abcdeabcde", null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "alice", "alice@example", "@example.test", "alice@", "alice@@example.test",
            "alice smith@example.test", "alice@exam ple.test", "alice@example.test\nother"})
    void rejectsInvalidEmails(String email) {
        assertInvalidEmail(email);
    }

    @Test
    void enforcesLocalPartAndTotalEmailLengthBounds() {
        assertThat(Passwords.normalizeEmail("a".repeat(64) + "@example.test"))
                .isEqualTo("a".repeat(64) + "@example.test");
        assertInvalidEmail("a".repeat(65) + "@example.test");
        String longest = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertThat(longest).hasSize(254);
        assertThat(Passwords.normalizeEmail(longest)).isEqualTo(longest);
        assertInvalidEmail(longest + "d");
    }

    @ParameterizedTest
    @CsvSource({"'  Alice@EXAMPLE.TEST  ', alice@example.test", "User+Tag@Example.Test, user+tag@example.test"})
    void trimsAndLowercasesEmail(String input, String expected) {
        assertThat(Passwords.normalizeEmail(input)).isEqualTo(expected);
    }

    private static void assertWeakPassword(String password, String email) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> Passwords.validate(password, email))
                .satisfies(error -> {
                    assertThat(error.status).isEqualTo(400);
                    assertThat(error.code).isEqualTo("WEAK_PASSWORD");
                });
    }

    private static void assertInvalidEmail(String email) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> Passwords.normalizeEmail(email))
                .satisfies(error -> {
                    assertThat(error.status).isEqualTo(400);
                    assertThat(error.code).isEqualTo("INVALID_EMAIL");
                });
    }
}
