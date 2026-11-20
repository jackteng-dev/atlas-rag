package dev.knowledge.platform.identity;

import dev.knowledge.platform.core.Db;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository @Profile("platform")
public class UserRepository {
    public record User(UUID id, String email, String displayName, String passwordHash, String status, String systemRole, Instant passwordChangedAt) {
        public boolean active() { return "ACTIVE".equals(status); }
        public boolean systemAdmin() { return "SYSTEM_ADMIN".equals(systemRole); }
    }
    private final JdbcClient db;
    public UserRepository(JdbcClient db) { this.db = db; }

    private static final String COLUMNS = "id, email, display_name, password_hash, status, system_role, password_changed_at";
    private static User map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new User(Db.uuid(rs, "id"), rs.getString("email"), rs.getString("display_name"), rs.getString("password_hash"),
                rs.getString("status"), rs.getString("system_role"), Db.instant(rs, "password_changed_at"));
    }
    public Optional<User> byId(UUID id) { return db.sql("select " + COLUMNS + " from users where id = :id").param("id", id).query((rs, n) -> map(rs)).optional(); }
    public Optional<User> byEmail(String email) {
        return db.sql("select " + COLUMNS + " from users where lower(email) = lower(:e)").param("e", email.trim()).query((rs, n) -> map(rs)).optional();
    }
    public User create(String email, String displayName, String passwordHash, String systemRole) {
        var id = UUID.randomUUID();
        db.sql("insert into users(id, email, display_name, password_hash, system_role) values (:id, :e, :n, :h, :r)")
                .param("id", id).param("e", email.trim()).param("n", displayName.trim()).param("h", passwordHash).param("r", systemRole).update();
        return byId(id).orElseThrow();
    }
    public void updatePassword(UUID id, String hash) {
        db.sql("update users set password_hash = :h, password_changed_at = now() where id = :id").param("h", hash).param("id", id).update();
    }
    public void updateStatus(UUID id, String status) { db.sql("update users set status = :s where id = :id").param("s", status).param("id", id).update(); }
    public long count() { return db.sql("select count(*) from users").query(Long.class).single(); }
    public List<User> all(int limit) { return db.sql("select " + COLUMNS + " from users order by created_at limit :l").param("l", limit).query((rs, n) -> map(rs)).list(); }
}
