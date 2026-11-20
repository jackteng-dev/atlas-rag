package dev.knowledge.platform.identity;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Deletes persisted server sessions of a user (password change, disable, removal). */
@Component @Profile("platform")
public class SessionRevoker {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    public SessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) { this.sessions = sessions; }

    public int revokeAll(UUID userId, String exceptSessionId) {
        var found = sessions.findByPrincipalName(userId.toString());
        int removed = 0;
        for (var id : found.keySet()) {
            if (id.equals(exceptSessionId)) continue;
            sessions.deleteById(id); removed++;
        }
        return removed;
    }
}
