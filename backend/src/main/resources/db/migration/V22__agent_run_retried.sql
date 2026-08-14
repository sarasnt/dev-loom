-- Whether a run was retried after the judge ruled it missed the task.
--
-- Without this the retry is invisible: it happens inside one background run, so the board shows a
-- single row and eval/ cannot tell a first-attempt pass from a rescued one. That makes "the retry
-- helps" an unfalsifiable claim, which is the one kind of claim this project doesn't keep.
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS retried BOOLEAN NOT NULL DEFAULT FALSE;
