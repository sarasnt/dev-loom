-- A session opened in claude-cli mode keeps its conversation in the terminal (Claude Code's
-- own transcript), not in brainstorm_message. Mark such sessions so the UI keeps them as a
-- terminal and doesn't flip them to a chat model (which would show an empty pane).
ALTER TABLE brainstorm_session ADD COLUMN cli_mode boolean NOT NULL DEFAULT false;
