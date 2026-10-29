-- Webhook signing secrets encrypted at rest (DMS-107, ADR 0035 addendum).
--
-- The application encrypts every secret with AES-256-GCM under a platform key from its configuration
-- (dms.webhooks.secretKeys); the database only stores the key id and the ciphertext and never sees the key.
-- Encrypting needs that key, so this migration cannot convert the existing plain-text secrets itself: it makes
-- room for the ciphertext, and every instance re-encrypts remaining plain-text secrets (and secrets of older
-- keys) at startup (WebhookSecretMigration). The plain-text column stays nullable for that transition; a later
-- migration drops it once no row uses it.

alter table dms.webhook_subscription
    alter column secret drop not null,
    -- Id of the platform key the secret is encrypted with, e.g. "2026-10".
    add column secret_key_id     text check (secret_key_id is null or secret_key_id ~ '^[A-Za-z0-9._-]{1,32}$'),
    -- base64 of nonce (12 bytes), ciphertext and GCM tag; bound to tenant and subscription id.
    add column secret_ciphertext text check (secret_ciphertext is null or length(secret_ciphertext) <= 200),
    add constraint webhook_subscription_secret_stored check (
        (secret_key_id is null) = (secret_ciphertext is null)
        and (secret_ciphertext is not null or secret is not null)
    );

-- Row-level security (V22) covers the new columns; the application may write them.
grant update (secret_key_id, secret_ciphertext) on dms.webhook_subscription to dms_app;
