BEGIN;

SET search_path = public;

INSERT INTO public.application_tag (id, name, display_name, tag_type) VALUES
  (1,  'directory_systems',                'Directory Systems',                'CATEGORY'),
  (2,  'hr_systems',                       'HR Systems',                       'CATEGORY'),
  (3,  'office_and_email_systems',         'Office and Email Systems',         'CATEGORY'),
  (4,  'security_and_access_control_systems','Security and Access Control Systems','CATEGORY'),
  (5,  'cloud-based',                      'Cloud based',                      'DEPLOYMENT'),
  (6,  'on-premise',                       'On premise',                       'DEPLOYMENT'),
  (7,  'eu-based',                         'EU based',                         'LOCALITY'),
  (8,  'popular',                          'Popular',                          'COMMON'),
  (9,  'us',                               'USA based',                        'LOCALITY'),
  (10, 'uk',                               'UK',                               'LOCALITY'),
  (11, 'internal_applications',            'Internal Applications',            'CATEGORY');

INSERT INTO public.country_of_origin (id, name, display_name) VALUES
  (1, 'austria',  'Austria, Europe'),
  (2, 'france',   'France, EU'),
  (3, 'cambodia', 'Cambodia, Asia'),
  (4, 'croatia',  'Croatia, EU'),
  (5, 'illinois', 'Illinoins, USA');

INSERT INTO public.connid_version (version, midpoint_version) VALUES
  ('1.1.0.0', ARRAY['4.1.0','4.1.1']),
  ('1.2.0.0', ARRAY['4.2.0','4.2.1']),
  ('1.3.0.0', ARRAY['4.3.0','4.3.1']),
  ('1.4.0.0', ARRAY['4.4.0','4.4.1']),
  ('1.5.0.0', ARRAY['4.5.0','4.5.1']);

INSERT INTO public.application
(id, name, display_name, description, logo, lifecycle_state, created_at, last_modified) VALUES
('54dfdf0e-4528-4b03-967d-4af33e49a0ab','csv-connector','CSV Connector',
 'Near day goal question effect third many. Attack direction future show he lose morning hit.
Crime single heavy surface shake admit matter. Expert look instead.',
 '\xa961c4a91369fcde892dad2cbfaac913a9d3475499526077963b8a5bfd155f944aecc98becdf1f239b2a17cd09ef83e8635fa28227dc3a37666fe26990255bf2a989bc1f40354a4914bca0370e92444ba113745c95a40c19e388f965c27e6ca80c80767c',
 'ACTIVE','2025-06-29 05:51:48','2025-06-29 05:51:48'),
('7774eb84-2b8e-41ba-9f40-65cfbf39f927','pokus_1','Pokus 1','pOKUS',
 NULL,'REQUESTED','2025-10-24 08:49:41','2025-10-24 08:49:41'),
('e5c2e9d6-4d28-4a08-8374-998c5373a35a','salesforce-connector','Salesforce Connector',
 'Former serve beautiful make international ever. Available brother writer example fact. Election character risk subject inside stay.',
 NULL,'ACTIVE','2025-05-12 14:52:23','2025-05-12 14:52:23'),
('e5c2e9d6-4d28-4a08-8374-998c5373a35b','salesforce-connector','Salesforce Connector',
 'Former serve beautiful make international ever. Available brother writer example fact. Election character risk subject inside stay.',
 NULL,'ACTIVE','2025-05-12 14:52:23','2025-05-12 14:52:23'),
('e5c2e9d6-4d28-4a08-8374-998c5373a35c','ldap-connector','LDAP Connector',
 'Former serve beautiful make international ever. Available brother writer example fact. Election character risk subject inside stay.',
 '\x70722ffd80a5819cbffd0b6eee772d15d4345c9ba3c786f94a733d6c1e23ca9f0ab3dc23375031e2a719c300d5b8d53b580e013e411672bdf25d4f2ea159e76a0450f731ac825a9ecd42cf125dd4ed7fca0cbe25cb4c03af8730381578847c79f1e671b0',
 'ACTIVE','2025-05-12 14:52:23','2025-05-12 14:52:23'),
('e5c2e9d6-4d28-4a08-8374-998c5373a35d','salesforce-connector','Salesforce Connector',
 'Former serve beautiful make international ever. Available brother writer example fact. Election character risk subject inside stay.',
 NULL,'ACTIVE','2025-05-12 14:52:23','2025-05-12 14:52:23'),
