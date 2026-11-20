package dev.knowledge.platform.identity;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Platform security: server-side sessions (Spring Session JDBC), double-submit cookie CSRF for every mutating request,
 * fail-closed for /api/** and no anonymous writes. Authorisation of tenant data happens in the service layer
 * ({@link dev.knowledge.platform.tenant.TenantAccess}), never from client-supplied claims.
 */
@Configuration @Profile("platform")
public class PlatformSecurityConfiguration {
    @Bean BCryptPasswordEncoder platformPasswords() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean SecurityFilterChain platformSecurity(HttpSecurity http, SecurityContextRepository contexts) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Cookies ignore ports: a generic XSRF-TOKEN set by another app on the same host would collide, so Atlas uses its own names.
        csrfRepository.setCookieName("ATLAS_XSRF");
        csrfRepository.setHeaderName("X-ATLAS-XSRF");
        csrfRepository.setCookieCustomizer(c -> c.sameSite("Lax").path("/"));
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null); // resolve the token on every request so the cookie is always issued
        http.csrf(c -> c.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(handler))
            .securityContext(s -> s.securityContextRepository(contexts))
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .requestCache(r -> r.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/status", "/api/v2/auth/csrf", "/api/v2/auth/login", "/api/v2/auth/register",
                        "/api/v2/invitations/accept", "/actuator/health", "/error").permitAll()
                .requestMatchers("/api/**", "/actuator/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, error) -> write(response, 401, "AUTH_REQUIRED", "Sign in to continue."))
                .accessDeniedHandler((request, response, error) -> {
                    if (error instanceof org.springframework.security.web.csrf.CsrfException)
                        write(response, 403, "CSRF_TOKEN_INVALID", "The security token is missing or expired. Refresh the page and try again.");
                    else write(response, 403, "ACCESS_DENIED", "You do not have permission for this action.");
                }))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(
                    "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'")));
        return http.build();
    }

    private static void write(jakarta.servlet.http.HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        var trace = org.slf4j.MDC.get("traceId");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"traceId\":\"" + (trace == null ? "" : trace) + "\"}");
    }
}
