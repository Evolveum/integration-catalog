/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersion;
import com.evolveum.midpoint.integration.catalog.object.Download;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodConnector;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorBundleVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.DownloadRepository;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.util.UserAgentParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorDownloadService {

    private static final long DOWNLOAD_OFFSET_SECONDS = 10;

    private final IntegrationMethodRepository integrationMethodRepository;
    private final ConnectorBundleVersionRepository connectorBundleVersionRepository;
    private final DownloadRepository downloadRepository;

    /**
     * Downloads connector files for the latest active connector bundle version
     * linked to the given integration method UUID.
     */
    public byte[] downloadConnector(UUID integMethodId, String ip, String userAgent) throws IOException {
        List<IntegrationMethod> methods = integrationMethodRepository.findByApplicationId(integMethodId);
        IntegrationMethod method = methods.stream()
                .filter(m -> m.getId().equals(integMethodId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Integration method not found: " + integMethodId));

        ConnectorBundleVersion bundleVersion = resolveLatestBundleVersion(method);
        if (bundleVersion == null || bundleVersion.getBrowseLink() == null) {
            throw new IllegalArgumentException("No download link available for integration method: " + integMethodId);
        }

        try (InputStream in = URI.create(bundleVersion.getBrowseLink()).toURL().openStream()) {
            byte[] fileBytes = in.readAllBytes();
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(DOWNLOAD_OFFSET_SECONDS);
            recordDownloadIfNew(bundleVersion, ip, userAgent, cutoff);
            return fileBytes;
        }
    }

    public void recordDownloadIfNew(ConnectorBundleVersion bundleVersion, String ip, String userAgent,
                                    LocalDateTime cutoff) {
        String browserName = UserAgentParser.parseBrowserName(userAgent);
        String deviceType = UserAgentParser.parseDeviceType(userAgent);
        String parsedUserAgent = browserName + "," + deviceType;

        boolean duplicate = downloadRepository
                .existsByConnectorBundleVersionAndIpAddressAndUserAgentAndDownloadedAt(
                        bundleVersion, ip, parsedUserAgent, cutoff);

        if (!duplicate) {
            Download dl = new Download();
            dl.setConnectorBundleVersion(bundleVersion);
            dl.setIpAddress(ip);
            dl.setUserAgent(parsedUserAgent);
            dl.setDownloadedAt(LocalDateTime.now());
            downloadRepository.save(dl);
        }
    }

    private ConnectorBundleVersion resolveLatestBundleVersion(IntegrationMethod method) {
        if (method.getConnectors() == null || method.getConnectors().isEmpty()) {
            return null;
        }
        // Pick first connector link, then its connector's latest bundle version
        IntegrationMethodConnector link = method.getConnectors().get(0);
        if (link.getConnector() == null) {
            return null;
        }
        return link.getConnector().getConnectorVersions().stream()
                .filter(cv -> cv.getConnectorBundleVersion() != null)
                .max(Comparator.comparing(cv -> cv.getConnectorBundleVersion().getUpdated(),
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(cv -> cv.getConnectorBundleVersion())
                .orElse(null);
    }
}
