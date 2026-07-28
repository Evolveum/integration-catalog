# integration-catalog

The integration catalog contains a list of connectors that represent possible application integrations and provides 
a set of available operations that can be performed on them. It serves as a central point for managing application integrations, 
allowing users to easily browse, upload, or download existing connectors.

---

## Running with Docker

### Prerequisites

Make sure you have installed:

- [Docker](https://www.docker.com/get-started)
- [Docker Compose](https://docs.docker.com/compose/) (v2+)

Also, verify that the following **secret files** exist in `docker/secrets/`:

* db_password.txt
* github_token.txt
* jenkins_token.txt
* jenkins_username.txt

### 1️⃣ Navigate to the Docker folder

```bash
cd docker
```

This folder contains **docker-compose.yml** and entrypoint scripts.


### 2️⃣ Build and start the containers
```bash
docker-compose up --build
```

* `--build` ensures that Angular frontend and Spring Boot backend are rebuilt.
* Two containers will start:
  * `db` → PostgreSQL database (with init scripts executed automatically)
  * `integration-catalog` → Spring Boot application with Angular 19.2.11 frontend


### 3️⃣ Verify the services

* Spring Boot app: http://localhost:8080
* PostgreSQL database: port `5432`

Check logs:
```bash
docker-compose logs -f integration-catalog
docker-compose logs -f db
```

### 4️⃣ Stopping and cleaning up

* Stop containers:
```bash
docker-compose down
```
* To remove persistent DB data and restart clean:
```bash
docker-compose down -v
docker-compose up --build
```

### Notes

* The application reads secrets from the mounted secret files and sets them as environment variables automatically.
* SQL scripts in `config/sql/` is executed only on the first database initialization.

---

## Database schema versioning

The database schema version is tracked in the `database_version` table (the current version
is the highest `version` row). On startup the application compares it with the version it
requires (`DatabaseSchemaVersionValidator.REQUIRED_VERSION`) and refuses to start with a clear
error message when the database is outdated (or newer than the application build).

* **Fresh database:** `config/sql/01_schema.sql` + `02_data.sql` create the schema already at
  the current version. In Docker this happens automatically on the first start of the `db`
  container.
* **Existing database:** re-run the whole cumulative upgrade script:
```bash
psql -v ON_ERROR_STOP=1 -U integration_catalog -d integration_catalog -f config/sql/upgrade/upgrade.sql
```
  The script has one section per schema version and is safe to run repeatedly. From
  version 5 on, each section is a `call apply_change(N, ...)` that executes only when the
  database version is lower than `N`, records the version row and commits — the SQL inside
  no longer has to be idempotent. Use plain `psql` (not pgAdmin or other tools with their
  own transaction handling): `apply_change` commits internally, which fails inside a
  wrapping transaction block.

### Adding a schema change

1. Append a new `-- region version N: <name>` section to `config/sql/upgrade/upgrade.sql`:
   ```sql
   call apply_change(N, '<short description>', $aa$
   <any SQL, does not have to be idempotent>
   $aa$);
   ```
   If a statement depends on a previous one being committed (typically
   `ALTER TYPE ... ADD VALUE` followed by a use of the new value), split them into two
   `apply_change` calls (two versions).
2. Make the same change in `config/sql/01_schema.sql` and bump the version inserted at the
   end of that script.
3. Bump `REQUIRED_VERSION` in `DatabaseSchemaVersionValidator`.

---

## License

This project is licensed under **European Union Public License**. 