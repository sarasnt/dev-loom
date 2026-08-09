package com.devloom.integrations;

import java.util.List;
import java.util.Map;

import com.devloom.workmodel.WorkItemEntity;

/**
 * A provider adapter (docs/SPEC-sources.md §6). One connector per source <em>type</em>; it is
 * a stateless strategy invoked per configured {@link SourceInstanceEntity} with that instance's
 * decrypted secrets. Emits normalized {@link WorkItemEntity} rows (source = instance name).
 * Adding a new type = a new implementation + its {@link #describe()} descriptor; nothing else
 * changes.
 */
public interface SourceConnector {

    /** The source type this connector handles, e.g. "jira". */
    String type();

    /** How this type is configured (deployments + fields) — drives the Add-source form. */
    SetupDescriptor.Type describe();

    /**
     * Fetch current items for one instance. MUST throw on failure (so the sync keeps existing
     * rows) and return a possibly-empty list on success.
     */
    List<WorkItemEntity> fetch(SourceInstanceEntity instance, Map<String, String> secrets);

    /** Validate the credential/config without persisting. Default = a fetch that doesn't throw. */
    default boolean test(SourceInstanceEntity instance, Map<String, String> secrets) {
        fetch(instance, secrets);
        return true;
    }
}
