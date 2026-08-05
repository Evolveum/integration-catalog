/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import com.evolveum.midpoint.integration.catalog.exception.DatabaseSchemaVersionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

import static com.evolveum.midpoint.integration.catalog.configuration.DatabaseSchemaVersionValidator.REQUIRED_VERSION;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DatabaseSchemaVersionValidator}. The JdbcTemplate is mocked,
 * so no database is needed; each test simulates one state the 'schemaChangeNumber' row
 * of the m_global_metadata table can be in and asserts whether startup validation passes
 * or fails with the right message.
 */
class DatabaseSchemaVersionValidatorTest {

    private static final String VERSION_QUERY =
            "SELECT value FROM m_global_metadata WHERE name = 'schemaChangeNumber'";

    private JdbcTemplate jdbcTemplate;
    private DatabaseSchemaVersionValidator validator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        validator = new DatabaseSchemaVersionValidator(jdbcTemplate);
    }

    private void databaseVersionIs(Integer version) {
        when(jdbcTemplate.queryForObject(VERSION_QUERY, String.class))
                .thenReturn(version == null ? null : String.valueOf(version));
    }

    @Test
    void matchingVersionPassesValidation() {
        databaseVersionIs(REQUIRED_VERSION);

        assertThatCode(() -> validator.validateSchemaVersion())
                .doesNotThrowAnyException();
    }

    @Test
    void missingVersionRowFailsWithUpgradeInstruction() {
        when(jdbcTemplate.queryForObject(VERSION_QUERY, String.class))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> validator.validateSchemaVersion())
                .isInstanceOf(DatabaseSchemaVersionException.class)
                .hasMessageContaining("no 'schemaChangeNumber' row")
                .hasMessageContaining("upgrade.sql");
    }

    @Test
    void nullVersionValueFailsWithUpgradeInstruction() {
        databaseVersionIs(null);

        assertThatThrownBy(() -> validator.validateSchemaVersion())
                .isInstanceOf(DatabaseSchemaVersionException.class)
                .hasMessageContaining("'schemaChangeNumber' row")
                .hasMessageContaining("holds NULL value")
                .hasMessageContaining("upgrade.sql");
    }

    @Test
    void nonNumericVersionValueFailsWithClearMessage() {
        when(jdbcTemplate.queryForObject(VERSION_QUERY, String.class)).thenReturn("garbage");

        assertThatThrownBy(() -> validator.validateSchemaVersion())
                .isInstanceOf(DatabaseSchemaVersionException.class)
                .hasMessageContaining("non-numeric value 'garbage'");
    }

    @Test
    void olderVersionFailsAndNamesMissingVersionRange() {
        databaseVersionIs(REQUIRED_VERSION - 2);

        assertThatThrownBy(() -> validator.validateSchemaVersion())
                .isInstanceOf(DatabaseSchemaVersionException.class)
                .hasMessageContaining("older than version " + REQUIRED_VERSION)
                .hasMessageContaining(
                        "apply versions " + (REQUIRED_VERSION - 1) + " to " + REQUIRED_VERSION)
                .hasMessageContaining("upgrade.sql");
    }

    @Test
    void newerVersionFailsAsUnsupportedByThisBuild() {
        databaseVersionIs(REQUIRED_VERSION + 1);

        assertThatThrownBy(() -> validator.validateSchemaVersion())
                .isInstanceOf(DatabaseSchemaVersionException.class)
                .hasMessageContaining("newer than version " + REQUIRED_VERSION)
                .hasMessageContaining("newer application build");
    }

    @Test
    void missingMetadataTableFailsWithUpgradeInstruction() {
        BadSqlGrammarException missingTable = new BadSqlGrammarException(
                "StatementCallback", VERSION_QUERY,
                new SQLException("relation \"m_global_metadata\" does not exist", "42P01"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenThrow(missingTable);

        assertThatThrownBy(() -> validator.validateSchemaVersion())
                .isInstanceOf(DatabaseSchemaVersionException.class)
                .hasMessageContaining("table 'm_global_metadata' does not exist")
                .hasMessageContaining("upgrade.sql")
                .hasCause(missingTable);
    }

    @Test
    void otherSqlGrammarErrorIsRethrownUnchanged() {
        BadSqlGrammarException otherError = new BadSqlGrammarException(
                "StatementCallback", VERSION_QUERY,
                new SQLException("column \"value\" does not exist", "42703"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenThrow(otherError);

        assertThatThrownBy(() -> validator.validateSchemaVersion())
                .isSameAs(otherError);
    }
}
