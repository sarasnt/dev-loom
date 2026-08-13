-- Did the run do what it was asked? RunQuality scores process only — a run that described a repo
-- when told to review a pull request committed no process faults and scored 0.75, and nothing in
-- the system disagreed. AnswerJudge rules on adherence separately, so the two can differ out loud.
ALTER TABLE agent_run ADD COLUMN adherence_score NUMERIC(3,2);  -- 1.00 did it, 0.50 partly, 0.00 not
ALTER TABLE agent_run ADD COLUMN adherence_note  VARCHAR(200);  -- the judge's one line
ALTER TABLE agent_run ADD COLUMN grounded        BOOLEAN;       -- stays within what it looked at
