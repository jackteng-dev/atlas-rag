package dev.knowledge.platform.core;

import dev.knowledge.ApiException;

/** Consistent platform error factory. 404 is used for anything the caller may not know exists. */
public final class Errors {
    private Errors() {}
    public static ApiException notFound(String what) {
        return new ApiException(404, what.toUpperCase().replace(' ', '_') + "_NOT_FOUND", "The requested " + what.toLowerCase() + " does not exist in this scope.");
    }
    public static ApiException forbidden(String message) { return new ApiException(403, "ACCESS_DENIED", message); }
    public static ApiException bad(String code, String message) { return new ApiException(400, code, message); }
    public static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
    public static ApiException unauthenticated() { return new ApiException(401, "AUTH_REQUIRED", "Sign in to continue."); }
}
