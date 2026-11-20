package dev.knowledge.platform.identity;

import dev.knowledge.platform.tenant.OrgService;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time bootstrap: only when the users table is empty AND both ATLAS_BOOTSTRAP_ADMIN_EMAIL and
 * ATLAS_BOOTSTRAP_ADMIN_PASSWORD are provided does it create a system administrator who owns a first organisation.
 * No password is ever hard-coded or logged.
 */
@Component @Profile("platform")
public class BootstrapAdmin implements ApplicationRunner {
    private final UserRepository users;
    private final Passwords passwords;
    private final OrgService orgs;
    private final String email, password, orgName;
    public BootstrapAdmin(UserRepository users, Passwords passwords, OrgService orgs,
                          @Value("${atlas.bootstrap.admin-email:}") String email, @Value("${atlas.bootstrap.admin-password:}") String password,
                          @Value("${atlas.bootstrap.org-name:Atlas}") String orgName) {
        this.users = users; this.passwords = passwords; this.orgs = orgs; this.email = email; this.password = password; this.orgName = orgName;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        var log = LoggerFactory.getLogger(BootstrapAdmin.class);
        if (users.count() > 0) return;
        if (email.isBlank() || password.isBlank()) {
            log.warn("No users exist. Set ATLAS_BOOTSTRAP_ADMIN_EMAIL and ATLAS_BOOTSTRAP_ADMIN_PASSWORD once to create the first administrator.");
            return;
        }
        var normalized = Passwords.normalizeEmail(email);
        Passwords.validate(password, normalized);
        var admin = users.create(normalized, "Administrator", passwords.hash(password), "SYSTEM_ADMIN");
        orgs.create(admin, orgName, AuthController.slugFor(orgName));
        log.info("Bootstrap administrator created for {} (remove the bootstrap password variable now).", normalized);
    }
}