('e5c2e9d6-4d28-4a08-8374-998c5373a35e','salesforce-connector','Salesforce Connector',
 'Former serve beautiful make international ever. Available brother writer example fact. Election character risk subject inside stay.',
 NULL,'ACTIVE','2025-05-12 14:52:23','2025-05-12 14:52:23'),
('e5c2e9d6-4d28-4a08-8374-998c5373a35f','salesforce-connector','Salesforce Connector',
 'Former serve beautiful make international ever. Available brother writer example fact. Election character risk subject inside stay.',
 NULL,'ACTIVE','2025-05-12 14:52:23','2025-05-12 14:52:23'),
('2e216899-cc0b-4a56-8788-c8892fa8f0a2','pokus_1.2','pokus 1.2','aj tu je text',
 NULL,'REQUESTED','2025-10-24 11:24:33','2025-10-24 11:24:33'),
('9ed6e4fb-5f06-4081-845c-df023274e4db','databasetable-connector','DatabaseTable Connector',
 'Career name these thank word explain. Past care over whom. Another moment sometimes camera every. While fly game wind data debate myself.',
 '\x34d87c4cb5324cb11182d876130e944cc385e0debfd7ea28165ec5a65b932bcf7bd35c1101614a6e204090baf734d551f560e1c02bbab81ff4d9470c178bb7a682f12eb694505a15ef2c38cd825aebf14d04c869de66ebb7a2692646b36ea37b09311504',
 'WITH_ERROR','2025-01-20 08:29:41','2025-01-20 08:29:41'),
('4a3c7f04-4106-4934-add1-f329f6333ad0','sap-integration','SAP Integration',
 'Success green news can hot. Assume lay system tend message recognize however.
Customer sign sing research. Against ready right high. Rise style rock tough.',
 '\x33353af1f318a9c95c213338529ccadb0d0d9708a725a8574ee71f21bd29841000ec416ad2287128248df40539fdbe01dd782cb8077b5e27eb03af688666d8c595c82b55ff646af97ecbede8646138e846fd111804c11b32b11cdbe0d35294cc5c70dfcf',
 'IN_PUBLISH_PROCESS','2025-03-12 18:54:19','2025-03-12 18:54:19'),
('4a3c7f04-4106-4934-add1-f329f6333ad1','sap-integration','SAP Integration',
 'Success green news can hot. Assume lay system tend message recognize however.
Customer sign sing research. Against ready right high. Rise style rock tough.',
 NULL,'IN_PUBLISH_PROCESS','2025-03-12 18:54:19','2025-03-12 18:54:19'),
('5f0ba262-a08b-44fc-b830-f4942e3efeb1','workday-adapter','Workday Adapter',
 'Before according scene include arrive measure themselves. Long during media last son record history. Early on more.',
 NULL,'IN_PUBLISH_PROCESS','2025-09-27 04:02:54','2025-09-27 04:02:54'),
('5f0ba262-a08b-44fc-b830-f4942e3efeb2','workday-adapter','Workday Adapter',
 'Before according scene include arrive measure themselves. Long during media last son record history. Early on more.',
 NULL,'WITH_ERROR','2025-09-27 04:02:54','2025-09-27 04:02:54'),
('5f0ba262-a08b-44fc-b830-f4942e3efeb3','workday-adapter','Workday Adapter',
 'Before according scene include arrive measure themselves. Long during media last son record history. Early on more.',
 NULL,'WITH_ERROR','2025-09-27 04:02:54','2025-09-27 04:02:54'),
('5f0ba262-a08b-44fc-b830-f4942e3efeb4','workday-adapter','Workday Adapter',
 'Before according scene include arrive measure themselves. Long during media last son record history. Early on more.',
 '\x7db0fb87eece91b8232cb25a73972596b50cceaf2ef34689e67d08898c6dfdf60f44181b867d87e8a4ada0cc50ce412dd955f7003142501a50e89c5472ec22b4e4e6d63fc9ce6f31dfa887aa5f815b0ddaefad0ca8a041afe7b276fa00b7cc7523229699',
 'WITH_ERROR','2025-09-27 04:02:54','2025-09-27 04:02:54');

INSERT INTO public.implementation_tag (id, name, display_name) VALUES
  (1,  'ai_generated',      'AI Generated'),
  (5,  'custom',            'CUSTOM'),
  (6,  'paged_search',      'Paged search'),
  (7,  'live_sync',         'Live sync'),
  (8,  'script',            'Script'),
  (9,  'password',          'Password'),
  (10, 'credentials',       'Credentials'),
  (11, 'test_connection',   'Test connection'),
  (12, 'lot_of_text',       'Lot of text'),
  (13, 'small_filler',      'Small filler'),
  (14, 'big_filler',        'BIG FILLER');

