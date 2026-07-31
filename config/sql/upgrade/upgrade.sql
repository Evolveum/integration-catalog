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
-- It is safe to run this script repeatedly. Sections up to version 4 are idempotent
-- statements; from version 5 on, every section is a `call apply_change(N, ...)` which
-- executes only when the database version is lower than N, so already-applied sections
-- are skipped and only the new ones take effect. The SQL inside apply_change does NOT
-- have to be idempotent.
--
-- Use plain psql, NOT tools with their own transaction handling (pgAdmin): apply_change
-- COMMITs internally, which fails inside a wrapping transaction block. For the same
-- reason never put an explicit COMMIT inside a change. If a later statement depends on
-- an earlier one being committed (typically ALTER TYPE ... ADD VALUE followed by a use
-- of the new value), split them into two apply_change calls.
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
        COMMIT;
    ELSE
        RAISE NOTICE 'Schema version % skipped - database is already at version %', changeVersion, currentVersion;
    END IF;
END $$;

INSERT INTO database_version (version, description)
VALUES (4, 'apply_change procedure for repeatable non-idempotent upgrades')
ON CONFLICT (version) DO NOTHING;
-- end of region

-- Append new version sections above this line. For every new version N (5 and higher):
--   1. add a "-- region version N: <name>" section here containing
--        call apply_change(N, '<short description>', $aa$
--        <any SQL, does not have to be idempotent>
--        $aa$);
--      ($aa$ dollar-quoting keeps inner $$ function bodies intact; the procedure
--      records version N in database_version and commits by itself),
--   2. make the same change in config/sql/01_schema.sql and bump the version inserted
--      at the end of that script to N,
--   3. bump REQUIRED_VERSION in DatabaseSchemaVersionValidator to N.
