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
