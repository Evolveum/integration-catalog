--
-- Copyright (c) 2010-2025 Evolveum and contributors
--
-- Licensed under the EUPL-1.2 or later.
--
-- Seed/demo data. Run it LAST, after both schema scripts, in one transaction:
--
--   psql ... -f config/sql/postgres.sql
--   psql ... -f config/sql/postgres-upgrade.sql
--   psql ... -1 -f src/test/resources/sql/testing_data.sql
--
-- Users, roles and group membership live entirely in the identity provider (see
-- keycloak_for_auth/import/integration-catalog-realm.json for the dev test users):
--   kcuser, u5 = Superuser (Evolveum)      u1 = OrganizationContributor (Acme)
--   u3, u4     = IndividualContributor     u2 = ReadOnly
-- The catalog only records who authored an item (authors) and who maintains it (maintainers);
-- the maintainer alone decides who may edit (AuthService.canEdit). The four published
-- integration methods of "My Test App" cover one maintainer kind each, so the "Edit and upgrade"
-- flow can be tried from every role:
--   Test 1 - maintained by Evolveum   -> superusers only
--   Test 2 - maintained by Acme (org) -> u1 + superusers
--   Test 3 - maintained by u3         -> u3 + superusers (not u4)
--   Test 4 - maintained by Community  -> every contributor
--
-- postgres.sql already seeds maintainers 1 (COMMUNITY) and 2 (EVOLVEUM) and author 1, so rows here
-- start above those. Every identity sequence is re-synced at the end.

-- ============================================================
-- ORGANIZATIONS, AUTHORS, MAINTAINERS
-- name = the Keycloak organization alias carried in the token claim
-- ============================================================

INSERT INTO organizations (id, name, display_name, description) OVERRIDING SYSTEM VALUE VALUES
    (1, 'evolveum', 'Evolveum', 'Maintainer of midPoint and the Integration catalog'),
    (2, 'acme',     'Acme co.', 'Demo partner organization');

INSERT INTO authors (id, username, email) OVERRIDING SYSTEM VALUE VALUES
    (2, 'u5', 'u5@example.com'),
    (3, 'u1', 'u1@example.com'),
    (4, 'u3', 'u3@example.com'),
    (5, 'u4', 'u4@example.com');

INSERT INTO maintainers (id, username, organization_id, category) OVERRIDING SYSTEM VALUE VALUES
    (3, NULL, 2,    'ORG'),
    (4, 'u3', NULL, 'USER');

-- ============================================================
-- LOOKUP TABLES
-- ============================================================

INSERT INTO application_tag (id, name, display_name, tag_type) OVERRIDING SYSTEM VALUE VALUES
    (9, 'us', 'USA based', 'LOCALITY');

INSERT INTO country_of_origin (id, name, display_name) OVERRIDING SYSTEM VALUE VALUES
    (1, 'czech_republic', 'Czech Republic'),
    (2, 'united_states_of_america', 'United States of America'),
    (3, 'germany', 'Germany');

-- ============================================================
-- APPLICATIONS
-- ============================================================

INSERT INTO application (id, name, display_name, description, lifecycle_state, created_at, updated, logo_path) VALUES
    ('11111111-1111-1111-1111-111111111111', 'my_test_app', 'My Test App',
     'Demo application with one published integration method per maintainer kind', 'ACTIVE', NOW(), NOW(), NULL),
    ('22222222-2222-2222-2222-222222222222', 'sap_hr', 'SAP HR',
     'SAP Human Resources system integration requested by the community. Some more text to test limit of chars that can hold in this DB column.'
     'SAP Human SAP Human SAP Human SAP Human SAP Human SAP Human SAP Human SAP .'
     'Do not know what else to write, so please e long enought. I am out of idea what to write more, just hit that 255 break mark. Here it is.', 'REQUESTED', NOW(), NOW(), NULL),
    ('33333333-3333-3333-3333-333333333333', 'empty_app', 'Empty App',
     'Existing published application that currently has no integration methods or connectors', 'ACTIVE', NOW(), NOW(), NULL);

INSERT INTO application_application_tag (application_id, tag_id) VALUES
    ('11111111-1111-1111-1111-111111111111', 1),
    ('11111111-1111-1111-1111-111111111111', 6),
    ('33333333-3333-3333-3333-333333333333', 2),
    ('33333333-3333-3333-3333-333333333333', 5);

