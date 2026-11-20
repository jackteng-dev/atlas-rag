package dev.knowledge.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {
    private final MutableClock clock = new MutableClock();
    private final LoginThrottle throttle = new LoginThrottle(clock);

    @Test
    void fifthFailureBlocksEmailAcrossDifferentIps() {
        assertThat(throttle.blocked("member@example.test", "192.0.2.1")).isFalse();
        for (int i = 1; i <= 4; i++) {
            throttle.failure("member@example.test", "192.0.2." + i);
            assertThat(throttle.blocked("member@example.test", "192.0.2.99")).isFalse();
        }
        throttle.failure("member@example.test", "192.0.2.5");
        assertThat(throttle.blocked("member@example.test", "192.0.2.99")).isTrue();
        assertThat(throttle.blocked("other@example.test", "192.0.2.99")).isFalse();
    }

    @Test
    void successResetsEmailCounterSoFiveNewFailuresAreRequired() {
        for (int i = 1; i <= 5; i++) throttle.failure("member@example.test", "192.0.2." + i);
        assertThat(throttle.blocked("member@example.test", "192.0.2.99")).isTrue();
        throttle.success("member@example.test");
        assertThat(throttle.blocked("member@example.test", "192.0.2.99")).isFalse();
        for (int i = 1; i <= 4; i++) {
            throttle.failure("member@example.test", "198.51.100." + i);
            assertThat(throttle.blocked("member@example.test", "192.0.2.99")).isFalse();
        }
        throttle.failure("member@example.test", "198.51.100.5");
        assertThat(throttle.blocked("member@example.test", "192.0.2.99")).isTrue();
    }

    @Test
    void fiveFailuresFromDifferentEmailsBlockSharedIp() {
        for (int i = 1; i <= 4; i++) {
            throttle.failure("member" + i + "@example.test", "192.0.2.1");
            assertThat(throttle.blocked("new@example.test", "192.0.2.1")).isFalse();
        }
        throttle.failure("member5@example.test", "192.0.2.1");
        assertThat(throttle.blocked("new@example.test", "192.0.2.1")).isTrue();
        assertThat(throttle.blocked("new@example.test", "192.0.2.2")).isFalse();
    }

    @Test
    void successDoesNotClearSharedIpBlockOrAnotherEmailsCounter() {
        for (int i = 0; i < 5; i++) throttle.failure("other@example.test", "192.0.2.1");
        throttle.success("member@example.test");
        assertThat(throttle.blocked("other@example.test", "192.0.2.2")).isTrue();
        throttle.success("other@example.test");
        assertThat(throttle.blocked("other@example.test", "192.0.2.2")).isFalse();
        assertThat(throttle.blocked("other@example.test", "192.0.2.1")).isTrue();
    }

    @Test
    void emailAndIpBlocksExpireAtFifteenMinutes() {
        for (int i = 0; i < 5; i++) throttle.failure("member@example.test", "192.0.2.1");
        clock.advance(Duration.ofMinutes(15).minusNanos(1));
        assertThat(throttle.blocked("member@example.test", "192.0.2.2")).isTrue();
        assertThat(throttle.blocked("other@example.test", "192.0.2.1")).isTrue();
        clock.advance(Duration.ofNanos(1));
        assertThat(throttle.blocked("member@example.test", "192.0.2.2")).isFalse();
        assertThat(throttle.blocked("other@example.test", "192.0.2.1")).isFalse();
    }

    @Test
    void failuresAfterExpiredWindowStartFreshCounters() {
        for (int i = 0; i < 5; i++) throttle.failure("member@example.test", "192.0.2.1");
        clock.advance(Duration.ofMinutes(15).plusNanos(1));
        for (int i = 0; i < 4; i++) {
            throttle.failure("member@example.test", "192.0.2.1");
            assertThat(throttle.blocked("member@example.test", "192.0.2.1")).isFalse();
        }
        throttle.failure("member@example.test", "192.0.2.1");
        assertThat(throttle.blocked("member@example.test", "192.0.2.1")).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
