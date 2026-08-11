-- Per-session model (so a brainstorm/repo session remembers what it was created with), and a
-- per-repo local-only flag (a local-only repo may only use local models — never a remote one).
ALTER TABLE brainstorm_session ADD COLUMN model VARCHAR(120);
ALTER TABLE git_repo ADD COLUMN local_only BOOLEAN NOT NULL DEFAULT false;
