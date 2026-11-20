package dev.knowledge.platform.tenant;

/** Organisation roles, ordered from most to least privileged. */
public enum Role {
    OWNER, ADMIN, EDITOR, VIEWER;
    public boolean atLeast(Role other) { return ordinal() <= other.ordinal(); }
    public static Role parse(String value) {
        try { return Role.valueOf(value); } catch (RuntimeException e) { throw dev.knowledge.platform.core.Errors.bad("INVALID_ROLE", "Unknown role."); }
    }
}
