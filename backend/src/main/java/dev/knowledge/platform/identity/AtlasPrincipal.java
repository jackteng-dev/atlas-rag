package dev.knowledge.platform.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/** Session principal. Its name is the user id, which Spring Session indexes so all sessions of a user can be revoked. */
public record AtlasPrincipal(UUID userId, String email, long authenticatedAtEpochMs) implements AuthenticatedPrincipal, Serializable {
    @Serial private static final long serialVersionUID = 1L;
    @Override public String getName() { return userId.toString(); }
}
