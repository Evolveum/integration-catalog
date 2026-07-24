/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import com.evolveum.midpoint.integration.catalog.exception.DatabaseSchemaVersionException;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Verifies on startup that the database schema version matches the version required by
 * this application build and aborts the startup with a clear error message when it does
 * not (missing version table, outdated database, or database newer than the application).
 *
 * The version is tracked in the database_version table maintained by the cumulative
 * config/sql/upgrade/upgrade.sql script; the current version is MAX(version).
 */
@Component
public class DatabaseSchemaVersionValidator {

    /**
     * Schema version required by this build. Bump together with every new section appended
     * to config/sql/upgrade/upgrade.sql and the version inserted at the end of
     * config/sql/01_schema.sql.
     */
    public static final int REQUIRED_VERSION = 3;

    private static final String UNDEFINED_TABLE_SQL_STATE = "42P01";

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSchemaVersionValidator.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaVersionValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void validateSchemaVersion() {
        Integer currentVersion = readCurrentVersion();

        if (currentVersion == null) {
            throw new DatabaseSchemaVersionException(
                    "Database schema version cannot be determined: table 'database_version' is empty. "
                            + "The required database update has not been applied. "
                            + "Run the config/sql/upgrade/upgrade.sql script against the database.");
        }
        if (currentVersion < REQUIRED_VERSION) {
            throw new DatabaseSchemaVersionException(
                    "Database schema version " + currentVersion + " is older than version " + REQUIRED_VERSION
                            + " required by this application. The required database update has not been applied. "
                            + "Run the config/sql/upgrade/upgrade.sql script against the database to apply versions "
                            + (currentVersion + 1) + " to " + REQUIRED_VERSION + ".");
        }
        if (currentVersion > REQUIRED_VERSION) {
            throw new DatabaseSchemaVersionException(
                    "Database schema version " + currentVersion + " is newer than version " + REQUIRED_VERSION
                            + " supported by this application. Run a newer application build against this database.");
        }
        LOGGER.info("Database schema version {} matches the version required by the application.", currentVersion);
    }

    private Integer readCurrentVersion() {
        try {
            return jdbcTemplate.queryForObject("SELECT max(version) FROM database_version", Integer.class);
        } catch (BadSqlGrammarException e) {
            if (e.getSQLException() != null
                    && UNDEFINED_TABLE_SQL_STATE.equals(e.getSQLException().getSQLState())) {
                throw new DatabaseSchemaVersionException(
                        "Database schema version cannot be determined: table 'database_version' does not exist. "
                                + "The required database update has not been applied. "
                                + "Run the config/sql/upgrade/upgrade.sql script against the database.",
                        e);
            }
            throw e;
        }
    }
}
