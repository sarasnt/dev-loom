-- Point-in-time snapshot of the work set, for since-yesterday diffs and new-urgent detection.
CREATE TABLE briefing_snapshot (
    id         BIGSERIAL PRIMARY KEY,
    taken_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind       VARCHAR(16) NOT NULL,        -- 'digest' | 'sync'
    items_json TEXT NOT NULL                -- JSON array of {extId,type,source,status,urgencyKey}
);
CREATE INDEX idx_briefing_snapshot_kind_taken ON briefing_snapshot (kind, taken_at DESC);

-- Per-work-item user state that must outlive a sync (keyed by source-stable ext id).
CREATE TABLE work_item_flag (
    ext_id     VARCHAR(190) PRIMARY KEY,
    handled_at TIMESTAMPTZ,
    planned_at TIMESTAMPTZ
);
