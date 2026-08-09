-- Persistent brainstorming (SPEC.md §Brainstorming). Sessions + their messages survive
-- restarts and refreshes; the local model still generates the replies.

CREATE TABLE brainstorm_session (
    id         BIGSERIAL   PRIMARY KEY,
    title      VARCHAR(200) NOT NULL,
    visibility VARCHAR(20)  NOT NULL DEFAULT 'personal',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE brainstorm_message (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES brainstorm_session (id) ON DELETE CASCADE,
    seq        INT         NOT NULL,
    role       VARCHAR(8)  NOT NULL,   -- you | ai
    body       TEXT        NOT NULL,
    model      VARCHAR(96),
    hypothesis BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_brainstorm_msg_session ON brainstorm_message (session_id, seq);
