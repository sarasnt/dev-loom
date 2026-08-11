-- Fleet: an AI run launched from DevLoom (interactive terminal or headless background claude -p).
CREATE TABLE agent_run (
    id                    BIGSERIAL PRIMARY KEY,
    title                 VARCHAR(300) NOT NULL,
    repo_path             TEXT NOT NULL,
    run_dir               TEXT,                    -- worktree dir, or repo_path when not isolated
    branch                VARCHAR(255),            -- devloom/run-<id> when isolated
    kind                  VARCHAR(16) NOT NULL,    -- 'interactive' | 'background'
    permission            VARCHAR(16),             -- 'readonly' | 'edit' (background)
    allow_tests           BOOLEAN NOT NULL DEFAULT false,
    isolated              BOOLEAN NOT NULL DEFAULT false,
    model                 VARCHAR(120),
    status                VARCHAR(16) NOT NULL,    -- running|review|done|failed|canceled|active|ended
    agent_run_id          VARCHAR(80),             -- the host agent's in-memory run id
    claude_session_id     VARCHAR(80),
    brainstorm_session_id BIGINT,
    result_summary        TEXT,
    error                 TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ
);
CREATE INDEX idx_agent_run_status ON agent_run (status, created_at DESC);
