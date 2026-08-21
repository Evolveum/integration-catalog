--
-- Copyright (c) 2010-2025 Evolveum and contributors
--
-- Licensed under the EUPL-1.2 or later.
--

-- Cumulative database upgrade script.
--
-- Contains one apply_change section per schema change; every new schema change is
-- APPENDED here as a new section. The current change number is stored in the
-- m_global_metadata table under the 'schemaChangeNumber' key - the same mechanism as
-- midPoint's native repository (postgres.sql / postgres-upgrade.sql). The table and the
-- apply_change procedure are created by config/sql/postgres.sql.
--
-- Always re-run the WHOLE file against an existing database:
--
--   psql -v ON_ERROR_STOP=1 -U integration_catalog -d integration_catalog -f config/sql/postgres-upgrade.sql
--
-- It is safe to run this script repeatedly: apply_change(N, ...) executes only when the
-- stored change number is lower than N, so already-applied sections are skipped and only
-- the new ones take effect. The SQL inside apply_change does NOT have to be idempotent.
--
-- Use plain psql, NOT tools with their own transaction handling (pgAdmin):
-- apply_change uses transaction-level advisory locking and relies on the
-- transaction handling of the CALL statement.
-- Never put an explicit COMMIT inside a change. If a later statement depends on
-- an earlier one being committed (typically ALTER TYPE ... ADD VALUE followed by a use
-- of the new value), split them into two apply_change calls.
--
-- Fresh installations do not need this file: config/sql/postgres.sql already creates
-- the schema at the current change number.

DO $$
    begin
        if to_regproc('apply_change') is null then
            raise exception 'You are running the UPGRADE script, but the procedure ''apply_change'' is missing.
Are you sure you are running this upgrade script on the correct database?
Current database name is ''%'', schema name is ''%''.', current_database(), current_schema();
        end if;
    END
$$;

-- region change 2: connector.cloned_from
-- Records the original connector a copy-on-write clone was made from; the approve step
-- uses it to fold a same-version metadata edit back into the shared original connector.
-- (Change 1 is the baseline schema created by config/sql/postgres.sql.)
call apply_change(2, $aa$
alter table connector add COLUMN IF NOT EXISTS cloned_from integer;
$aa$);
-- end of region

-- region change 3: user data moved entirely to the identity provider
-- Users, roles and organizations live in the identity provider (claims 'role', 'group',
-- 'organization'); the application read them from token claims and, at the time, from the
-- provider's administration API. The author/maintainer columns are plain text and stay.
call apply_change(3, $aa$
DROP TABLE IF EXISTS catalog_users;
DROP TABLE IF EXISTS organizations;
$aa$);
-- end of region

-- region change 4: organizations table + organization stamped on catalog items
-- The application stops calling the identity provider's administration API. Everything it
-- knows about the logged-in user comes from the OIDC token claims, so users, roles and
-- groups stay with the provider and no user table comes back. Organizations are the one
-- exception: the claim carries only the organization's identifier, so the display name
-- has to live somewhere - here.
--
-- organizations.id is that identifier (immutable, unlike the name), which is what makes a
-- rename one UPDATE of organizations.name instead of a sweep over five tables.
--
-- Because a token only describes its own bearer, facts about *other* users are recorded
-- on the item when it is written: author_org_id, maintainer_org_id (set when an
-- organization rather than a person maintains the item - the maintainer column then holds
-- a username only) and author_category. Existing rows are converted once the
-- organizations are known - see change 5 below.
call apply_change(4, $aa$
CREATE TABLE IF NOT EXISTS organizations (
    id          character varying(255) NOT NULL,
    name        character varying(255) NOT NULL,
    description text
);

ALTER TABLE ONLY organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);

ALTER TABLE connector                ADD COLUMN IF NOT EXISTS maintainer_org_id character varying(255);
ALTER TABLE connector_version        ADD COLUMN IF NOT EXISTS maintainer_org_id character varying(255);
ALTER TABLE connector_bundle         ADD COLUMN IF NOT EXISTS maintainer_org_id character varying(255);
ALTER TABLE connector_bundle_version ADD COLUMN IF NOT EXISTS maintainer_org_id character varying(255);
ALTER TABLE integration_method       ADD COLUMN IF NOT EXISTS maintainer_org_id character varying(255);

ALTER TABLE connector                ADD COLUMN IF NOT EXISTS author_org_id character varying(255);
ALTER TABLE connector_version        ADD COLUMN IF NOT EXISTS author_org_id character varying(255);
ALTER TABLE connector_bundle         ADD COLUMN IF NOT EXISTS author_org_id character varying(255);
ALTER TABLE connector_bundle_version ADD COLUMN IF NOT EXISTS author_org_id character varying(255);
ALTER TABLE integration_method       ADD COLUMN IF NOT EXISTS author_org_id character varying(255);

ALTER TABLE connector                ADD COLUMN IF NOT EXISTS author_category character varying(32);
ALTER TABLE connector_version        ADD COLUMN IF NOT EXISTS author_category character varying(32);
ALTER TABLE connector_bundle         ADD COLUMN IF NOT EXISTS author_category character varying(32);
ALTER TABLE connector_bundle_version ADD COLUMN IF NOT EXISTS author_category character varying(32);
ALTER TABLE integration_method       ADD COLUMN IF NOT EXISTS author_category character varying(32);

