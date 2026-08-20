package com.devloom.integrations;

/**
 * The source is configured but carries no credential this operation can use.
 *
 * <p>Worth its own type because it is the one failure the user can fix directly, and because it
 * used to be reported as "nothing is failing" — the analyzer read a global config property that
 * nobody sets instead of the credentials entered when the source was added.
 */
public class SourceCredentialsMissingException extends SourceUnavailableException {

    public SourceCredentialsMissingException(String source, String what) {
        super(source, "No " + what + " configured for " + source
                + ", so its builds cannot be analyzed.", null);
    }
}
