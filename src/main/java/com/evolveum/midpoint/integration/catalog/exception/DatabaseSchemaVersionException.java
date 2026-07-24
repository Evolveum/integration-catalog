/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.exception;

/**
 * Thrown on startup when the database schema version cannot be determined or does not
 * match the version required by this application build.
 */
public class DatabaseSchemaVersionException extends RuntimeException {

    public DatabaseSchemaVersionException(String message) {
        super(message);
    }

    public DatabaseSchemaVersionException(String message, Throwable cause) {
        super(message, cause);
    }
}
