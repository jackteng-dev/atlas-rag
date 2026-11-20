package dev.knowledge.platform.identity;

import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Per-email and per-IP sliding lockout: 5 failures within 15 minutes block further attempts for that window. */
@Component @Profile("platform")
public class LoginThrottle {
    private record Window(Instant start, int failures) {}
    private static final int LIMIT = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
    private final Clock clock;
    public LoginThrottle() { this(Clock.systemUTC()); }
    LoginThrottle(Clock clock) { this.clock = clock; }

    public boolean blocked(String email, String ip) { return over("e:" + email) || over("i:" + ip); }
    public void failure(String email, String ip) { bump("e:" + email); bump("i:" + ip); }
    public void success(String email) { failures.remove("e:" + email); }
    private boolean over(String key) {
        var w = failures.get(key);
        return w != null && w.start().plus(WINDOW).isAfter(clock.instant()) && w.failures() >= LIMIT;
    }
    private void bump(String key) {
        var now = clock.instant();
        failures.compute(key, (k, w) -> w == null || w.start().plus(WINDOW).isBefore(now) ? new Window(now, 1) : new Window(w.start(), w.failures() + 1));
        if (failures.size() > 100_000) failures.entrySet().removeIf(e -> e.getValue().start().plus(WINDOW).isBefore(now));
    }
}
