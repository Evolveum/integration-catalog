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
-- Fresh installations DO need this file, and must run it right after postgres.sql:
--
--   psql ... -f config/sql/postgres.sql          -- baseline, stamps the change number it carries
--   psql ... -f config/sql/postgres-upgrade.sql  -- applies every change appended since
--
-- postgres.sql is not maintained alongside this file: schema changes are appended here alone, so
-- the baseline stays behind and this script closes the gap. Its trailing "call apply_change(N, ...,
-- true)" stamp must therefore be left exactly as it is - raising it to match a change defined only
-- here marks that change as applied without ever running it, and nothing afterwards will apply it,
-- because apply_change skips any number already recorded. The result is a database that reports the
-- right version while missing columns, which the startup check cannot detect.

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

-- region change 3: integration_method.support_ticket_id
-- Links an in-review revision to the work package opened for it in the support portal, so the
-- author and the reviewer discuss the submission there instead of in the catalog.
--
-- The column is deliberately not backfilled: revisions submitted before this change have no
-- work package, and one cannot be invented for them. They keep NULL and behave as they do
-- today - the reviewer approves without a ticket check.
--
-- A ticket belongs to the revision row, not to the method. Editing a published revision forks
-- a new row (see ConnectorUploadService.clonePublishedAsDraft), which starts with NULL here and
-- therefore gets its own work package; editing or resubmitting a revision that is still in
-- review writes to the same row and so keeps the work package it already has.
call apply_change(3, $aa$
ALTER TABLE integration_method ADD COLUMN IF NOT EXISTS support_ticket_id integer;
$aa$);
-- end of region

-- region change 4: catalog_users.email
-- Contact address for the people a submission names, so the support work package opened for a review
-- can say who to write to, and can subscribe them to it, instead of only recording who submitted.
-- Until now the catalog held no e-mail for anyone: catalog_users carried a username, a password, a
-- role and an organization.
--
-- Only people get an address. A submission maintained by an organization is represented by its
-- author, who is a member of that organization and the person who can act on what the review asks
-- for, so every address the catalog needs is a personal one.
--
-- Nullable and deliberately not backfilled - there is no address to invent for an existing row. A
-- submission by someone without one renders as a bare name, exactly as every submission does today.
--
-- 320 = 64 local part + "@" + 255 domain, the longest address RFC 5321 allows.
call apply_change(4, $aa$
ALTER TABLE catalog_users ADD COLUMN IF NOT EXISTS email varchar(320);
$aa$);
-- end of region

-- region change 5: pending_operation
-- Operations the catalog owes an external system, written down before they are attempted so that a
-- system which is temporarily unreachable delays an operation instead of losing it. A scheduled job
-- offers every row still marked PENDING back to its handler until it succeeds - see
-- PendingOperationRetryJob.
--
-- Deliberately generic. The first user is the support portal, whose work packages were until now
-- opened on a best-effort basis: a portal that was down left a log line and a submission with no
-- ticket, with nothing to find it by afterwards. Nothing about the table says so, though. It records
-- WHICH system owes the operation (target_system), WHICH operation of that system it is (operation)
-- and everything needed to perform it as opaque JSON (payload), whose shape is known only to the
-- handler registered for that pair. Another external system is therefore a new value in
-- target_system plus a handler bean, with no DDL and no change to the job.
--
-- operation is free text rather than an enum type: the set of operations belongs to whoever
-- integrates a system, and adding one must not mean altering a type shared by all of them.
--
-- No backfill. Rows begin with the first operation raised after this change; whatever was lost to an
-- outage before it was never recorded anywhere and cannot be reconstructed.
--
-- The index serves the only query there is - the job asking one system what it is still owed - and
-- covers the count beside it. Left as a plain composite index rather than a partial one on
-- status = 'PENDING': completed rows outnumber pending ones over time, but not by enough to be worth
-- an index whose predicate has to be repeated in every query that hopes to use it.
call apply_change(5, $aa$
CREATE TABLE IF NOT EXISTS pending_operation (
    id              bigserial PRIMARY KEY,
    target_system   varchar(50)  NOT NULL,
    operation       varchar(100) NOT NULL,
    payload         text         NOT NULL,
    status          varchar(20)  NOT NULL,
    attempts        integer      NOT NULL DEFAULT 0,
    created_at      timestamp    NOT NULL,
    last_attempt_at timestamp,
    last_error      text
);
CREATE INDEX IF NOT EXISTS idx_pending_operation_pending
    ON pending_operation (target_system, status, id);
$aa$);
-- end of region

