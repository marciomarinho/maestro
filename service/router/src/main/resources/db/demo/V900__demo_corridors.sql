-- Demo acquiring agreements.
--
-- Loaded only when `spring.flyway.locations` includes `classpath:db/demo`, matching the
-- convention payment-api already uses for its demo merchant: the seed is a deliberate
-- opt-in rather than something that follows the schema wherever it goes.
--
-- Three acquirers, priced as three real acquiring agreements plausibly would be. The
-- spread is what gives the router something to trade off: Southcross is the cheapest
-- and will win the steady state, Meridian is dear enough that it only earns traffic
-- when the others are unwell — which is exactly the situation the brownout demo
-- creates, and the reason cost is weighted below availability rather than above it.
--
--   southcross  cheapest    the incumbent's challenger
--   northbank   mid         the incumbent
--   meridian    dearest     the one you are glad you kept

INSERT INTO acquirer_corridor (acquirer_id, corridor, cost_bps, fixed_fee_minor) VALUES
    ('southcross', 'VISA:AUD',       115.00, 25),
    ('southcross', 'MASTERCARD:AUD', 125.00, 25),
    ('northbank',  'VISA:AUD',       130.00, 30),
    ('northbank',  'MASTERCARD:AUD', 135.00, 30),
    ('meridian',   'VISA:AUD',       160.00, 20),
    ('meridian',   'MASTERCARD:AUD', 165.00, 20)
ON CONFLICT (acquirer_id, corridor) DO NOTHING;
