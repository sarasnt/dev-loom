package com.devloom.integrations;

/**
 * The source's own server could not be reached or did not answer in time.
 *
 * <p>Distinct from "there is nothing to show". A connector that cannot reach its server knows
 * nothing about the state of the world, and must not be allowed to render as an empty state
 * saying everything is fine — that is indistinguishable from good news, so nobody investigates.
 */
public class SourceUnreachableException extends RuntimeException {
    private final String source;

    public SourceUnreachableException(String source, Throwable cause) {
        super("Could not reach " + source + ": " + rootMessage(cause), cause);
        this.source = source;
    }

    public String source() { return source; }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        return m == null || m.isBlank() ? r.getClass().getSimpleName() : m;
    }
}
