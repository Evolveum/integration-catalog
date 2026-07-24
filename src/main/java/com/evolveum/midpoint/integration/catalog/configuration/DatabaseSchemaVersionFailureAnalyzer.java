/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import com.evolveum.midpoint.integration.catalog.exception.DatabaseSchemaVersionException;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a schema version mismatch into a readable "Description / Action" startup failure
 * report instead of a bare stack trace. Registered in META-INF/spring.factories.
 */
public class DatabaseSchemaVersionFailureAnalyzer extends AbstractFailureAnalyzer<DatabaseSchemaVersionException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, DatabaseSchemaVersionException cause) {
        return new FailureAnalysis(
                cause.getMessage(),
                "Run the cumulative upgrade script against the database, e.g. "
                        + "psql -U integration_catalog -d integration_catalog -f config/sql/upgrade/upgrade.sql, "
                        + "then start the application again. The script is idempotent - "
                        + "already applied sections are skipped.",
                cause);
    }
}