INSERT INTO application_origin (application_id, country_id) VALUES
    ('11111111-1111-1111-1111-111111111111', 1),
    ('33333333-3333-3333-3333-333333333333', 3);

-- ============================================================
-- CONNECTOR BUNDLES, BUNDLE VERSIONS, CONNECTORS, CONNECTOR VERSIONS
-- ============================================================

INSERT INTO connector_bundle (id, revision, author, created_at, updated, lifecycle_state,
    bundle_name, display_name, description, framework, license, ticketing_link, project_homepage,
    git_clone_ulr, path_to_project, build_framework)
OVERRIDING SYSTEM VALUE VALUES
    (1, '1.0', 2, NOW(), NOW(), 'ACTIVE', 'connector-ldap', 'LDAP Connector Bundle',
     'ConnId LDAP connector', 'JAVA_BASED', 'APACHE_2', 'https://github.com/Evolveum/connector-ldap/issues',
     'https://github.com/Evolveum/connector-ldap', 'https://github.com/Evolveum/connector-ldap.git', NULL, 'MAVEN'),
    (2, '1.0', 3, NOW(), NOW(), 'ACTIVE', 'connector-servicenow', 'ServiceNow Connector Bundle',
     'ConnId ServiceNow connector, low code', 'LOW_CODE', 'MIT', 'https://github.com/ExampleOrg/connector-servicenow/issues',
     'https://github.com/ExampleOrg/connector-servicenow', 'https://github.com/ExampleOrg/connector-servicenow.git', NULL, 'GRADLE'),
    (3, '1.0', 2, NOW(), NOW(), 'ACTIVE', 'com.evolveum.polygon.connector-csv', 'CSV File Connector Bundle',
     'ConnId CSV file connector', 'JAVA_BASED', 'APACHE_2', 'https://github.com/Evolveum/connector-csv/issues',
     'https://github.com/Evolveum/connector-csv', 'https://github.com/Evolveum/connector-csv.git', NULL, 'MAVEN');

INSERT INTO connector_bundle_maintainers (connector_bundle_id, maintainer_id) VALUES
    (1, 2),
    (2, 3),
    (3, 2);

INSERT INTO connector_bundle_version (id, revision, author, created_at, updated, lifecycle_state,
    connector_bundle_id, bundle_version, browse_link, git_clone_ulr, path_to_project, build_framework,
    commit_tag, artifact_url, error_message)
OVERRIDING SYSTEM VALUE VALUES
    (1, '1.0', 2, NOW(), NOW(), 'ACTIVE', 1, '3.8', 'https://github.com/Evolveum/connector-ldap/tree/v3.8',
     'https://github.com/Evolveum/connector-ldap.git', NULL, 'MAVEN', 'v3.8', NULL, NULL),
    (2, '1.0', 3, NOW(), NOW(), 'ACTIVE', 2, '1.5.0', 'https://github.com/ExampleOrg/connector-servicenow/tree/1.5.0',
     'https://github.com/ExampleOrg/connector-servicenow.git', NULL, 'GRADLE', '1.5.0', NULL, NULL),
    (3, '1.0', 2, NOW(), NOW(), 'ACTIVE', 3, '2.9',
     'https://nexus.evolveum.com/nexus/#browse/browse:releases:com%2Fevolveum%2Fpolygon%2Fconnector-csvfile',
     'https://github.com/Evolveum/connector-csv.git', NULL, 'MAVEN', 'v2.9',
     'https://nexus.evolveum.com/nexus/repository/releases/com/evolveum/polygon/connector-csvfile/1.4.2.0/connector-csvfile-1.4.2.0.jar', NULL);

INSERT INTO connector_bundle_version_maintainers (connector_bundle_version_id, connector_bundle_version_revision, maintainer_id) VALUES
    (1, '1.0', 2),
    (2, '1.0', 3),
    (3, '1.0', 2);

INSERT INTO connector (id, revision, author, maintainer, created_at, updated, display_name,
    fully_qualified_class_name, connector_bundle_id, description, cloned_from)
OVERRIDING SYSTEM VALUE VALUES
    (1, '1.0', 2, 2, NOW(), NOW(), 'LDAP Connector',
     'com.evolveum.polygon.connector.ldap.LdapConnector', 1, 'LDAP connector', NULL),
    (2, '1.0', 3, 3, NOW(), NOW(), 'ServiceNow Connector',
     'com.example.connector.servicenow.ServiceNowConnector', 2, 'ServiceNow low-code connector', NULL),
    (3, '1.0', 2, 2, NOW(), NOW(), 'CSV File Connector',
     'com.evolveum.polygon.connector.csv.CsvConnector', 3, 'CSV file connector', NULL);

