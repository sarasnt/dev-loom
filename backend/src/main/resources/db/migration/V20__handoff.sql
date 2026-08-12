-- Generated handoff artifacts. Previously a handoff was assembled on demand and never kept, so
-- "Generate handoff" produced something you could only read once: navigating away lost it, and
-- there was no way to see the one you made this morning. Keeping them makes the screen a list of
-- your handoffs rather than a view of the newest failure.
--
-- The rendered markdown is stored as generated. Re-deriving it later would silently change it —
-- the analysis is a model call, and the log it quotes ages out of the CI provider.
CREATE TABLE handoff (
    id          BIGSERIAL PRIMARY KEY,
    build_id    VARCHAR(120),                 -- the run it came from, for re-opening the failure
    title       VARCHAR(300) NOT NULL,
    repo        VARCHAR(300),
    branch      VARCHAR(300),
    target      VARCHAR(60),                  -- which agent it was written for
    rendered    TEXT NOT NULL,                -- the artifact, exactly as handed over
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_handoff_created ON handoff (created_at DESC);
