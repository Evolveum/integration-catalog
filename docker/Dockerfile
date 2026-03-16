# Copyright (c) 2010-2025 Evolveum and contributors
#
# Licensed under the EUPL-1.2 or later.

# --- Stage 1: Build Angular frontend ---
FROM node:22-alpine AS frontend-build
WORKDIR /integration-catalog/angular-frontend
COPY angular-frontend/package*.json ./
RUN npm install
COPY angular-frontend/ ./
RUN npm run build --prod

# --- Stage 2: Build Maven + Spring Boot backend ---
FROM maven:3.9.4-eclipse-temurin-21 AS backend-build
WORKDIR /integration-catalog/backend
COPY pom.xml .
COPY src ./src
RUN mkdir -p src/main/resources/static
COPY --from=frontend-build /integration-catalog/angular-frontend/dist/ic-frontend-ngmodule/browser/. ./src/main/resources/static/

RUN mvn clean package -DskipTests

# --- Stage 3: Runtime image ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /integration-catalog
COPY --from=backend-build /integration-catalog/backend/target/integration-catalog-*.jar integration-catalog.jar

# Copy entrypoint script to handle secrets
COPY docker/entrypoint.sh /integration-catalog/entrypoint.sh
RUN chmod +x /integration-catalog/entrypoint.sh

EXPOSE 8080

# Start application using entrypoint script
ENTRYPOINT ["/integration-catalog/entrypoint.sh"]