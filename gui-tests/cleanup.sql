-- Copyright (c) 2010-2026 Evolveum and contributors
--
-- Licensed under the EUPL-1.2 or later.
--
-- Removes the data the GUI tests create: applications named e2e_<timestamp>_app, their integration
-- methods and connectors named e2e_<timestamp>_connector.
--
-- Rolls back unless told otherwise, so a plain run only shows what would be deleted:
--   psql -h <host> -U <user> -d <database> -f gui-tests/cleanup.sql
-- Deletes for real:
--   psql -h <host> -U <user> -d <database> -v commit=1 -f gui-tests/cleanup.sql
--
-- Needs psql 10 or newer. Stops without deleting anything if real data depends on the test data.
-- Keeps the shared maintainers and authors rows. Tickets in the support portal are not touched:
-- the listing below shows their numbers, so they can be closed by hand.

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE e2e_applications ON COMMIT DROP AS
    SELECT id, display_name
    FROM application
    WHERE display_name ~ '^e2e_[0-9]+_app$';

CREATE TEMP TABLE e2e_methods ON COMMIT DROP AS
    SELECT id, revision, display_name, lifecycle_state, support_ticket_id
    FROM integration_method
    WHERE application_id IN (SELECT id FROM e2e_applications);

CREATE TEMP TABLE e2e_connectors ON COMMIT DROP AS
    SELECT id, display_name, connector_bundle_id
    FROM connector
    WHERE display_name ~ '^e2e_[0-9]+_connector$';

-- Deleting a row below cascades, so anything real attached to test data would silently go with it.
DO $$
DECLARE
    offenders text;
BEGIN
    SELECT string_agg(display_name, ', ') INTO offenders
    FROM e2e_methods
    WHERE display_name !~ '^e2e_[0-9]+_method$';
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'Test applications hold methods that are not test data: %', offenders;
    END IF;

    SELECT string_agg(DISTINCT im.display_name, ', ') INTO offenders
    FROM integration_method_connector imc
    JOIN integration_method im
        ON im.id = imc.integ_method_id AND im.revision = imc.integ_method_revision
    WHERE imc.connector_id IN (SELECT id FROM e2e_connectors)
        AND im.id NOT IN (SELECT id FROM e2e_methods);
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'Test connectors are used by methods that are not test data: %', offenders;
    END IF;

    SELECT string_agg(c.display_name, ', ') INTO offenders
    FROM connector c
    WHERE c.connector_bundle_id IN (SELECT connector_bundle_id FROM e2e_connectors)
        AND c.id NOT IN (SELECT id FROM e2e_connectors);
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'Test bundles hold connectors that are not test data: %', offenders;
    END IF;
END $$;

\echo
\echo Target database:
SELECT current_database() AS database, inet_server_addr() AS server;

\echo Applications:
SELECT display_name FROM e2e_applications ORDER BY display_name;

\echo Integration methods:
SELECT display_name, revision, lifecycle_state, support_ticket_id
FROM e2e_methods
ORDER BY display_name, revision;

\echo Connectors:
SELECT id, display_name, connector_bundle_id FROM e2e_connectors ORDER BY id;

-- No foreign key leads here: the retry job's rows name their method inside the JSON payload.
\echo Pending operations:
DELETE FROM pending_operation p
WHERE EXISTS (SELECT 1 FROM e2e_methods m WHERE p.payload LIKE '%' || m.id::text || '%');

-- Cascades to methods, their capabilities and connector links, requests, votes and recently used.
\echo Applications:
DELETE FROM application WHERE id IN (SELECT id FROM e2e_applications);

-- Cascades to connectors, connector versions, bundle versions, downloads and maintainer links.
\echo Connector bundles:
DELETE FROM connector_bundle WHERE id IN (SELECT connector_bundle_id FROM e2e_connectors);

\if :{?commit}
    COMMIT;
    \echo Committed: the rows above are deleted.
\else
    ROLLBACK;
    \echo Rolled back: nothing was deleted. Add -v commit=1 to delete.
\endif
