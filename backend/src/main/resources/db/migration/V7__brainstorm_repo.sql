-- Repo-scoped brainstorming: a session can be bound to a local repo, and its Claude Code
-- turns run in that repo's working dir with session continuity (docs/SPEC-sources.md §14).

ALTER TABLE brainstorm_session ADD COLUMN repo_path         VARCHAR(1024);
ALTER TABLE brainstorm_session ADD COLUMN claude_session_id VARCHAR(128);