INSERT INTO public.implementation
(id, display_name, connector_bundle, maintainer, framework, ticketing_system_link, license, application_id) VALUES
('b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e','SAP Integration Implementation','com.evolveum.polygon.connector.sap.integration','Scott, Baker and Howard','SCIM_REST','http://clark.com/','MIT','4a3c7f04-4106-4934-add1-f329f6333ad0'),
('c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f','Workday Adapter Implementation','com.evolveum.polygon.connector.workday.adapter','Smith-Olson','SCIM_REST','https://www.phillips.org/','APACHE_2','5f0ba262-a08b-44fc-b830-f4942e3efeb4'),
('a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d','LDAP Connector Implementation','com.evolveum.polygon.connector.salesforce.connector','Wilson-Robinson','CONNID','http://www.may.info/','MIT','e5c2e9d6-4d28-4a08-8374-998c5373a35c'),
('d4e5f6a7-b8c9-4d0e-1f2a-3b4c5d6e7f8a','DatabaseTable Connector Implementation','com.evolveum.polygon.connector.azure.ad.sync','Crawford and Sons','CONNID','https://www.weber.com/','MIT','9ed6e4fb-5f06-4081-845c-df023274e4db'),
('e5f6a7b8-c9d0-4e1f-2a3b-4c5d6e7f8a9b','CSV Connector Implementation','com.evolveum.polygon.connector.oracle.connector','Stevens and Sons','CONNID','http://levy-herrera.net/','APACHE_2','54dfdf0e-4528-4b03-967d-4af33e49a0ab'),
('f6a7b8c9-d0e1-4f2a-3b4c-5d6e7f8a9b0c','CSV Connector Implementation','com.evolveum.polygon.connector.oracle.connector','Stevens and Sons','CONNID','http://levy-herrera.net/','APACHE_2','54dfdf0e-4528-4b03-967d-4af33e49a0ab');

INSERT INTO public.implementation_version
(id, description, connector_version, capabilities, browse_link, checkout_link, download_link, system_version, author, released_date, publish_date, lifecycle_state, build_framework, connid_version, implementation_id, error_message) VALUES
('e87a6b1b-38c5-4834-96de-2418dbda9f1b',
 'Hit something action building majority body. Newspaper sell business write political tough not.
Shake sing part picture person.',
 '2.0.8',
 ARRAY['CREATE','GET','DELETE','SCRIPT_ON_CONNECTOR','SCRIPT_ON_RESOURCE','AUTHENTICATION','SEARCH']::"CapabilityType"[],
 'https://khan.com/','https://khan.com//tree/v2.0.8','http://chung-weaver.biz//downloads/connector-2.0.8.jar',
 'System 1.5','tuckerjames@example.org','2025-05-02','2025-09-25 09:55:02','IN_PUBLISH_PROCESS','GRADLE','1.2.0.0','b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',NULL),
('5616cdc5-2b90-42ac-b347-78928126de08',
 'CSV connector','5.2',
 ARRAY['CREATE','GET','DELETE']::"CapabilityType"[],
 'http://www.ramirez-flores.biz/','https://github.com/Evolveum/connector-csv/tree/v2.8','https://nexus.evolveum.com/nexus/repository/public/com/evolveum/polygon/connector-csv/2.8/connector-csv-2.8.jar',
 'System 1.5','isabelthomas@evolveum.com','2025-04-14','2025-07-27 13:44:05','ACTIVE','MAVEN','1.5.0.0','e5f6a7b8-c9d0-4e1f-2a3b-4c5d6e7f8a9b',NULL),
('5616cdc5-2b90-42ac-b347-78928126de09',
 'LDAP connector','5.0.0',
 ARRAY['CREATE','GET','UPDATE']::"CapabilityType"[],
 'http://www.ramirez-flores.biz/','https://github.com/Evolveum/connector-ldap/tree/v3.9','https://nexus.evolveum.com/nexus/repository/releases/com/evolveum/polygon/connector-ldap/3.9.1/connector-ldap-3.9.1.jar',
 'System 1.5','isabelthomas@example.com','2025-04-14','2025-07-27 13:44:05','ACTIVE','MAVEN','1.5.0.0','f6a7b8c9-d0e1-4f2a-3b4c-5d6e7f8a9b0c',NULL),
