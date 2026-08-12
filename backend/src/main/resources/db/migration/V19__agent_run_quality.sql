-- How a run went about its work, not whether the answer was right. Recorded so the Fleet board can
-- show that a run answered without opening a file, and so eval/ can score behaviour as well as
-- correctness. See RunQuality for what the number means.
ALTER TABLE agent_run ADD COLUMN quality_score   NUMERIC(4,2);  -- 1.00 clean, 0.00 worst
ALTER TABLE agent_run ADD COLUMN quality_notes   VARCHAR(500);  -- the penalties, named
ALTER TABLE agent_run ADD COLUMN tool_calls      INTEGER;
ALTER TABLE agent_run ADD COLUMN tool_repeats    INTEGER;
