# Copyright (c) 2010-2025 Evolveum and contributors
#
# Licensed under the EUPL-1.2 or later.

# --- Stage 1: Build Angular frontend ---
FROM node:22-alpine AS frontend-build
WORKDIR /app

# Copy only package.json first to avoid reinstalling deps unnecessarily
COPY angular-frontend/package*.json ./
RUN npm install

# Copy full source AFTER install (improves caching & performance)
COPY angular-frontend/ ./

# Cache-buster to force rebuild when frontend changes
ARG FRONTEND_CACHEBUST=1
RUN echo "Cachebust=$FRONTEND_CACHEBUST"

RUN npm run build --prod


# --- Stage 2: Build Spring Boot backend ---
FROM maven:3.9.4-eclipse-temurin-21 AS backend-build
WORKDIR /app

COPY pom.xml ./
RUN mvn -q dependency:go-offline

COPY src ./src

# Copy frontend build output (cleaner and faster)
COPY --from=frontend-build /app/dist/ic-frontend-ngmodule/browser/ ./src/main/resources/static/

ARG BACKEND_CACHEBUST=1
RUN echo "Cachebust=$BACKEND_CACHEBUST"

RUN mvn -q clean package -DskipTests


# --- Stage 3: Runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /integration-catalog

COPY --from=backend-build /app/target/integration-catalog-*.jar integration-catalog.jar

# Copy entrypoint script to handle secrets
COPY docker/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

EXPOSE 8080

# Start application using entrypoint script
ENTRYPOINT ["./entrypoint.sh"]
