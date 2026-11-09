package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class SecurityConfiguration {
    public record Account(String username,String passwordHash,List<String> roles,List<String> departments) {}
    @Bean UserDetailsService accounts(@Value("${knowledge.auth-users:}") String file,ObjectMapper mapper) throws Exception {
        var users=new ArrayList<UserDetails>();
        if(!file.isBlank()) {
            Account[] configured=mapper.readValue(Files.readAllBytes(Path.of(file)),Account[].class);
            if(configured.length==0) throw new IllegalArgumentException("Authentication file must contain accounts");
            var names=new HashSet<String>();
            for(Account account:configured) {
                if(account.username()==null || !account.username().matches("[a-zA-Z0-9_-]{1,64}")
                        || account.username().equals("public-demo") || !names.add(account.username())
                        || account.passwordHash()==null || !account.passwordHash().matches("\\$2[aby]\\$[0-9]{2}\\$.{53}")
                        || account.roles()==null || account.departments()==null)
                    throw new IllegalArgumentException("Invalid authentication account; use bcrypt hashes");
                var authorities=new ArrayList<String>();
                for(String role:account.roles()) {
                    if(!role.matches("[A-Z][A-Z0-9_]{0,31}")) throw new IllegalArgumentException("Invalid role");
                    authorities.add("ROLE_"+role);
                }
                for(String department:account.departments()) {
                    if(!department.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid department");
                    authorities.add("DEPARTMENT_"+department);
                }
                users.add(User.withUsername(account.username()).password(account.passwordHash()).authorities(authorities.toArray(String[]::new)).build());
            }
        }
        return new InMemoryUserDetailsManager(users);
    }
    @Bean BCryptPasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain apiSecurity(HttpSecurity http,DocumentAccess access) throws Exception {
        http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if(access.authenticatedMode()) {
            http.authorizeHttpRequests(a->a.requestMatchers("/api/status","/error").permitAll()
                    .requestMatchers("/api/**").authenticated().anyRequest().permitAll()).httpBasic(withDefaults());
        } else http.authorizeHttpRequests(a->a.anyRequest().permitAll());
        http.exceptionHandling(e->e.authenticationEntryPoint((request,response,error)->{
            response.setStatus(401);response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"AUTH_REQUIRED\",\"message\":\"Sign in to access this knowledge base.\"}");
        }));
        return http.build();
    }
}
