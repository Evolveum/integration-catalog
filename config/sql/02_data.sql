--
-- Copyright (c) 2010-2025 Evolveum and contributors
--
-- Licensed under the EUPL-1.2 or later.
--
-- 

-- ============================================================
-- ORGANIZATIONS
-- ============================================================
INSERT INTO organizations (id, name, description) VALUES
	(1, 'Acme co.', 'Test organization for OrganizationContributor and IndividualContributor users'),
	(2, 'Evolveum', 'Evolveum — application administrators');

SELECT setval('organizations_id_seq', 2);

-- ============================================================
-- CATALOG USERS
-- ============================================================
INSERT INTO catalog_users (username, password, role, organization_id) VALUES
	('u1', crypt('u1', gen_salt('bf', 10)), 'OrganizationContributor', 1),
	('u2', crypt('u2', gen_salt('bf', 10)), 'ReadOnly',                NULL),
	('u3', crypt('u3', gen_salt('bf', 10)), 'IndividualContributor',   NULL),
	('u4', crypt('u4', gen_salt('bf', 10)), 'IndividualContributor',   1),
	('u5', crypt('u5', gen_salt('bf', 10)), 'Superuser',               2);

-- ============================================================
-- LOOKUP TABLES
-- ============================================================

INSERT INTO application_tag (id, name, display_name, tag_type) OVERRIDING SYSTEM VALUE VALUES
    (1, 'directory_systems',                	'Directory Systems',                	'CATEGORY'),
	(2, 'hr_systems',                       	'HR Systems',                       	'CATEGORY'),
	(3, 'office_and_email_systems',         	'Office and Email Systems',         	'CATEGORY'),
	(4, 'security_and_access_control_systems',	'Security and Access Control Systems',	'CATEGORY'),
    (5, 'on-premise',   						'On Premise',   						'DEPLOYMENT'),
    (6, 'cloud-based', 							'Cloud based', 							'DEPLOYMENT'),
    (7, 'popular', 								'Popular', 								'COMMON'),
    (8, 'us', 									'USA based', 							'LOCALITY');

SELECT setval('application_tag_id_seq', 8);

INSERT INTO country_of_origin (id, name, display_name) OVERRIDING SYSTEM VALUE VALUES
    (1, 'czech_republic', 'Czech Republic'),
    (2, 'united_states_of_america', 'United States of America'),
    (3, 'germany', 'Germany');

SELECT setval('country_of_origin_id_seq', 3);

INSERT INTO integration_method_type (id, display_name, description) OVERRIDING SYSTEM VALUE VALUES
    (1, 'SCIM',        'SCIM integration method type'),
    (2, 'REST API',    'REST Api integration method type'),
    (3, 'OPEN_LDAP',   'Open LDAP integration method type'),
    (4, 'MANUAL_ITSM', 'Manual ITSM integration method type'),
    (5, 'DATABASE',     'Database integration method type'),
    (6, 'CSV',         'CSV integration method type');

SELECT setval('integration_method_type_id_seq', 6);

INSERT INTO connector_tag (id, name, display_name) OVERRIDING SYSTEM VALUE VALUES
    (1,'ai_generated','AI Generated');

SELECT setval('connector_tag_id_seq', 1);

INSERT INTO capability (id, name, description, display_order, globality) VALUES
    (1,  'TEST',                    'Test connection to resource',        1, 'GLOBAL'),
    (2,  'SCHEMA',                  'Retrieve resource schema',           2, 'GLOBAL'),
    (3,  'PARTIAL_SCHEMA',          'Retrieve partial resource schema',   3, 'GLOBAL'),
    (4,  'DISCOVER_CONFIGURATION',  'Discover Configuration',             4, 'GLOBAL'),
    (5,  'AUTHENTICATION',          'Authenticate users',                 5, 'GLOBAL'),
    (6,  'SCRIPT_ON_CONNECTOR',     'Script on Connector',                6, 'GLOBAL'),
    (7,  'SCRIPT_ON_RESOURCE',      'Script on resource',                 7, 'GLOBAL'),
    (8,  'RESOLVE_USERNAME',        'Resolve Username',                   8, 'GLOBAL'),
    (9,  'SEARCH',                  'Search and list objects',            1, 'SPECIFIC'),
    (10, 'GET',                     'Read individual objects',            2, 'SPECIFIC'),
    (11, 'CREATE',                  'Create new objects',                 3, 'SPECIFIC'),
    (12, 'UPDATE',                  'Modify existing objects',            4, 'SPECIFIC'),
    (13, 'UPDATE_DELTA',            'Update the delta',                   5, 'SPECIFIC'),
    (14, 'COMPLEX_UPDATE_DELTA',    'Update the complex delta',           6, 'SPECIFIC'),
    (15, 'DELETE',                  'Remove objects',                     7, 'SPECIFIC'),
    (16, 'LIVE_SYNC',               'Receive live change notifications',  8, 'SPECIFIC'),
    (17, 'SYNC',                    'Synchronize objects periodically',   9, 'SPECIFIC'),
    (18, 'VALIDATE',                'Validate',                          10, 'SPECIFIC');
	
