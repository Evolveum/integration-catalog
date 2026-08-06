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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Verifies on startup that the database schema version matches the version required by
 * this application build and aborts the startup with a clear error message when it does
 * not (missing version table, outdated database, or database newer than the application).
 *
 * The version is tracked as the 'schemaChangeNumber' row of the m_global_metadata table
 * (the same mechanism midPoint's native repository uses), maintained by the cumulative
 * config/sql/upgrade/upgrade.sql script.
 */
@Component
public class DatabaseSchemaVersionValidator {

    /**
     * Schema change number required by this build. Bump together with every new
     * apply_change section appended to config/sql/upgrade/upgrade.sql and the number in
     * the apply_change call at the end of config/sql/01_schema.sql.
     */
    public static final int REQUIRED_VERSION = 2;

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
                    "Database schema version cannot be determined: table 'm_global_metadata' has no "
                            + "'schemaChangeNumber' row. The required database update has not been applied. "
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
        String value;
        try {
            value = jdbcTemplate.queryForObject(
                    "SELECT value FROM m_global_metadata WHERE name = 'schemaChangeNumber'", String.class);
        } catch (EmptyResultDataAccessException e) {
            // no 'schemaChangeNumber' row
            return null;
        } catch (BadSqlGrammarException e) {
            if (e.getSQLException() != null
                    && UNDEFINED_TABLE_SQL_STATE.equals(e.getSQLException().getSQLState())) {
                throw new DatabaseSchemaVersionException(
                        "Database schema version cannot be determined: table 'm_global_metadata' does not exist. "
                                + "The required database update has not been applied. "
                                + "Run the config/sql/upgrade/upgrade.sql script against the database.",
                        e);
            }
            throw e;
        }
        if (value == null) {
            throw new DatabaseSchemaVersionException(
                    "Database schema version cannot be determined: the 'schemaChangeNumber' row of "
                            + "table 'm_global_metadata' holds NULL value. The required database update "
                            + "has not been applied correctly. "
                            + "Run the config/sql/upgrade/upgrade.sql script against the database.");
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new DatabaseSchemaVersionException(
                    "Database schema version cannot be determined: the 'schemaChangeNumber' row of "
                            + "table 'm_global_metadata' holds non-numeric value '" + value + "'.", e);
        }
    }
}
