-- Multi-source configuration (docs/SPEC-sources.md). Named, user-configured source instances
-- replace the hardcoded single connectors. Each instance's credential is encrypted at rest.

CREATE TABLE source_instance (
    id          BIGSERIAL   PRIMARY KEY,
    type        VARCHAR(32)  NOT NULL,          -- jira | github | bitbucket | calendar | notion
    deployment  VARCHAR(16)  NOT NULL DEFAULT 'cloud',
    name        VARCHAR(80)  NOT NULL UNIQUE,    -- e.g. "CSW Jira" — becomes item.source
    base_url    VARCHAR(512),
    config_json TEXT,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE source_credential (
    instance_id BIGINT      PRIMARY KEY REFERENCES source_instance (id) ON DELETE CASCADE,
    enc_secret  TEXT        NOT NULL,            -- base64(iv||ciphertext) of the secret map
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE work_item ADD COLUMN source_instance_id BIGINT;
CREATE INDEX idx_work_item_instance ON work_item (source_instance_id);

-- Pre-migration rows have no instance id and would collide (same source name, same ext_id)
-- with the instance-scoped re-sync. They're all re-synced on boot, so clear them.
DELETE FROM work_item;
