package dev.knowledge.platform.ops;

import dev.knowledge.platform.core.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;

/**
 * Append-only audit trail. Callers pass identifiers and small, non-secret details only: never passwords, tokens,
 * invitation secrets, document text or model output.
 */
@Service @Profile("platform")
public class AuditService {
    public enum Result { SUCCESS, DENIED, FAILURE }
    private final JdbcClient db;
    private final Json json;
    public AuditService(JdbcClient db, Json json) { this.db = db; this.json = json; }

    public void record(UUID orgId, UUID actorId, String action, String resourceType, Object resourceId, Result result, Map<String, ?> detail) {
        db.sql("""
                insert into audit_events(org_id, actor_id, action, resource_type, resource_id, result, request_id, trace_id, ip, detail)
                values (:org, :actor, :action, :type, :rid, :result, :req, :trace, :ip, cast(:detail as jsonb))""")
                .param("org", orgId).param("actor", actorId).param("action", action).param("type", resourceType)
                .param("rid", resourceId == null ? null : resourceId.toString()).param("result", result.name())
                .param("req", Trace.current()).param("trace", Trace.current()).param("ip", clientIp())
                .param("detail", json.write(detail == null ? Map.of() : detail)).update();
    }
    public void record(UUID orgId, UUID actorId, String action, String resourceType, Object resourceId) {
        record(orgId, actorId, action, resourceType, resourceId, Result.SUCCESS, Map.of());
    }
    private static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpServletRequest request = attrs.getRequest();
        return request.getRemoteAddr();
    }
}
