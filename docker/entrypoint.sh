#!/bin/sh
# Copyright (c) 2010-2025 Evolveum and contributors
#
# Licensed under the EUPL-1.2 or later.

# Reads secrets from files and sets environment variables for Spring Boot

# Read DB password from secret file
if [ -f "$SPRING_DATASOURCE_PASSWORD_FILE" ]; then
    export SPRING_DATASOURCE_PASSWORD=$(cat "$SPRING_DATASOURCE_PASSWORD_FILE")
fi

# Read GitHub token from secret file
if [ -f "$GITHUB_TOKEN_FILE" ]; then
    export GITHUB_TOKEN=$(cat "$GITHUB_TOKEN_FILE")
fi

# Read Jenkins token from secret file
if [ -f "$JENKINS_TOKEN_FILE" ]; then
    export JENKINS_TOKEN=$(cat "$JENKINS_TOKEN_FILE")
fi

# Read Jenkins username from secret file
if [ -f "$JENKINS_USERNAME_FILE" ]; then
    export JENKINS_USERNAME=$(cat "$JENKINS_USERNAME_FILE")
fi

# Run Spring Boot application
exec java -jar /integration-catalog/integration-catalog.jar
