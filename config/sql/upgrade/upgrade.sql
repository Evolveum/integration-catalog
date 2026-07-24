--
-- Copyright (c) 2010-2025 Evolveum and contributors
--
-- Licensed under the EUPL-1.2 or later.
--

-- Cumulative database upgrade script.
--
-- Contains one section per schema version; every new schema change is APPENDED here as a
-- new section that ends by inserting its version row into database_version. The current
-- schema version is MAX(version) in that table.
--
-- Always re-run the WHOLE file against an existing database:
--
--   psql -U integration_catalog -d integration_catalog -f config/sql/upgrade/upgrade.sql
--
-- Every section must stay idempotent (IF NOT EXISTS / ON CONFLICT DO NOTHING), so
-- already-applied sections are harmless no-ops and only the new ones take effect.
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

-- Append new version sections above this line. For every new version N:
--   1. add a "-- region version N: <name>" section here with idempotent statements,
--      ending with the INSERT of row N into database_version,
--   2. make the same change in config/sql/01_schema.sql and bump the version inserted
--      at the end of that script to N,
--   3. bump REQUIRED_VERSION in DatabaseSchemaVersionValidator to N.
