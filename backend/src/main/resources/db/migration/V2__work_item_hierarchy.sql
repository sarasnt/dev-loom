-- Work-item hierarchy + descriptions (SPEC.md §15).
-- Jira sub-tasks/children reference their parent; descriptions back the expandable preview.

ALTER TABLE work_item ADD COLUMN description   TEXT;
ALTER TABLE work_item ADD COLUMN parent_ext_id VARCHAR(64);

CREATE INDEX idx_work_item_parent ON work_item (source, parent_ext_id);

-- Purge the original demo seed rows from V1 (acme/billing, Pricing, etc.). Live syncs use
-- replace-on-sync so these are already overwritten in practice; this removes them for good,
-- including on a fresh database where V1 re-seeds them.
DELETE FROM work_item WHERE ext_id IN ('482', '1893', 'T91', '455', 'evt1', '478');