SELECT setval('capability_id_seq', 18);


INSERT INTO midpoint_version (id, version, version_name, is_current) values
	(1, '4.2', 'Version 4.2', false),
	(2, '4.3', 'Version 4.3', false),
	(3, '4.4', 'Version 4.4', false),
	(4, '4.5', 'Version 4.5', false),
	(5, '4.6', 'Version 4.6', false),
	(6, '4.7', 'Version 4.7', false),
	(7, '4.8', 'Version 4.8', false),
	(8, '4.9', 'Version 4.9', true),
    (9, '4.10', 'Version 4.10', false),
    (10, '4.11', 'Version 4.11', false);
	
SELECT setval('midpoint_version_id_seq', 8);

-- ============================================================
-- APPLICATIONS
-- ============================================================

INSERT INTO application (id, name, display_name, description, lifecycle_state, created_at, updated, logo_path) VALUES
    ('11111111-1111-1111-1111-111111111111', 'my_test_app', 'My Test App',
     'My Test App - Microsoft Active Directory LDAP connector for identity management', 'ACTIVE', NOW(), NOW(), null),
    ('22222222-2222-2222-2222-222222222222', 'sap_hr', 'SAP HR',
     'SAP Human Resources system integration requested by the community', 'REQUESTED', NOW(), NOW(), null);

INSERT INTO application_application_tag (application_id, tag_id) VALUES
    ('11111111-1111-1111-1111-111111111111', 1),
    ('11111111-1111-1111-1111-111111111111', 6);

INSERT INTO application_origin (application_id, country_id) VALUES
    ('11111111-1111-1111-1111-111111111111', 1);

-- ============================================================
-- CONNECTOR BUNDLES  (id 1..4)
-- ============================================================

INSERT INTO connector_bundle (id, revision, author, maintainer, created_at, updated, lifecycle_state,
    bundle_name, display_name, description, framework, license, ticketing_link, build_framework)
OVERRIDING SYSTEM VALUE VALUES
    (1, '1.0', 'u5', 'Conn bun maintainer 1', NOW(), NOW(), 'ACTIVE', 'connector-ldap', 'LDAP Connector Bundle',
     'ConnId LDAP connector for Java-based', 'JAVA_BASED', 'APACHE_2', 'https://github.com/Evolveum/connector-ldap/issues', 'MAVEN'),
    (2, '1.0', 'u1', 'Conn bun maintainer 2', NOW(), NOW(), 'ACTIVE', 'connector-servicenow', 'ServiceNow Connector Bundle',
     'ConnId ServiceNow  with LOW CODE', 'LOW_CODE', 'MIT', 'https://github.com/ExampleOrg/connector-servicenow/issues', 'GRADLE');

SELECT setval('connector_bundle_id_seq', 3);

-- ============================================================
-- CONNECTOR BUNDLE VERSIONS  (id 1..4, composite PK id+revision)
-- ============================================================

INSERT INTO connector_bundle_version (id, revision, author, maintainer, created_at, updated,
    lifecycle_state, connector_bundle_id, bundle_version, browse_link, git_clone_ULR,
    path_to_project, build_framework, error_message)
