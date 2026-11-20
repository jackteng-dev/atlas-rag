package dev.knowledge.platform.identity;

import dev.knowledge.ApiException;
import dev.knowledge.platform.core.Errors;
import dev.knowledge.platform.identity.UserRepository.User;
import dev.knowledge.platform.ops.AuditService;
import dev.knowledge.platform.tenant.OrgService;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("platform") @RequestMapping("/api/v2")
public class AuthController {
    public record LoginRequest(String email, String password) {}
    public record RegisterRequest(String email, String password, String displayName, String orgName) {}
    public record PasswordRequest(String currentPassword, String newPassword) {}
    public record AcceptRequest(String token, String displayName, String password) {}
    public record UserView(String id, String email, String displayName, String systemRole) {}
    public record Me(UserView user, List<OrgService.Membership> memberships) {}

    private final UserRepository users;
    private final Passwords passwords;
    private final LoginThrottle throttle;
    private final SecurityContextRepository contexts;
    private final CurrentUser current;
    private final OrgService orgs;
    private final SessionRevoker revoker;
    private final AuditService audit;
    private final boolean registrationOpen;

    public AuthController(UserRepository users, Passwords passwords, LoginThrottle throttle, SecurityContextRepository contexts, CurrentUser current,
                          OrgService orgs, SessionRevoker revoker, AuditService audit, @Value("${atlas.registration.open:false}") boolean registrationOpen) {
        this.users = users; this.passwords = passwords; this.throttle = throttle; this.contexts = contexts; this.current = current;
        this.orgs = orgs; this.revoker = revoker; this.audit = audit; this.registrationOpen = registrationOpen;
    }

    @GetMapping("/auth/csrf") ResponseEntity<Void> csrf(CsrfToken token) { token.getToken(); return ResponseEntity.noContent().build(); }

    @PostMapping("/auth/login")
    Me login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = body.email() == null ? "" : body.email().trim().toLowerCase(Locale.ROOT);
        String ip = request.getRemoteAddr();
        if (throttle.blocked(email, ip)) throw new ApiException(429, "TOO_MANY_ATTEMPTS", "Too many failed sign-ins. Try again in 15 minutes.");
        var user = users.byEmail(email);
        if (user.isEmpty()) passwords.burn(body.password());
        if (user.isEmpty() || !user.get().active() || !passwords.matches(body.password(), user.get().passwordHash())) {
            throttle.failure(email, ip);
            audit.record(null, user.map(User::id).orElse(null), "auth.login", "user", null, AuditService.Result.DENIED, Map.of());
            throw new ApiException(401, "BAD_CREDENTIALS", "Email or password is incorrect.");
        }
        throttle.success(email);
        signIn(user.get(), request, response);
        audit.record(null, user.get().id(), "auth.login", "user", user.get().id());
        return me(user.get());
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me") Me me() { return me(current.require()); }

    @PostMapping("/auth/register") @Transactional
    Me register(@RequestBody RegisterRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (!registrationOpen) throw new ApiException(403, "REGISTRATION_CLOSED", "Self-service registration is disabled. Ask an administrator for an invitation.");
        var email = Passwords.normalizeEmail(body.email());
        Passwords.validate(body.password(), email);
        if (body.displayName() == null || body.displayName().isBlank() || body.displayName().length() > 120) throw Errors.bad("INVALID_NAME", "Display name is required.");
        if (users.byEmail(email).isPresent()) throw Errors.conflict("EMAIL_TAKEN", "An account with this email exists.");
        var user = users.create(email, body.displayName(), passwords.hash(body.password()), "NONE");
        String orgName = body.orgName() == null || body.orgName().isBlank() ? body.displayName() + " 的組織" : body.orgName();
        orgs.create(user, orgName, slugFor(orgName));
        audit.record(null, user.id(), "auth.register", "user", user.id());
        signIn(user, request, response);
        return me(user);
    }

    @PostMapping("/auth/password")
    ResponseEntity<Void> changePassword(@RequestBody PasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
        var user = current.require();
        if (!passwords.matches(body.currentPassword(), user.passwordHash())) throw new ApiException(400, "BAD_CREDENTIALS", "Current password is incorrect.");
        Passwords.validate(body.newPassword(), user.email());
        users.updatePassword(user.id(), passwords.hash(body.newPassword()));
        var session = request.getSession(false);
        revoker.revokeAll(user.id(), session == null ? null : session.getId());
        signIn(users.byId(user.id()).orElseThrow(), request, response);
        audit.record(null, user.id(), "auth.password.change", "user", user.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/accept")
    Me accept(@RequestBody AcceptRequest body, HttpServletRequest request, HttpServletResponse response) {
        User signedIn = null;
        try { signedIn = current.require(); } catch (ApiException ignored) { /* anonymous acceptance creates an account */ }
        var user = orgs.accept(body.token(), signedIn, body.displayName(), body.password());
        if (signedIn == null) signIn(user, request, response);
        return me(user);
    }

    private void signIn(User user, HttpServletRequest request, HttpServletResponse response) {
        var old = request.getSession(false);
        if (old != null) old.invalidate(); // session fixation defence: always a fresh session id after authentication
        request.getSession(true);
        var authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.systemAdmin()) authorities.add(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
        var principal = new AtlasPrincipal(user.id(), user.email(), System.currentTimeMillis());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
    private Me me(User user) {
        return new Me(new UserView(user.id().toString(), user.email(), user.displayName(), user.systemRole()), orgs.memberships(user.id()));
    }
    static String slugFor(String name) {
        var base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.length() < 2) base = "org";
        if (base.length() > 40) base = base.substring(0, 40);
        return base + "-" + Integer.toHexString(new Random().nextInt(0x10000, 0xfffff));
    }
}
