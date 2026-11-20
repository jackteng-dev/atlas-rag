package dev.knowledge.platform.identity;

import dev.knowledge.platform.core.Errors;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component @Profile("platform")
public class Passwords {
    private final BCryptPasswordEncoder encoder;
    // Constant-time-ish defence against user enumeration: unknown emails still pay one bcrypt comparison.
    private final String dummyHash;
    public Passwords(BCryptPasswordEncoder encoder) { this.encoder = encoder; dummyHash = encoder.encode("atlas-dummy-password-for-timing"); }

    public String hash(String raw) { return encoder.encode(raw); }
    public boolean matches(String raw, String hash) { return raw != null && hash != null && encoder.matches(raw, hash); }
    public void burn(String raw) { encoder.matches(raw == null ? "" : raw, dummyHash); }

    public static void validate(String password, String email) {
        if (password == null || password.length() < 10 || password.length() > 128)
            throw Errors.bad("WEAK_PASSWORD", "Passwords must be 10–128 characters.");
        if (email != null && password.toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT).split("@")[0]))
            throw Errors.bad("WEAK_PASSWORD", "The password must not contain the email name.");
        if (password.chars().distinct().count() < 5) throw Errors.bad("WEAK_PASSWORD", "The password is too repetitive.");
    }
    public static String normalizeEmail(String email) {
        if (email == null) throw Errors.bad("INVALID_EMAIL", "A valid email address is required.");
        var e = email.trim();
        if (e.length() > 254 || !e.matches("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+")) throw Errors.bad("INVALID_EMAIL", "A valid email address is required.");
        return e.toLowerCase(Locale.ROOT);
    }
}
