package dev.knowledge.platform.core;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Serves the Vue single-page app for client-side routes (e.g. /o/{org}/chat) when the web bundle is packaged in the jar. */
@Component @Profile("platform") @Order(Ordered.LOWEST_PRECEDENCE - 10)
public class SpaFallbackFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("GET".equals(request.getMethod()) && !path.equals("/") && !path.startsWith("/api/") && !path.startsWith("/actuator")
                && !path.startsWith("/assets/") && !path.substring(path.lastIndexOf('/') + 1).contains(".")
                && getClass().getResource("/static/index.html") != null) {
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
