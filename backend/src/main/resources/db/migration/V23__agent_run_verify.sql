-- Whether what an edit run wrote actually passes the repository's own check.
--
-- Until now a run ended at "the model wrote a file". Whether the file worked was left to whoever
-- opened the branch, which is most of why a local model's edit was a gamble rather than a result.
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS verify_status VARCHAR(16);   -- passed | failed | skipped
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS verify_command VARCHAR(300); -- what ran, or why nothing did
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS verify_output TEXT;          -- the failure, kept for review
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS verify_fixed BOOLEAN NOT NULL DEFAULT FALSE;
