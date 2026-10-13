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

import java.util.Comparator;
import java.util.Optional;

/**
 * Which version of a connector an integration method uses: always the newest one, unless that would
 * go above the connector_maxversion its link declares. Every screen, the download and the metadata
 * pick the version here, so they cannot disagree.
 */
public final class ConnectorVersions {

    /** By version number, then by row id; the greatest is the newest. */
    private static final Comparator<ConnectorVersion> NEWEST_LAST = Comparator
            .comparing(ConnectorVersions::versionOf, ConnectorVersions::compare)
            .thenComparing(ConnectorVersion::getId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private ConnectorVersions() {
    }

    /** The version row the link's integration method uses; empty when every row is above its max. */
    public static Optional<ConnectorVersion> used(IntegrationMethodConnector link) {
        return link == null ? Optional.empty() : used(link.getConnector(), link.getConnectorMaxVersion());
    }

    /**
     * The newest built version row not above {@code maxVersion}. Rows without a bundle version are
     * skipped, as no build stands behind them.
     */
    public static Optional<ConnectorVersion> used(Connector connector, String maxVersion) {
        if (connector == null || connector.getConnectorVersions() == null) {
            return Optional.empty();
        }
        String max = maxVersion == null || maxVersion.isBlank() ? null : maxVersion.trim();
        return connector.getConnectorVersions().stream()
                .filter(cv -> cv.getConnectorBundleVersion() != null)
                .filter(cv -> max == null || compare(versionOf(cv), max) <= 0)
                .max(NEWEST_LAST);
    }

    /** The user-facing version of a row: its bundle version, else its revision. */
    public static String versionOf(ConnectorVersion cv) {
        if (cv == null) {
            return null;
        }
        ConnectorBundleVersion cbv = cv.getConnectorBundleVersion();
        return cbv != null && cbv.getBundleVersion() != null ? cbv.getBundleVersion() : cv.getRevision();
    }

    /**
     * Orders dotted versions part by part, numerically where both parts are numbers (1.10 after 1.9),
     * as text otherwise; a missing part counts as zero, so 1.0 equals 1.0.0. Null sorts first.
     */
    public static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        String[] left = a.trim().split("[.\\-]");
        String[] right = b.trim().split("[.\\-]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            int result = isNumber(l) && isNumber(r)
                    ? new java.math.BigInteger(l).compareTo(new java.math.BigInteger(r))
                    : l.compareToIgnoreCase(r);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static boolean isNumber(String part) {
        return !part.isEmpty() && part.chars().allMatch(Character::isDigit);
    }
}
