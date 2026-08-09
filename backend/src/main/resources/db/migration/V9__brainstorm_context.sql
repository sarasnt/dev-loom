-- Editable per-session brainstorm context: the repo (pinned) + added work items / files / notes.

CREATE TABLE brainstorm_context (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES brainstorm_session (id) ON DELETE CASCADE,
    kind       VARCHAR(24) NOT NULL,           -- repo | workitem | file | note
    ref        VARCHAR(1024),                  -- id/path/etc.
    label      VARCHAR(512) NOT NULL,
    pinned     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_brainstorm_context_session ON brainstorm_context (session_id, id);
