-- Generic key/value app settings (user-configurable, non-secret). First use: the default
-- working directory for claude-cli terminal sessions that aren't bound to a repo.
CREATE TABLE app_config (
    key   VARCHAR(120) PRIMARY KEY,
    value TEXT
);
