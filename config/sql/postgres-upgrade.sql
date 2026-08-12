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