OVERRIDING SYSTEM VALUE VALUES
    (1, '1.0', 'u5','Conn bun ver author 1', NOW(), NOW(), 'ACTIVE', 1, '3.8', 'https://github.com/Evolveum/connector-ldap/tree/v3.8',
     'https://github.com/Evolveum/connector-ldap.git', '/path_to_project', 'MAVEN', NULL),
    (2, '1.0', 'u1', 'Conn bun ver author 2', NOW(), NOW(), 'ACTIVE', 2, '1.5.0', 'https://github.com/ExampleOrg/connector-salesforce/tree/1.0.5',
     'https://github.com/ExampleOrg/connector-salesforce.git', '/path_to_project', NULL, NULL);

SELECT setval('connector_bundle_version_id_seq', 4);

-- ============================================================
-- CONNECTORS  (id 1..4)
-- ============================================================

INSERT INTO connector (id, revision, author, maintainer, created_at, updated, display_name,
    fully_qualified_class_name, connector_bundle_id, description)
OVERRIDING SYSTEM VALUE VALUES
    (1, '1.0', 'Conn author 1', 'Conn maintainer 1', NOW(), NOW(), 'Display name Connector Java-based',
	'Fully qualified conn class name 1', 1, 'Description Connector 1'),
    (2, '1.0', 'Conn author 2', 'Conn maintainer 2', NOW(), NOW(), 'Display name Connector LOW CODE',
	'Fully qualified conn class name 2', 2, 'Description connector 2');

SELECT setval('connector_id_seq', 2);

INSERT INTO connector_connector_tag (connector_id, tag_id) VALUES
    (1, 1);

SELECT setval('connector_connector_tag_id_seq', 1);

-- ============================================================
-- CONNECTOR VERSIONS  (id 1..4, composite PK id+revision)
-- FK references connector_bundle_version(id, revision)
-- ============================================================

INSERT INTO connector_version (id, revision, author, maintainer, created_at, updated,
    lifecycle_state, connector_bundle_version_id, connector_bundle_version_revision,
    connector_id, fully_qualified_class_name, error_message)
OVERRIDING SYSTEM VALUE VALUES
    (1, '1.0', 'u5', 'Conn ver author 1', NOW(), NOW(), 'ACTIVE', 1, '1.0', 1, 'Fully qualified conn ver class name 1', NULL),
    (2, '1.0', 'u1', 'Conn ver author 2', NOW(), NOW(), 'ACTIVE', 2, '1.0', 2, 'Fully qualified conn ver class name 2', NULL);

SELECT setval('connector_version_id_seq', 2);

-- ============================================================
-- INTEGRATION METHODS  (composite PK uuid + revision)
-- ============================================================

INSERT INTO integration_method (id, application_id, display_name, description,
     tutorial, file_path, midpoint_minVersion, midpoint_maxVersion, lifecycle_state, revision, author, maintainer, created_at, updated, app_version)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','11111111-1111-1111-1111-111111111111','Test 1 - Integration method','Test 1 - Integration method description',
	 'Tutorial 1','/file_path',5,6,'ACTIVE','1.0','IM author 1','IM maintainer 1',NOW(),NOW(),'2025.1'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','11111111-1111-1111-1111-111111111111', 'Test 2 - Integration method','Test 2 - Integration method description',
	 'Tutorial 2','/file_path',4,8,'ACTIVE','1.0','IM author 2','IM maintainer 2',NOW(),NOW(),'2024.2');

-- ============================================================
-- INTEGRATION METHOD → CONNECTOR links
-- unique constraint: one connector link per (integ_method_id, integ_method_revision)
-- ============================================================

INSERT INTO integration_method_connector (id, integ_method_id, integ_method_revision,
    connector_id, connector_minVersion, connector_maxVersion)
VALUES
    (1, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 1, '4.7', '4.9'),
    (2, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '1.0', 2, '4.3', '4.5');

SELECT setval('integration_method_connector_id_seq', 2);

-- ============================================================
-- INTEGRATION METHOD → INTEGRATION METHOD TYPE links
-- unique constraint: one int method type link per (integ_method_id, integ_method_revision)
-- ============================================================

INSERT INTO int_method_int_method_type (id, integration_method_id, integration_method_revision, integration_method_type_id) OVERRIDING SYSTEM VALUE
VALUES
    (1, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 1),
	(2, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 2),
	(3, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '1.0', 3);

SELECT setval('int_method_int_method_type_id_seq', 3);
	