('5616cdc5-2b90-42ac-b347-78928126de10',
 'Databasetable connector','5.1',
 ARRAY['CREATE','GET','UPDATE','DELETE']::"CapabilityType"[],
 'http://www.ramirez-flores.biz/','https://github.com/Evolveum/openicf/releases/tag/connector-databasetable-v1.5.1.0','https://nexus.evolveum.com/nexus/repository/releases/com/evolveum/polygon/connector-databasetable/1.5.2.0/connector-databasetable-1.5.2.0.jar',
 'System 1.5','isabelthomas@evolveum.com','2025-04-14','2025-07-27 13:44:05','DEPRECATED','MAVEN','1.5.0.0','e5f6a7b8-c9d0-4e1f-2a3b-4c5d6e7f8a9b',NULL),
('eb460ab7-5797-42b0-ad70-eccdc113cf0c',
 'Level painting his lot sound. Involve note big everyone.
Reflect claim throw huge. Head design amount pressure goal.',
 '3.0.1',
 ARRAY['CREATE','GET','UPDATE','DELETE']::"CapabilityType"[],
 'https://www.serrano.com/','https://www.serrano.com//tree/v3.0.1','http://miles.org//downloads/connector-3.0.1.jar',
 'API v59.0','iwarner@example.net','2025-06-30','2025-07-15 02:16:54','WITH_ERROR','GRADLE','1.3.0.0','c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f',NULL),
('eb19c64d-2cc9-4828-b38f-e4298be9b208',
 'Degree every others capital. Perform important animal fish find power thing.',
 '1.0.4',
 ARRAY['CREATE','GET','UPDATE','DELETE']::"CapabilityType"[],
 'https://www.blake.org/','https://github.com/Evolveum/connector-ldap/tree/v3.9','https://nexus.evolveum.com/nexus/repository/releases/com/evolveum/polygon/connector-ldap/3.9.1/connector-ldap-3.9.1.jar',
 'System 0.3','shelly74@example.com','2025-09-25','2025-09-22 22:19:49','ARCHIVED','MAVEN','1.1.0.0','a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',NULL),
('6d3544ab-e5ed-457f-b356-56e9fd419cbc',
 'Music rise area player. Focus wife particular class. Read protect add spend who cover also.',
 '4.0.2',
 ARRAY['CREATE','GET','UPDATE','DELETE']::"CapabilityType"[],
 'https://collins.com/','https://github.com/Evolveum/openicf/releases/tag/connector-databasetable-v1.5.1.0','https://nexus.evolveum.com/nexus/repository/releases/com/evolveum/polygon/connector-databasetable/1.5.2.0/connector-databasetable-1.5.2.0.jar',
 'System 1.51','zacharywilcox@example.net','2025-04-06','2025-02-09 14:52:59','ACTIVE','MAVEN','1.4.0.0','d4e5f6a7-b8c9-4d0e-1f2a-3b4c5d6e7f8a',NULL);

INSERT INTO public.downloads (implementation_version_id, ip_address, user_agent, downloaded_at, id) VALUES
('e87a6b1b-38c5-4834-96de-2418dbda9f1b','122.154.103.227','Mozilla/5.0 (Linux; Android 4.2.2) AppleWebKit/534.2 (KHTML, like Gecko) Chrome/56.0.868.0 Safari/534.2','2025-08-01 10:00:20','4fae33f4-7ab6-4ad8-8c5a-ec1f869d48b1'),
('6d3544ab-e5ed-457f-b356-56e9fd419cbc','219.90.145.191','Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.0; Trident/5.0)','2025-09-15 15:03:31','f8771572-28bb-47a4-afef-f21256d0ce9a'),
('5616cdc5-2b90-42ac-b347-78928126de09','83.160.162.104','Opera/8.86.(Windows NT 5.2; de-DE) Presto/2.9.162 Version/12.00','2025-03-13 20:52:30','6e7cbe6c-2fdc-4d60-ba87-443e58bc1de7'),
('eb19c64d-2cc9-4828-b38f-e4298be9b208','182.165.80.49','Mozilla/5.0 (iPad; CPU iPad OS 15_8_2 like Mac OS X) AppleWebKit/536.1 (KHTML, like Gecko) CriOS/39.0.895.0 Mobile/52C249 Safari/536.1','2025-03-31 17:46:53','81503522-8948-41bf-82ba-05ad7e299d79'),
('eb460ab7-5797-42b0-ad70-eccdc113cf0c','43.154.255.67','Opera/8.62.(X11; Linux x86_64; tg-TJ) Presto/2.9.177 Version/10.00','2025-06-01 16:36:32','8172d5e5-675a-45ab-8e55-690f5a4e88d0');

