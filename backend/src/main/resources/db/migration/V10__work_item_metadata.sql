-- Rich, model-facing metadata for a work item (e.g. Jira custom fields as "Name: value"
-- lines), kept separate from the compact meta_csv shown in the Work UI. Used to enrich
-- brainstorm context so the model can reason about the real issue, not just its title.
ALTER TABLE work_item ADD COLUMN metadata text;
