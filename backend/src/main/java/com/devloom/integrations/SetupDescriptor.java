package com.devloom.integrations;

import java.util.List;

/**
 * Declares how a source type is configured (docs/SPEC-sources.md §5). Drives the Add-source
 * form: one or more deployments, each with the fields to collect. {@code secret} fields are
 * stored encrypted; the rest go into the instance config.
 */
public final class SetupDescriptor {
    private SetupDescriptor() {}

    public record Field(String key, String label, String type, boolean secret,
                        boolean required, String placeholder, String help) {
        public static Field text(String key, String label, boolean required, String placeholder) {
            return new Field(key, label, "text", false, required, placeholder, null);
        }
        public static Field url(String key, String label, boolean required, String placeholder) {
            return new Field(key, label, "url", false, required, placeholder, null);
        }
        public static Field secret(String key, String label, String placeholder) {
            return new Field(key, label, "password", true, true, placeholder, null);
        }
    }

    public record Deployment(String id, String label, List<Field> fields) {}

    public record Type(String type, String label, List<Deployment> deployments) {}
}
