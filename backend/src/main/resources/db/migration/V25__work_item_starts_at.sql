-- When a work item happens (calendar events today; anything time-anchored later). The connector
-- always knew this — it was baking the time into a display string, which cannot be sorted into
-- a day's agenda.
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS starts_at TIMESTAMPTZ;
