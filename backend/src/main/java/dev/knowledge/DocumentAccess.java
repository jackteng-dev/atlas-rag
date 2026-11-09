package dev.knowledge;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import static dev.knowledge.Models.*;

/** Claims come exclusively from Spring Security authentication, never request headers or documentIds. */
@Component
public class DocumentAccess {
    private final boolean authenticatedMode;
    @org.springframework.beans.factory.annotation.Autowired
    public DocumentAccess(@Value("${knowledge.auth-users:}") String usersFile) { authenticatedMode=!usersFile.isBlank(); }
    public DocumentAccess() { this(""); }
    public record Actor(String name, Set<String> roles, Set<String> departments) {
        public static Actor publicUser() { return new Actor("public-demo",Set.of(),Set.of()); }
        public boolean admin() { return roles.contains("ADMIN"); }
    }
    public Actor current() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return Actor.publicUser();
        Set<String> roles=new HashSet<>(), departments=new HashSet<>();
        auth.getAuthorities().forEach(a->{
            String value=a.getAuthority();
            if(value.startsWith("ROLE_")) roles.add(value.substring(5));
            if(value.startsWith("DEPARTMENT_")) departments.add(value.substring(11));
        });
        return new Actor(auth.getName(),Set.copyOf(roles),Set.copyOf(departments));
    }
    public boolean readable(Document doc,Actor actor) {
        var m=doc.metadata();
        return m.publicAccess() || actor.admin() || (!actor.name().equals("public-demo") && m.owner().equals(actor.name()))
                || ((!m.department().equals("public") ? actor.departments().contains(m.department()) : true)
                    && m.allowedRoles().stream().anyMatch(actor.roles()::contains));
    }
    public boolean eligible(Document doc,Actor actor,Instant now) { return readable(doc,actor) && !doc.metadata().expired(now); }
    public void requireRead(Document doc,boolean forCitation) {
        if(!readable(doc,current()) || (forCitation && doc.metadata().expired(Instant.now())))
            throw new ApiException(404,"DOCUMENT_NOT_FOUND","Document is unavailable in the current scope.");
    }
    public void requireManage(Document doc) {
        Actor actor=current();
        if(actor.admin() || (actor.roles().contains("EDITOR") && doc.metadata().owner().equals(actor.name()))
                || (!authenticatedMode && actor.name().equals("public-demo") && doc.metadata().publicAccess())) return;
        throw new ApiException(403,"ACCESS_DENIED","Document management requires its owner or an administrator.");
    }
    public void requireImport(Metadata metadata) {
        Actor actor=current();
        if(actor.admin() || (actor.roles().contains("EDITOR") && metadata.owner().equals(actor.name())
                && (metadata.department().equals("public") || actor.departments().contains(metadata.department())))
                || (!authenticatedMode && actor.name().equals("public-demo") && metadata.publicAccess() && metadata.owner().equals("public-demo"))) return;
        throw new ApiException(403,"ACCESS_DENIED","Cannot assign this owner or access policy.");
    }
    public boolean authenticatedMode() { return authenticatedMode; }
}
