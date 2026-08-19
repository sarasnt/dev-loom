-- Who created a PR, and what it wants from you ("mine" | "review" | "other"). Computed at sync
-- time by the connector — the only place that knows the authenticated user — never guessed
-- downstream. Null for non-PR items and for connectors that don't resolve it.
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS author VARCHAR(120);
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS pr_role VARCHAR(10);
