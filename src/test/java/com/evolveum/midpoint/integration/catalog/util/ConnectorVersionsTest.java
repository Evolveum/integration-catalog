/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */
package com.evolveum.midpoint.integration.catalog.util;

import com.evolveum.midpoint.integration.catalog.object.Connector;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodConnector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorVersionsTest {

    @Test
    void usesNewestVersionWithoutMax() {
        assertThat(usedVersion(null, "1.0.0", "1.10.0", "1.9.0")).isEqualTo("1.10.0");
    }

    @Test
    void neverGoesAboveMax() {
        assertThat(usedVersion("1.9.5", "1.0.0", "1.10.0", "1.9.0")).isEqualTo("1.9.0");
        assertThat(usedVersion("1.9", "1.9.0", "2.0.0")).isEqualTo("1.9.0");
    }

    @Test
    void nothingWhenEveryVersionIsAboveMax() {
        assertThat(usedVersion("0.9", "1.0.0", "2.0.0")).isNull();
    }

    @Test
    void comparesPartsNumerically() {
        assertThat(ConnectorVersions.compare("1.10", "1.9")).isPositive();
        assertThat(ConnectorVersions.compare("1.0", "1.0.0")).isZero();
        assertThat(ConnectorVersions.compare("1.5.0.333", "1.5.0")).isPositive();
    }

    private static String usedVersion(String max, String... versions) {
        Connector connector = new Connector();
        List<ConnectorVersion> rows = new ArrayList<>();
        int id = 1;
        for (String version : versions) {
            ConnectorBundleVersion cbv = new ConnectorBundleVersion();
            cbv.setBundleVersion(version);
            ConnectorVersion cv = new ConnectorVersion();
            cv.setId(id++);
            cv.setRevision(version);
            cv.setConnectorBundleVersion(cbv);
            rows.add(cv);
        }
        connector.setConnectorVersions(rows);
        IntegrationMethodConnector link = new IntegrationMethodConnector();
        link.setConnector(connector);
        link.setConnectorMaxVersion(max);
        return ConnectorVersions.used(link).map(ConnectorVersions::versionOf).orElse(null);
    }
}
