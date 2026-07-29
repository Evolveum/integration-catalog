# Integration Catalog — OIDC Authentication and Authorization

The Integration Catalog authenticates users with **OpenID Connect**: the Spring Boot
backend is the OIDC client, **Keycloak** is the identity provider. There is no local
password login anymore — Keycloak owns credentials, roles, group membership and the
organization assignment.

## 1. Architecture

```
Browser (Angular SPA)
   │  1. GET /oauth2/authorization/keycloak   (login button → full-page redirect)
   ▼
Spring Boot backend (OIDC client, spring-boot-starter-oauth2-client)
   │  2. redirects to Keycloak authorization endpoint
   ▼
Keycloak  http://localhost:8081/realms/integration-catalog
   │  3. user logs in, redirects back with code
   ▼
Backend /login/oauth2/code/keycloak — exchanges code, validates ID token,
   maps claims to authorities, provisions the user, starts a session (cookie)
   │  4. redirect to /
   ▼
SPA — GET /api/auth/me returns the profile; all /api calls carry the session cookie
```

- **Session model:** classic servlet session cookie (`JSESSIONID`). The SPA never sees a
  token. CSRF is protected with the `XSRF-TOKEN` cookie / `X-XSRF-TOKEN` header pair
  (Angular's HttpClient handles this automatically for same-origin calls).
- **Unauthenticated `/api` calls** get a plain `401` (no redirect); the SPA decides when to
  start the login flow.
- **Logout:** `GET /logout` invalidates the app session, then performs RP-initiated logout
  at Keycloak (ends the SSO session) and returns to the app.
- **Dev setup:** `ng serve` proxies `/api`, `/oauth2`, `/login/oauth2` and `/logout` to the
  backend (`proxy.conf.json`), so cookies stay on the SPA origin (`localhost:4200`). In
  production the backend serves the SPA itself and everything is naturally same-origin.

## 2. What Keycloak provides

Client `integration-catalog` (confidential, standard flow) in realm `integration-catalog`
with protocol mappers that put the following into the ID token / userinfo:

| Claim | Source in Keycloak | Used for |
|---|---|---|
| `preferred_username` | username | principal name (`Authentication.getName()`), `catalog_users.username` |
| `name` / `given_name` / `family_name` | user profile | full name in `/api/auth/me` |
| `email` | user profile | email in `/api/auth/me` |
| `roles` | user attribute `role` | application role → `ROLE_*` authorities + `catalog_users.role` |
| `groups` | user attribute `group` | `Partner` / `Subscriber` → `GROUP_*` authorities, shown in `/api/auth/me` |
| `organization` | user attribute `organization` | linked/created `organizations` row → `catalog_users.organization_id` |

The catalog deliberately does **not** use Keycloak-native realm roles or group objects:
everything application-specific lives in plain **user attributes** (`role`, `group`,
`organization`) that `oidc-usermodel-attribute-mapper` mappers on the client copy into the
tokens/userinfo. Keycloak stays a vanilla identity provider; to onboard a user an admin just
fills in three attributes on the user's *Attributes* tab.

**Role** (attribute `role`, value must match the `catalog_users.role` literals):
`ReadOnly`, `IndividualContributor`, `OrganizationContributor`, `Superuser`.
If the attribute carries several values, the strongest wins (Superuser >
OrganizationContributor > IndividualContributor > ReadOnly); a user without the attribute
is treated as `ReadOnly`.

**Group** (attribute `group`): `Partner` or `Subscriber`. Carried into the security context
as `GROUP_Partner` / `GROUP_Subscriber` authorities and exposed by `/api/auth/me`; no
endpoint restriction is currently keyed off them.

**Provisioning:** every successful login upserts the `catalog_users` row (role,
organization) and creates the `organizations` row on first sight
(`service/UserProvisioningService`). The DB row is what the ownership logic
(`AuthService.canEdit`, organization members, maintainer options) queries.

## 3. Backend configuration

`src/main/resources/application.properties`:

```properties
spring.security.oauth2.client.registration.keycloak.client-id=integration-catalog
spring.security.oauth2.client.registration.keycloak.client-secret=...
spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email
spring.security.oauth2.client.provider.keycloak.issuer-uri=http://localhost:8081/realms/integration-catalog
spring.security.oauth2.client.provider.keycloak.user-name-attribute=preferred_username

# Shared secret for the Jenkins build callbacks (X-Callback-Token header).
jenkins.callbackToken=
```

> The issuer is resolved at startup, so **Keycloak must be running before the backend
> starts** (see `keycloak_for_auth/` — `docker compose up -d`).

Key classes (package `security`):

- `SecurityConfig` — filter chain: endpoint matrix, `oauth2Login()`, RP-initiated logout,
  SPA CSRF, 401 entry point for `/api/**`.
- `CatalogOidcUserService` — maps `roles`/`groups` claims to authorities, triggers
  provisioning.
- `JenkinsCallbackFilter` — shared-secret check for `/api/upload/continue/**`; rejects
  everything while `jenkins.callbackToken` is unset.

## 4. Endpoint authorization matrix

Enforced centrally in `security/SecurityConfig`. On top of the matrix, data-dependent
ownership (`AuthService.canEdit`: maintainer / same organization / author / superuser) and
review-state rules stay enforced in the service layer.

### Public (anonymous)

- All catalog reads: `GET /api/applications`, `GET /api/applications/{id}` (+ logo,
  implementations, request, downloads-count), search POSTs
  (`/api/applications/search/*/*`, `/api/integration-methods/search/*/*`), codelists
  (`application-tags`, `integration-method-types`, `midpoint-versions`,
  `countries-of-origin`, `capabilities`, `categories/counts`), `connectors/active`,
  `connectors/catalog`, statistics, `recently-used` (GET), requests (GET), vote counts and
  `votes/check` (answers `false` for anonymous), tutorial listing/download, bundle download.
- The SPA itself, its static assets, Swagger UI.

### Any authenticated user (including ReadOnly)

- `GET /api/auth/me`, `GET /api/auth/organization/members`
- `POST /api/recently-used/{applicationId}`

ReadOnly stops here: beyond these, it may only browse and download (the anonymous set).

### Contributors (IndividualContributor, OrganizationContributor, Superuser)

- `POST /api/requests` — create a request (the session user is recorded as requester)
- `DELETE /api/requests/{requestId}` — cancel a request; the service allows only the
  recorded requester or a superuser (403 otherwise)
- `POST /api/requests/{id}/vote` — vote (identity from the session)
- `POST /api/upload/connector`,
  `GET /api/upload/check-bundle-name`, `GET /api/upload/check-version`
- `PUT`/`POST`/`DELETE` under `/api/applications/{appId}/integration-method/...`
  (edit revision, connectors add/update/remove, compatibility, tutorial upload/delete)
- `POST`/`DELETE /api/applications/{id}/logo`, `POST /api/integration-methods/{id}/tutorial`

### Superuser only

- Review workflow: `POST .../start-review`, `.../stop-review`, `.../publish`, `.../reject`
- `GET /api/auth/all-maintainers`

### Machine (shared secret, no session)

- `POST /api/upload/verify`, `POST /api/upload/continue/{oid}` and
  `POST /api/upload/continue/fail/{oid}` — Jenkins build callbacks; require header
  `X-Callback-Token: <jenkins.callbackToken>`.

Everything not matched above under `/api/**` requires authentication (deny-by-default).

## 5. Test users (dev realm import)

`keycloak_for_auth/import/integration-catalog-realm.json` (password = username unless noted):

| User | Password | Role | Organization | Group |
|---|---|---|---|---|
| `kcuser` | `StrongPassword` | Superuser | Evolveum | — |
| `u1` | `u1` | OrganizationContributor | Acme co. | Partner |
| `u2` | `u2` | ReadOnly | — | — |
| `u3` | `u3` | IndividualContributor | — | — |
| `u4` | `u4` | IndividualContributor | Acme co. | Subscriber |
| `u5` | `u5` | Superuser | Evolveum | — |

Keycloak admin console: http://localhost:8081 (`admin` / `VeryStrongAdminPassword`).

> The realm is imported on first start only. If you already have a `keycloak_for_auth/data`
> volume from an earlier run, wipe it (or re-import the realm manually) to pick up the
> roles/groups/mappers added for the catalog.

## 6. Database

Schema version **5**: `catalog_users.password` is nullable and unused (authentication moved
to Keycloak). Existing databases: run `config/sql/upgrade/upgrade.sql`. The seed users in
`config/sql/02_data.sql` mirror the Keycloak test users; any other Keycloak user is
provisioned automatically on first login.
