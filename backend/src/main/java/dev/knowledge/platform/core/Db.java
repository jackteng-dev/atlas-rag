package dev.knowledge.platform.core;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** JDBC column helpers shared by repositories. */
public final class Db {
    private Db() {}
    public static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
    public static String iso(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column); return ts == null ? null : ts.toInstant().toString();
    }
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column); return ts == null ? null : ts.toInstant();
    }
    public static Integer intOrNull(ResultSet rs, String column) throws SQLException { int v = rs.getInt(column); return rs.wasNull() ? null : v; }
    public static List<String> textArray(ResultSet rs, String column) throws SQLException {
        var a = rs.getArray(column); if (a == null) return List.of();
        return List.of((String[]) a.getArray());
    }
    public static List<UUID> uuidArray(ResultSet rs, String column) throws SQLException {
        var a = rs.getArray(column); if (a == null) return List.of();
        return List.of((UUID[]) a.getArray());
    }
    public static Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    public static UUID parse(String id, String what) {
        try { return UUID.fromString(id); } catch (RuntimeException e) { throw Errors.notFound(what); }
    }
}
