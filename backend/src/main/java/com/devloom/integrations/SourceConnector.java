package com.devloom.integrations;

import java.util.List;

import com.devloom.workmodel.WorkItemEntity;

/**
 * A provider adapter (SPEC.md §12). Each external system implements this port and emits
 * normalized {@link WorkItemEntity} rows; nothing downstream knows which system produced
 * them. Adding Bitbucket/GitLab/Notion = a new implementation, no changes elsewhere.
 */
public interface SourceConnector {

    /** Source label used as the WorkItem.source and for replace-on-sync (e.g. "Jira"). */
    String source();

    /** Whether this connector is configured (e.g. a token is present). */
    boolean enabled();

    /** Fetch current items for the user. Returns empty on failure — never throws to the caller. */
    List<WorkItemEntity> fetch();
}
