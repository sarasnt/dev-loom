-- Canonical URL for a work item (open it on GitHub / Jira / Notion). Populated by connectors.
ALTER TABLE work_item ADD COLUMN url TEXT;
