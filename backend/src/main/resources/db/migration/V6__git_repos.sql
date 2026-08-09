-- Local git repositories the user manages through DevLoom (via the host agent).
-- Only a pointer (path) + light metadata is stored; live status comes from the agent.

CREATE TABLE git_repo (
    id         BIGSERIAL   PRIMARY KEY,
    path       VARCHAR(1024) NOT NULL UNIQUE,
    name       VARCHAR(256)  NOT NULL,
    host       VARCHAR(32),                  -- github | bitbucket | gitlab | git
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
