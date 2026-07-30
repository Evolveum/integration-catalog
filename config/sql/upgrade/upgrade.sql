--
-- Copyright (c) 2010-2025 Evolveum and contributors
--
-- Licensed under the EUPL-1.2 or later.
--

-- Cumulative database upgrade script.
--
-- Contains one section per schema version; every new schema change is APPENDED here as a
-- new section. The current schema version is MAX(version) in the database_version table.
--
-- Always re-run the WHOLE file against an existing database:
--
--   psql -v ON_ERROR_STOP=1 -U integration_catalog -d integration_catalog -f config/sql/upgrade/upgrade.sql
--
-- It is safe to run this script repeatedly, from psql as well as from tools that run the
-- whole script in one transaction (pgAdmin query tool). Sections up to version 4 are
-- idempotent statements; from version 5 on, every section is a `call apply_change(N, ...)`
-- which executes only when the database version is lower than N, so already-applied
-- sections are skipped and only the new ones take effect. The SQL inside apply_change
-- does NOT have to be idempotent.
--
-- apply_change must not COMMIT (a procedure cannot commit inside a wrapping transaction
-- block, e.g. under pgAdmin) — committing is left to the caller/tool. Never put an
-- explicit COMMIT inside a change either. A change that depends on an earlier change
-- being committed (typically ALTER TYPE ... ADD VALUE followed by a use of the new
-- value) cannot run in the same transaction: run the script with plain psql then, where
-- every call commits on its own.
--
-- Fresh installations do not need this file: config/sql/01_schema.sql already creates
-- the schema at the current version.

-- region version 1: database schema version tracking
CREATE TABLE IF NOT EXISTS database_version (
    version integer NOT NULL,
    description character varying(255) NOT NULL,
    applied_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT database_version_pkey PRIMARY KEY (version)
);

INSERT INTO database_version (version, description)
VALUES (1, 'Baseline schema (before REVIEWING state and connector.cloned_from)')
ON CONFLICT (version) DO NOTHING;
-- end of region

-- region version 2: REVIEWING lifecycle state and integration_method.reviewed_by
-- REVIEWING is positioned right after IN_REVIEW to match config/sql/01_schema.sql.
ALTER TYPE LifecycleType ADD VALUE IF NOT EXISTS 'REVIEWING' AFTER 'IN_REVIEW';

-- Records who is reviewing (set at start-review, kept on approve/reject).
ALTER TABLE integration_method ADD COLUMN IF NOT EXISTS reviewed_by character varying(255);

INSERT INTO database_version (version, description)
VALUES (2, 'REVIEWING lifecycle state and integration_method.reviewed_by')
ON CONFLICT (version) DO NOTHING;
-- end of region

-- region version 3: connector.cloned_from
-- Records the original connector a copy-on-write clone was made from; the approve step uses it
-- to fold a same-version metadata edit back into the shared original connector.
ALTER TABLE connector ADD COLUMN IF NOT EXISTS cloned_from integer;

INSERT INTO database_version (version, description)
VALUES (3, 'connector.cloned_from for copy-on-write connector clones')
ON CONFLICT (version) DO NOTHING;
-- end of region

-- region version 4: apply_change procedure for repeatable, non-idempotent upgrades
-- Inspired by midPoint's native repository upgrade mechanism (postgres-upgrade.sql).
-- This section itself stays in the old idempotent style (CREATE OR REPLACE) because the
-- procedure cannot be used to install itself; every section AFTER this one uses it.
CREATE OR REPLACE PROCEDURE apply_change(changeVersion int, changeDescription text, change TEXT, force boolean = false)
    LANGUAGE plpgsql
AS $$
DECLARE
    currentVersion int;
BEGIN
    SELECT max(version) INTO currentVersion FROM database_version;

    -- the change is executed only if its version is newer than the database version - or if forced
    IF currentVersion IS NULL OR currentVersion < changeVersion OR force THEN
        EXECUTE change;
        RAISE NOTICE 'Schema version % (%) applied', changeVersion, changeDescription;

        INSERT INTO database_version (version, description)
        VALUES (changeVersion, changeDescription)
        ON CONFLICT (version) DO NOTHING;
    ELSE
        RAISE NOTICE 'Schema version % skipped - database is already at version %', changeVersion, currentVersion;
    END IF;
END $$;

INSERT INTO database_version (version, description)
VALUES (4, 'apply_change procedure for repeatable non-idempotent upgrades')
ON CONFLICT (version) DO NOTHING;
-- end of region

-- region version 5: OIDC authentication — catalog_users.password no longer used
-- Authentication moved to Keycloak (OIDC); users are provisioned from the token at login
-- and no local password is stored for them anymore.
call apply_change(5, 'OIDC authentication: catalog_users.password made nullable', $aa$
ALTER TABLE catalog_users ALTER COLUMN password DROP NOT NULL;
$aa$);
-- end of region

-- region version 6: user data moved entirely to Keycloak
-- The catalog keeps no user, role or organization rows anymore: the logged-in user's
-- identity comes from the OIDC token claims and lookups about other users (ownership
-- checks, maintainer lists, organization members) go through the Keycloak Admin API.
-- The author/maintainer columns on catalog items are plain text and are unaffected.
call apply_change(6, 'user data moved entirely to Keycloak: drop catalog_users and organizations', $aa$
DROP TABLE IF EXISTS catalog_users;
DROP TABLE IF EXISTS organizations;
$aa$);
-- end of region

-- Append new version sections above this line. For every new version N (6 and higher):
--   1. add a "-- region version N: <name>" section here containing
--        call apply_change(N, '<short description>', $aa$
--        <any SQL, does not have to be idempotent>
--        $aa$);
--      ($aa$ dollar-quoting keeps inner $$ function bodies intact; the procedure
--      records version N in database_version by itself),
--   2. make the same change in config/sql/01_schema.sql and bump the version inserted
--      at the end of that script to N,
--   3. bump REQUIRED_VERSION in DatabaseSchemaVersionValidator to N.
