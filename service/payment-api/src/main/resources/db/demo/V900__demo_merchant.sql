-- Demo fixtures.
--
-- Loaded only when `spring.flyway.locations` includes `classpath:db/demo`, which the
-- local and docker profiles do and nothing else does. Keeping it out of
-- `db/migration` means the seed is a deliberate opt-in rather than something that
-- would follow the schema into an environment that should not have it.
--
-- The key below is a fixed local credential so the README quickstart is three lines.
-- It grants access to nothing but this simulated platform.
--   plaintext : sk_test_maestro_demo_0001
--   stored    : SHA-256 hex

INSERT INTO merchant (id, name, status, default_currency)
VALUES ('mch_demo', 'Demo Merchant', 'ACTIVE', 'AUD')
ON CONFLICT (id) DO NOTHING;

INSERT INTO api_key (id, merchant_id, key_prefix, key_hash, role)
VALUES ('ak_demo',
        'mch_demo',
        'sk_test_maestro',
        '1c361632564a69b84bc712580505c85f8af16e34eb11cbc1e17591c509eb172b',
        'merchant_admin')
ON CONFLICT (key_hash) DO NOTHING;
