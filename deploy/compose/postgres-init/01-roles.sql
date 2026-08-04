-- Database roles and schemas.
--
-- One PostgreSQL instance, one schema per service, and one login role per schema
-- (ADR-0010, ADR-0014). No role is granted anything on another service's schema, so
-- "no service reads another service's tables" fails at runtime rather than at code
-- review. Each service's Flyway migrations then create its own tables as its own role.
--
-- Passwords are local-only fixtures. Nothing in this platform ever holds a real
-- credential (ADR-0010).

CREATE ROLE maestro_payment LOGIN PASSWORD 'maestro_payment';
CREATE ROLE maestro_routing LOGIN PASSWORD 'maestro_routing';

CREATE SCHEMA payment AUTHORIZATION maestro_payment;
CREATE SCHEMA routing AUTHORIZATION maestro_routing;

-- Each role sees only its own schema. Omitting the cross-grants is the enforcement.
ALTER ROLE maestro_payment SET search_path = payment;
ALTER ROLE maestro_routing SET search_path = routing;

-- Nothing belongs in the default schema; leaving it writable invites accidental
-- cross-service tables that would bypass the separation above.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