ALTER TABLE ONLY connector
    ADD CONSTRAINT fk_conn_maintainer_org FOREIGN KEY (maintainer_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY connector_version
    ADD CONSTRAINT fk_conn_version_maintainer_org FOREIGN KEY (maintainer_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY connector_bundle
    ADD CONSTRAINT fk_conn_bundle_maintainer_org FOREIGN KEY (maintainer_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY connector_bundle_version
    ADD CONSTRAINT fk_conn_bundle_version_maintainer_org FOREIGN KEY (maintainer_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY integration_method
    ADD CONSTRAINT fk_integ_method_maintainer_org FOREIGN KEY (maintainer_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY connector
    ADD CONSTRAINT fk_conn_author_org FOREIGN KEY (author_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY connector_version
    ADD CONSTRAINT fk_conn_version_author_org FOREIGN KEY (author_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY connector_bundle
    ADD CONSTRAINT fk_conn_bundle_author_org FOREIGN KEY (author_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY connector_bundle_version
    ADD CONSTRAINT fk_conn_bundle_version_author_org FOREIGN KEY (author_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY integration_method
    ADD CONSTRAINT fk_integ_method_author_org FOREIGN KEY (author_org_id) REFERENCES organizations(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_conn_maintainer_org         ON connector USING btree (maintainer_org_id);
CREATE INDEX idx_cver_maintainer_org         ON connector_version USING btree (maintainer_org_id);
CREATE INDEX idx_cbundle_maintainer_org      ON connector_bundle USING btree (maintainer_org_id);
CREATE INDEX idx_cbundle_ver_maintainer_org  ON connector_bundle_version USING btree (maintainer_org_id);
CREATE INDEX idx_integ_method_maintainer_org ON integration_method USING btree (maintainer_org_id);
CREATE INDEX idx_conn_author_org             ON connector USING btree (author_org_id);
CREATE INDEX idx_cver_author_org             ON connector_version USING btree (author_org_id);
CREATE INDEX idx_cbundle_author_org          ON connector_bundle USING btree (author_org_id);
CREATE INDEX idx_cbundle_ver_author_org      ON connector_bundle_version USING btree (author_org_id);
CREATE INDEX idx_integ_method_author_org     ON integration_method USING btree (author_org_id);
$aa$);
-- end of region

-- region change 5: plain-text organization maintainers become references
-- Completes change 4 for databases that already held data. Before it, an item maintained by
-- an organization carried the organization's display name in the maintainer column, which is
-- exactly what a rename used to orphan; now it carries maintainer_org_id and the maintainer
-- column holds a username only.
--
-- IMPORTANT: this converts only what matches a row in the organizations table, which change 4
-- created EMPTY. Insert the environment's organizations BEFORE running this script - the
-- change runs once, so an organization added afterwards will not be picked up and its items
-- keep their plain-text maintainer.
--
-- author_org_id and author_category are deliberately not filled in here: they describe the
-- uploader's role and organization at the time of upload, which no longer exists anywhere in
-- the database and cannot be recovered from it. Rows without them behave as personal items -
-- the author keeps access - and they are stamped the next time the item is written.
call apply_change(5, $aa$
UPDATE connector c
    SET maintainer_org_id = o.id, maintainer = NULL
    FROM organizations o
    WHERE lower(c.maintainer) = lower(o.name) AND c.maintainer_org_id IS NULL;

UPDATE connector_version cv
    SET maintainer_org_id = o.id, maintainer = NULL
    FROM organizations o
    WHERE lower(cv.maintainer) = lower(o.name) AND cv.maintainer_org_id IS NULL;

UPDATE connector_bundle cb
    SET maintainer_org_id = o.id, maintainer = NULL
    FROM organizations o
    WHERE lower(cb.maintainer) = lower(o.name) AND cb.maintainer_org_id IS NULL;

UPDATE connector_bundle_version cbv
    SET maintainer_org_id = o.id, maintainer = NULL
    FROM organizations o
    WHERE lower(cbv.maintainer) = lower(o.name) AND cbv.maintainer_org_id IS NULL;

UPDATE integration_method im
    SET maintainer_org_id = o.id, maintainer = NULL
    FROM organizations o
    WHERE lower(im.maintainer) = lower(o.name) AND im.maintainer_org_id IS NULL;
$aa$);
-- end of region

-- Append new apply_change sections above this line. For every new change N (3 and higher):
--   1. add a "-- region change N: <name>" section here containing
--        call apply_change(N, $aa$
--        <any SQL, does not have to be idempotent>
--        $aa$);
--      ($aa$ dollar-quoting keeps inner $$ function bodies intact; the procedure advances 'schemaChangeNumber'
--      in m_global_metadata within the same transaction.
--   2. make the same change in config/sql/postgres.sql and bump the number in the
--      "call apply_change" at the end of that script to N,
--   3. bump REQUIRED_VERSION in DatabaseSchemaVersionValidator to N.
