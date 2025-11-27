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

## License

This project is licensed under **European Union Public License**. 