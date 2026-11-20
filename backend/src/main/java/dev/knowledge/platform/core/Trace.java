package dev.knowledge.platform.core;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.MDC;

/** Request-scoped trace id; jobs copy it so background work keeps the originating trace. */
public final class Trace {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Trace() {}
    public static String newId() { var b = new byte[12]; RANDOM.nextBytes(b); return HexFormat.of().formatHex(b); }
    public static String current() { var id = MDC.get("traceId"); return id == null ? newId() : id; }
    public static boolean valid(String id) { return id != null && id.matches("[a-f0-9]{16,64}"); }
    public static void set(String id) { MDC.put("traceId", id); }
    public static void clear() { MDC.remove("traceId"); }
}
