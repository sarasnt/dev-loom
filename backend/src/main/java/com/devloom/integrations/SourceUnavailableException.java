package com.devloom.integrations;

/**
 * A source could not be consulted, for any reason — unreachable, or not configured with usable
 * credentials.
 *
 * <p>The distinction that matters is not <em>why</em> but <em>versus what</em>: none of these are
 * "there is nothing to show". A connector that could not ask knows nothing about the state of the
 * world, and rendering that as an empty state is indistinguishable from good news, so nobody
 * investigates. Callers catch this and say what happened.
 */
public abstract class SourceUnavailableException extends RuntimeException {
    private final String source;

    protected SourceUnavailableException(String source, String message, Throwable cause) {
        super(message, cause);
        this.source = source;
    }

    public String source() { return source; }
}