-- ============================================================
-- INTEGRATION METHOD CAPABILITIES
-- (no IDENTITY on id — explicit values required)
-- ============================================================

INSERT INTO integration_method_capability (id, integ_method_id, integ_method_revision, object_class) OVERRIDING SYSTEM VALUE VALUES
    (1, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 'Account'),
    (2, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 'Group'),
    (3, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1.0', 'Global');

SELECT setval('integration_method_capability_id_seq', 3);

INSERT INTO integration_method_capability_item (integration_method_capability_id, capability_id) VALUES
    -- Account: CREATE(11) GET(10) UPDATE(12) DELETE(15) TEST(1) SCRIPT_ON_CONNECTOR(6) SCRIPT_ON_RESOURCE(7) AUTHENTICATION(5)
    (1,11),(1,10),(1,12),(1,15),(1,17),(1,18),
    -- Group: CREATE(11) GET(10) UPDATE(12) DELETE(15) TEST(1)
    (2,11),(2,10),(2,12),(2,15),
    -- Global: CREATE(11) GET(10) UPDATE(12) DELETE(15) TEST(1) SCRIPT_ON_CONNECTOR(6) SCRIPT_ON_RESOURCE(7) AUTHENTICATION(5)
    (3,1),(3,2),(3,3),(3,4),(3,5),(3,6);

-- ============================================================
-- CONNECTOR VERSION CAPABILITIES
-- (no IDENTITY on id — explicit values required)
-- ============================================================

INSERT INTO conn_version_capability (id, conn_version_id, conn_version_revision, object_class) OVERRIDING SYSTEM VALUE VALUES
    (1, 1, '1.0', 'Account'),
    (2, 1, '1.0', 'Group'),
    (3, 1, '1.0', 'Global');

SELECT setval('conn_version_capability_id_seq', 3);

INSERT INTO conn_version_capability_item (conn_version_capability_id, capability_id) VALUES
    -- Account: CREATE(11) GET(10) UPDATE(12) DELETE(15) TEST(1) SCRIPT_ON_CONNECTOR(6) SCRIPT_ON_RESOURCE(7) AUTHENTICATION(5)
    (1,11),(1,10),(1,12),(1,15),(1,17),(1,18),
    -- Group: CREATE(11) GET(10) UPDATE(12) DELETE(15) TEST(1)
    (2,11),(2,10),(2,12),(2,15),
    -- Global: CREATE(11) GET(10) UPDATE(12) DELETE(15) TEST(1) SCRIPT_ON_CONNECTOR(6) SCRIPT_ON_RESOURCE(7) AUTHENTICATION(5)
    (3,1),(3,2),(3,3),(3,4),(3,5),(3,6);

-- ============================================================
-- REQUEST for SAP HR (REQUESTED lifecycle)
-- ============================================================

INSERT INTO request (application_id, requester, mail, collab, base_url, system_version) VALUES
    ('22222222-2222-2222-2222-222222222222', 'jane', 'jane@example.com', true, 'https://sap-hr.example.com', '2024');

INSERT INTO object_class_capabilities (request_id, object_name, capabilities) VALUES
    (1, 'Account', ARRAY['CREATE','GET','UPDATE','DELETE','SEARCH']::"CapabilityType"[]),
    (1, 'Group',   ARRAY['GET','SEARCH']::"CapabilityType"[]);

INSERT INTO vote (request_id, voter) VALUES
    (1, 'u1'),
    (1, 'u2');

-- ============================================================
-- SAMPLE DOWNLOADS (AD connector — connector_bundle_version id=1)
-- ============================================================

INSERT INTO download (connector_bundle_version_id, connector_bundle_version_revision, ip_address, user_agent, downloaded_at) VALUES
    (1, '1.0', '192.168.1.100', 'Chrome,Desktop',  NOW() - INTERVAL '3 days'),
    (1, '1.0', '10.0.0.5',      'Firefox,Desktop', NOW() - INTERVAL '2 days'),
    (1, '1.0', '172.16.0.10',   'Chrome,Mobile',   NOW() - INTERVAL '1 day'),
    (2, '1.0', '192.168.1.101', 'Chrome,Desktop',  NOW() - INTERVAL '5 days');
	