INSERT INTO connector_connector_tag (connector_id, tag_id) VALUES
    (1, 1);

-- The obsolete tag comes from a postgres-upgrade.sql change, so it is looked up by name (no row when absent).
INSERT INTO connector_connector_tag (connector_id, tag_id)
SELECT 3, id FROM connector_tag WHERE name = 'obsolete';

INSERT INTO connector_version (id, revision, author, maintainer, created_at, updated,
    lifecycle_state, connector_bundle_version_id, connector_bundle_version_revision,
    connector_id, fully_qualified_class_name, error_message)
OVERRIDING SYSTEM VALUE VALUES
    (1, '1.0', 2, 2, NOW(), NOW(), 'ACTIVE', 1, '1.0', 1, 'com.evolveum.polygon.connector.ldap.LdapConnector', NULL),
    (2, '1.0', 3, 3, NOW(), NOW(), 'ACTIVE', 2, '1.0', 2, 'com.example.connector.servicenow.ServiceNowConnector', NULL),
    (3, '1.0', 2, 2, NOW(), NOW(), 'ACTIVE', 3, '1.0', 3, 'com.evolveum.polygon.connector.csv.CsvConnector', NULL);

-- ============================================================
-- INTEGRATION METHODS  (composite PK uuid + revision; midpoint versions are midpoint_version ids)
-- ============================================================

INSERT INTO integration_method (id, application_id, display_name, description, limitations,
     tutorial, file_path, midpoint_minversion, midpoint_maxversion, lifecycle_state, revision,
     author, maintainer, created_at, updated, app_version, reviewed_by)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111',
     'Test 1 - Evolveum maintained', 'LDAP-based method maintained by Evolveum',
     'Nested groups are not resolved.', 'Tutorial 1', NULL, 5, 9, 'ACTIVE', '1.0', 2, 2, NOW(), NOW(), '2025.1', 'u5'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111',
     'Test 2 - Acme maintained', 'ServiceNow method maintained by the Acme organization',
     NULL, 'Tutorial 2', NULL, 4, 8, 'ACTIVE', '1.0', 3, 3, NOW(), NOW(), '2024.2', 'u5'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111',
     'Test 3 - u3 maintained', 'CSV export method maintained by the individual contributor u3',
     'Deletes are not propagated; rows must be removed by hand.', 'Tutorial 3', NULL, 6, 10, 'ACTIVE', '1.0', 4, 4, NOW(), NOW(), '3.2', 'u5'),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', '11111111-1111-1111-1111-111111111111',
     'Test 4 - Community maintained', 'LDAP method open to every contributor',
     NULL, 'Tutorial 4', NULL, 7, NULL, 'ACTIVE', '2.0', 5, 1, NOW(), NOW(), '2025.2', 'u5');

INSERT INTO integration_method_connector (integ_method_id, integ_method_revision,
    connector_id, connector_minversion, connector_maxversion)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 1, '3.8', NULL),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '1.0', 2, '1.5.0', NULL),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', '1.0', 3, '2.9', NULL),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', '2.0', 1, '3.8', NULL);

-- Types: 1 Native/Proprietary API, 2 Standardized API, 3 Intermediary directory service,
-- 4 Direct repository access, 5 File-based integration, ...
INSERT INTO int_method_int_method_type (integration_method_id, integration_method_revision, integration_method_type_id) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 2),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 3),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '1.0', 1),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', '1.0', 5),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', '2.0', 3);

-- ============================================================
-- CAPABILITIES
-- Methods may only offer capabilities with offered_for_method = true:
-- SEARCH(9) READ(10) CREATE(11) UPDATE(12) DELETE(15) LIVE_SYNC(16) PASSWORD(19) ACTIVATION(20) ASSOCIATIONS(21)
-- ============================================================

INSERT INTO integration_method_capability (id, integ_method_id, integ_method_revision, object_class) OVERRIDING SYSTEM VALUE VALUES
    (1, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 'Account'),
    (2, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 'Group'),
    (3, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '1.0', 'Account'),
    (4, 'cccccccc-cccc-cccc-cccc-cccccccccccc', '1.0', 'Account'),
    (5, 'dddddddd-dddd-dddd-dddd-dddddddddddd', '2.0', 'Account'),
    (6, 'dddddddd-dddd-dddd-dddd-dddddddddddd', '2.0', 'Group');

