-- Master secret for encrypting app-stored credentials. Generated on first use (or seeded from
-- DEVLOOM_SECRET if that was set) and persisted, so keys can be stored without manual setup.

CREATE TABLE app_secret (
    name       VARCHAR(64) PRIMARY KEY,
    value      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