-- region change 6: only ACTIVE connector bundles compete for a bundle name
-- unique_connector_bundle_bundle_name reserved (bundle_name, revision) across every lifecycle state,
-- so a draft could not carry the name of the published bundle it was cloned from. The workaround was
-- to hand the copy a suffixed revision (RepositoryUtil.uniqueBundleRevision), which then had to be
-- reclaimed when the copy replaced the original - and it also made two rows of the same Maven artifact
-- look like two different bundles.
--
-- A partial index instead: at most one ACTIVE bundle per (bundle_name, revision), with drafts and
-- rejected leftovers free to repeat it. Adding lifecycle_state to the constraint tuple would have done
-- the same for one draft but then collided on the second rejected draft of the same version.
call apply_change(6, $aa$
ALTER TABLE connector_bundle DROP CONSTRAINT IF EXISTS unique_connector_bundle_bundle_name;
CREATE UNIQUE INDEX unique_active_bundle_name
    ON connector_bundle (bundle_name, revision)
    WHERE lifecycle_state = 'ACTIVE';
$aa$);
-- end of region

-- region change 7: user data moved entirely to the identity provider
-- Users, roles and organizations live in the identity provider (claims 'role', 'group',
-- 'organization'); the application read them from token claims and, at the time, from the
-- provider's administration API. The author/maintainer columns are plain text and stay.
--
-- This drops catalog_users, which change 4 had just given an email column. That address was the
-- one thing the table still supplied that the token cannot - a token describes only its bearer,
-- so it can answer "the caller's address" but never "that other person's address". Change 8
-- therefore stamps author_email on the item at write time, the same way it stamps author_org_id,
-- and change 4 becomes a column that existed only between these two changes.
call apply_change(7, $aa$
DROP TABLE IF EXISTS catalog_users;
DROP TABLE IF EXISTS organizations;
$aa$);
-- end of region

-- region change 8: organizations table + organization stamped on catalog items
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
-- organizations are known - see change 9 below.
call apply_change(8, $aa$
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

-- 320 = 64 local part + "@" + 255 domain, the longest address RFC 5321 allows. Nullable and not
-- backfilled: rows written before this change have no address recorded and none can be recovered,
-- so a support work package for one of them simply names the author without a contact - exactly
-- what change 4 settled for when the address was missing from catalog_users.
ALTER TABLE connector                ADD COLUMN IF NOT EXISTS author_email character varying(320);
ALTER TABLE connector_version        ADD COLUMN IF NOT EXISTS author_email character varying(320);
ALTER TABLE connector_bundle         ADD COLUMN IF NOT EXISTS author_email character varying(320);
ALTER TABLE connector_bundle_version ADD COLUMN IF NOT EXISTS author_email character varying(320);
ALTER TABLE integration_method       ADD COLUMN IF NOT EXISTS author_email character varying(320);

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

-- region change 9: plain-text organization maintainers become references
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
call apply_change(9, $aa$
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
--   2. bump REQUIRED_VERSION in DatabaseSchemaVersionValidator to N.
--
-- Do NOT touch config/sql/postgres.sql - neither the schema in it nor its trailing apply_change
-- stamp. This file is the only place schema changes are written, and the reason is in the header:
-- raising that stamp records a change as applied without running it, and no later run can repair it.
--
-- Editing an already-applied section is limited to taking something out of it. A database that
-- recorded change N skips it forever, so the edit reaches only databases below N - silently, with no
-- error to notice. Dropping a statement is therefore survivable: databases above N keep whatever it
-- created as an unused leftover, which costs nothing. ADDING one is not: those databases would never
-- run it and would end up reporting version N while missing what it creates. Add by appending change
-- N+1 instead, and drop a leftover the same way if it is worth the DDL.