INSERT INTO integration_method_capability_item (integration_method_capability_id, capability_id, state) VALUES
    (1,9,'YES'),(1,10,'YES'),(1,11,'YES'),(1,12,'YES'),(1,15,'YES'),(1,19,'YES'),(1,20,'YES'),
    (2,9,'YES'),(2,10,'YES'),(2,11,'YES'),(2,12,'YES'),(2,15,'YES'),(2,21,'YES'),
    (3,10,'YES'),(3,11,'YES'),(3,12,'YES'),(3,16,'YES'),
    (4,9,'YES'),(4,10,'YES'),(4,11,'YES'),
    (5,9,'YES'),(5,10,'YES'),(5,11,'YES'),(5,12,'YES'),(5,15,'YES'),
    (6,10,'YES'),(6,21,'YES');

-- Every object carries every offered capability: the rest is NO, except on groups 3 and 6,
-- which stay UNKNOWN so the seed shows all three states.
INSERT INTO integration_method_capability_item (integration_method_capability_id, capability_id, state)
SELECT imc.id, c.id, (CASE WHEN imc.id IN (3, 6) THEN 'UNKNOWN' ELSE 'NO' END)::CapabilityState
  FROM integration_method_capability imc
 CROSS JOIN capability c
 WHERE c.offered_for_method
   AND NOT EXISTS (SELECT 1 FROM integration_method_capability_item i
                    WHERE i.integration_method_capability_id = imc.id AND i.capability_id = c.id);

-- Connector versions may carry any capability, including the connector-only ones.
INSERT INTO conn_version_capability (id, conn_version_id, conn_version_revision, object_class) OVERRIDING SYSTEM VALUE VALUES
    (1, 1, '1.0', 'Account'),
    (2, 1, '1.0', 'Group'),
    (3, 1, '1.0', 'Global'),
    (4, 3, '1.0', 'Account');

INSERT INTO conn_version_capability_item (conn_version_capability_id, capability_id) VALUES
    (1,9),(1,10),(1,11),(1,12),(1,15),(1,19),(1,20),
    (2,9),(2,10),(2,11),(2,12),(2,15),(2,21),
    (3,1),(3,2),(3,4),(3,5),(3,6),(3,17),
    (4,9),(4,10),(4,11),(4,12),(4,15);

-- ============================================================
-- REQUEST for SAP HR (REQUESTED lifecycle)
-- ============================================================

INSERT INTO request (id, application_id, requester, mail, collab, base_url, system_version) OVERRIDING SYSTEM VALUE VALUES
    (1, '22222222-2222-2222-2222-222222222222', 'jane', 'jane@example.com', true, 'https://sap-hr.example.com', '2024');

INSERT INTO object_class_capabilities (request_id, object_name, capabilities) VALUES
    (1, 'Account', ARRAY['CREATE','READ','UPDATE','DELETE','SEARCH']::"CapabilityType"[]),
    (1, 'Group',   ARRAY['READ','SEARCH']::"CapabilityType"[]);

INSERT INTO vote (request_id, voter) VALUES
    (1, 'u1'),
    (1, 'u2');

-- ============================================================
-- SAMPLE DOWNLOADS
-- ============================================================

INSERT INTO download (connector_bundle_version_id, connector_bundle_version_revision, ip_address, user_agent, downloaded_at) VALUES
    (1, '1.0', '192.168.1.100', 'Chrome,Desktop',  NOW() - INTERVAL '3 days'),
    (1, '1.0', '10.0.0.5',      'Firefox,Desktop', NOW() - INTERVAL '2 days'),
    (1, '1.0', '172.16.0.10',   'Chrome,Mobile',   NOW() - INTERVAL '1 day'),
    (2, '1.0', '192.168.1.101', 'Chrome,Desktop',  NOW() - INTERVAL '5 days');

-- ============================================================
-- Re-sync every identity sequence past the explicit ids above
-- ============================================================

DO $$
DECLARE
    col record;
BEGIN
    FOR col IN
        SELECT table_name, column_name FROM information_schema.columns
         WHERE table_schema = 'public' AND is_identity = 'YES'
    LOOP
        EXECUTE format(
            'SELECT setval(pg_get_serial_sequence(%L, %L), COALESCE(MAX(%I), 1), MAX(%I) IS NOT NULL) FROM %I',
            col.table_name, col.column_name, col.column_name, col.column_name, col.table_name);
    END LOOP;
END $$;
