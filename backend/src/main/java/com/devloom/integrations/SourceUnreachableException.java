package com.devloom.integrations;

/** The source's own server could not be reached, or did not answer in time. */
public class SourceUnreachableException extends SourceUnavailableException {

    public SourceUnreachableException(String source, Throwable cause) {
        super(source, "Could not reach " + source + ": " + rootMessage(cause), cause);
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        return m == null || m.isBlank() ? r.getClass().getSimpleName() : m;
    }
}
