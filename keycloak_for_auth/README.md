# Keycloak for Integration Catalog authentication

Dev/test Keycloak instance acting as the OIDC identity provider for the Integration
Catalog (see [docs/authentication.md](../docs/authentication.md)).

# Prerequisites
Docker, Docker compose installed and available

# First steps
- Run it:
```
docker compose up -d
```
- Keycloak listens on http://localhost:8081 and imports the `integration-catalog` realm
  (roles, organizations, the `integration-catalog` OIDC client and test users)
  on first start.

> The realm is imported only when the database is empty. After changing
> `import/integration-catalog-realm.json`, wipe `./data/postgres` (or import manually in the admin
> console) to pick up the changes.

# Usage
## Login to the Integration Catalog
Test users (password = username unless noted):

| User | Password | Role | Organization |
|---|---|---|---|
| kcuser | StrongPassword | Superuser | Evolveum |
| u1 | u1 | OrganizationContributor | Acme co. |
| u2 | u2 | ReadOnly | — |
| u3 | u3 | IndividualContributor | — |
| u4 | u4 | IndividualContributor | Acme co. |
| u5 | u5 | Superuser | Evolveum |

## Login to Keycloak as admin
- Visit http://localhost:8081
- Use credentials admin / VeryStrongAdminPassword
