-- Model monitoring history. The Monitoring panel used to read an in-memory ring, so every backend
-- restart reset it to zero — the numbers described the current process, not your usage. Persisting
-- each observed call makes the panel a history; old rows are pruned on a retention window.
CREATE TABLE llm_call (
    id            BIGSERIAL PRIMARY KEY,
    at            TIMESTAMPTZ NOT NULL,
    provider      VARCHAR(60)  NOT NULL,
    model         VARCHAR(160) NOT NULL,
    latency_ms    BIGINT       NOT NULL,
    input_tokens  BIGINT       NOT NULL DEFAULT 0,
    output_tokens BIGINT       NOT NULL DEFAULT 0,
    ok            BOOLEAN      NOT NULL,
    error         TEXT         -- provider message on failure; never prompt/response text
);

-- Every query is "recent first" or "since a cutoff", both served by this.
CREATE INDEX idx_llm_call_at ON llm_call (at DESC);
