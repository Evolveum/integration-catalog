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
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.repository.DownloadRepository;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.util.UserAgentParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;

/**
 * Records download statistics for integration-method bundle downloads. A download is attributed to
 * the latest active connector bundle version linked to the method, and de-duplicated within a short
 * time window per (bundle version, ip, user-agent) so repeated clicks are not over-counted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorDownloadService {

    private static final long DOWNLOAD_OFFSET_SECONDS = 10;

    private final IntegrationMethodRepository integrationMethodRepository;
    private final DownloadRepository downloadRepository;

    /**
     * Records a download for the given integration-method revision against its latest connector bundle
     * version. No-op (other than a debug log) if the method has no linked connector bundle version.
     */
    public void recordMethodDownload(UUID methodId, String revision, String ip, String userAgent) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElse(null);
        if (method == null) {
            log.debug("Integration method {}/{} not found; download not recorded", methodId, revision);
            return;
        }

        ConnectorBundleVersion bundleVersion = resolveLatestBundleVersion(method);
        if (bundleVersion == null) {
            log.debug("No connector bundle version for {}/{}; download not recorded", methodId, revision);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(DOWNLOAD_OFFSET_SECONDS);
        recordDownloadIfNew(bundleVersion, ip, userAgent, cutoff);
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
