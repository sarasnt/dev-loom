-- Bring-your-own model provider API keys (SPEC.md §20). Stored encrypted at rest
-- (AES-GCM via DEVLOOM_SECRET); the ciphertext is opaque without the secret.

CREATE TABLE provider_credential (
    provider   VARCHAR(32)  PRIMARY KEY,   -- anthropic | openai
    enc_key    TEXT         NOT NULL,       -- base64(iv + ciphertext)
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
