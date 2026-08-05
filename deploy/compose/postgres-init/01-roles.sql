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

-- The ledger is the exception: it needs *two* roles, not one (ADR-0016).
--
-- ADR-0008 promises that UPDATE and DELETE on postings are impossible rather than
-- merely forbidden. A single role cannot deliver that, because the role that runs the
-- migrations owns the tables, and an owner can always grant privileges back to itself.
-- So migrations run as the owner and the application connects as a separate role that
-- is granted SELECT and INSERT on postings and nothing more. The migration itself
-- issues those grants, at the end of V1.
CREATE ROLE maestro_ledger_migrator LOGIN PASSWORD 'maestro_ledger_migrator';
CREATE ROLE maestro_ledger LOGIN PASSWORD 'maestro_ledger';

CREATE SCHEMA ledger AUTHORIZATION maestro_ledger_migrator;
GRANT USAGE ON SCHEMA ledger TO maestro_ledger;

-- Each role sees only its own schema. Omitting the cross-grants is the enforcement.
ALTER ROLE maestro_payment SET search_path = payment;
ALTER ROLE maestro_routing SET search_path = routing;
ALTER ROLE maestro_ledger_migrator SET search_path = ledger;
ALTER ROLE maestro_ledger SET search_path = ledger;

-- Nothing belongs in the default schema; leaving it writable invites accidental
-- cross-service tables that would bypass the separation above.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
