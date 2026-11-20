package dev.knowledge.platform.identity;

import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Lockout per email (5 failures / 15 min) and per client IP (50 failures / 15 min). The IP limit is deliberately higher so a shared
 * office/NAT address is not locked out by a few typos, while password spraying across many accounts from one IP is still stopped.
 */
@Component @Profile("platform")
public class LoginThrottle {
    private record Window(Instant start, int failures) {}
    static final int EMAIL_LIMIT = 5, IP_LIMIT = 50;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
    private final Clock clock;
    public LoginThrottle() { this(Clock.systemUTC()); }
    LoginThrottle(Clock clock) { this.clock = clock; }

    public boolean blocked(String email, String ip) { return over("e:" + email, EMAIL_LIMIT) || over("i:" + ip, IP_LIMIT); }
    public void failure(String email, String ip) { bump("e:" + email); bump("i:" + ip); }
    public void success(String email) { failures.remove("e:" + email); }
    private boolean over(String key, int limit) {
        var w = failures.get(key);
        return w != null && w.start().plus(WINDOW).isAfter(clock.instant()) && w.failures() >= limit;
    }
    private void bump(String key) {
        var now = clock.instant();
        failures.compute(key, (k, w) -> w == null || !w.start().plus(WINDOW).isAfter(now) ? new Window(now, 1) : new Window(w.start(), w.failures() + 1));
        if (failures.size() > 100_000) failures.entrySet().removeIf(e -> e.getValue().start().plus(WINDOW).isBefore(now));
    }
}