INSERT INTO public.application_application_tag (id, application_id, tag_id) VALUES
(1,'54dfdf0e-4528-4b03-967d-4af33e49a0ab',2),
(2,'54dfdf0e-4528-4b03-967d-4af33e49a0ab',5),
(3,'54dfdf0e-4528-4b03-967d-4af33e49a0ab',7),
(4,'54dfdf0e-4528-4b03-967d-4af33e49a0ab',8),
(26,'e5c2e9d6-4d28-4a08-8374-998c5373a35a',3),
(27,'e5c2e9d6-4d28-4a08-8374-998c5373a35b',4),
(5,'e5c2e9d6-4d28-4a08-8374-998c5373a35c',1),
(6,'e5c2e9d6-4d28-4a08-8374-998c5373a35c',6),
(7,'e5c2e9d6-4d28-4a08-8374-998c5373a35c',7),
(28,'e5c2e9d6-4d28-4a08-8374-998c5373a35d',4),
(29,'e5c2e9d6-4d28-4a08-8374-998c5373a35e',3),
(30,'e5c2e9d6-4d28-4a08-8374-998c5373a35f',4),
(8,'9ed6e4fb-5f06-4081-845c-df023274e4db',3),
(9,'9ed6e4fb-5f06-4081-845c-df023274e4db',5),
(11,'4a3c7f04-4106-4934-add1-f329f6333ad0',1),
(12,'4a3c7f04-4106-4934-add1-f329f6333ad0',5),
(13,'4a3c7f04-4106-4934-add1-f329f6333ad0',10),
(25,'4a3c7f04-4106-4934-add1-f329f6333ad1',2),
(19,'5f0ba262-a08b-44fc-b830-f4942e3efeb1',2),
(18,'5f0ba262-a08b-44fc-b830-f4942e3efeb2',11),
(17,'5f0ba262-a08b-44fc-b830-f4942e3efeb3',11),
(14,'5f0ba262-a08b-44fc-b830-f4942e3efeb4',1),
(15,'5f0ba262-a08b-44fc-b830-f4942e3efeb4',8);

INSERT INTO public.application_origin (id, application_id, country_id) VALUES
(5,'54dfdf0e-4528-4b03-967d-4af33e49a0ab',5),
(1,'e5c2e9d6-4d28-4a08-8374-998c5373a35c',1),
(8,'9ed6e4fb-5f06-4081-845c-df023274e4db',1),
(4,'9ed6e4fb-5f06-4081-845c-df023274e4db',4),
(2,'4a3c7f04-4106-4934-add1-f329f6333ad0',2),
(7,'4a3c7f04-4106-4934-add1-f329f6333ad0',3),
(3,'5f0ba262-a08b-44fc-b830-f4942e3efeb4',3);

INSERT INTO public.implementation_implementation_tag (id, implementation_id, tag_id) VALUES
(3,'e5f6a7b8-c9d0-4e1f-2a3b-4c5d6e7f8a9b',1);

INSERT INTO public.request (id, application_id, capabilities, requester) VALUES
(7,'7774eb84-2b8e-41ba-9f40-65cfbf39f927', ARRAY['CREATE']::"CapabilityType"[], NULL),
(8,'2e216899-cc0b-4a56-8788-c8892fa8f0a2', ARRAY['CREATE','GET']::"CapabilityType"[], 'tomo.simon@gmail.com');

INSERT INTO public.votes (request_id, voter) VALUES
(7,'u1'),
(7,'u2'),
(8,'u1'),
(7,'u3');

SELECT pg_catalog.setval('public.application_application_tag_id_seq', 30, true);
SELECT pg_catalog.setval('public.application_origin_id_seq', 8, true);
SELECT pg_catalog.setval('public.application_tag_id_seq', 20, true);
SELECT pg_catalog.setval('public.country_of_origin_id_seq', 5, true);
SELECT pg_catalog.setval('public.implementation_implementation_tag_id_seq', 21, true);
SELECT pg_catalog.setval('public.implementation_tag_id_seq', 14, true);
SELECT pg_catalog.setval('public.request_id_seq', 8, true);

COMMIT;
