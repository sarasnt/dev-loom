-- DevLoom initial schema (subset of SPEC.md §16 — enough for the MVP skeleton).
-- The unified WorkItem is the spine; signals/priority are computed in the app layer for now.

CREATE TABLE work_item (
    id          BIGSERIAL PRIMARY KEY,
    ext_id      VARCHAR(64)  NOT NULL,
    type        VARCHAR(24)  NOT NULL,   -- pr | build | task | review | stale
    title       VARCHAR(512) NOT NULL,
    status      VARCHAR(64)  NOT NULL,
    status_tone VARCHAR(16)  NOT NULL,   -- warn | fail | stale | healthy | info
    meta_csv    VARCHAR(256) NOT NULL DEFAULT '',
    source      VARCHAR(64)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_work_item_ext UNIQUE (source, ext_id)
);

CREATE INDEX idx_work_item_type ON work_item (type);

CREATE TABLE audit_event (
    id          BIGSERIAL PRIMARY KEY,
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(256) NOT NULL,
    metadata    VARCHAR(1024),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed the same work items the frontend stub uses, so the DB-backed API matches the UI.
INSERT INTO work_item (ext_id, type, title, status, status_tone, meta_csv, source, sort_order) VALUES
 ('482',  'pr',     '#482 · Checkout returns 500 under tier discount', 'review · mine', 'warn',    'wait 51h,acme/billing',  'GitHub',  1),
 ('1893', 'build',  'Build #1893 · test job failed',                   'failed',        'fail',    '22m,feature/pricing',    'GitHub',  2),
 ('T91',  'task',   'TICKET-91 · Checkout 500s under discount',        'in progress',   'info',    'P1,Jira',                'Jira',    3),
 ('455',  'stale',  '#455 · Refactor pricing tiers',                   'stale',         'stale',   'idle 4d,acme/billing',   'GitHub',  4),
 ('evt1', 'review', 'Release planning · standup',                      'today 15:00',   'info',    'Google',                 'Calendar',5),
 ('478',  'pr',     '#478 · Add idempotency keys to webhooks',         'merged',        'healthy', 'yesterday,acme/core',    'GitHub',  6);
