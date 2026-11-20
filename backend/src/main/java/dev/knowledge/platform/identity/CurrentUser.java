package dev.knowledge.platform.identity;

import dev.knowledge.platform.core.Errors;
import dev.knowledge.platform.identity.UserRepository.User;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the signed-in user from the server session and re-checks the database on every call, so a disabled user or a
 * password change takes effect immediately even if a stale session survived.
 */
@Component @Profile("platform")
public class CurrentUser {
    private final UserRepository users;
    public CurrentUser(UserRepository users) { this.users = users; }

    public User require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AtlasPrincipal principal)) throw Errors.unauthenticated();
        var user = users.byId(principal.userId()).orElseThrow(Errors::unauthenticated);
        if (!user.active()) throw Errors.unauthenticated();
        if (user.passwordChangedAt() != null && user.passwordChangedAt().toEpochMilli() > principal.authenticatedAtEpochMs() + 1000)
            throw Errors.unauthenticated();
        return user;
    }
    public User requireSystemAdmin() {
        var user = require();
        if (!user.systemAdmin()) throw Errors.forbidden("System administrator role required.");
        return user;
    }
}
