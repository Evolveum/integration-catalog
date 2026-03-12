/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.Download;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import com.evolveum.midpoint.integration.catalog.repository.DownloadRepository;
import com.evolveum.midpoint.integration.catalog.repository.ImplementationVersionRepository;
import com.evolveum.midpoint.integration.catalog.util.UserAgentParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Service for handling connector downloads and tracking download statistics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorDownloadService {

    private static final long DOWNLOAD_OFFSET_SECONDS = 10;

    private final ImplementationVersionRepository implementationVersionRepository;
    private final DownloadRepository downloadRepository;

    /**
     * Downloads a connector by version ID and records the download.
     *
     * @param versionId the implementation version UUID
     * @param ip the client IP address
     * @param userAgent the client user agent string
     * @return the connector file bytes
     * @throws IOException if download fails
     * @throws IllegalArgumentException if version not found or no download link available
     */
    public byte[] downloadConnector(UUID versionId, String ip, String userAgent) throws IOException {
        ImplementationVersion version = implementationVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));

        // Get download link from BundleVersion
        String downloadLink = (version.getBundleVersion() != null) ?
                version.getBundleVersion().getDownloadLink() : null;

        if (downloadLink == null || downloadLink.isEmpty()) {
            throw new IllegalArgumentException("No download link available for version: " + versionId);
        }

        try (InputStream in = new URL(downloadLink).openStream()) {
            byte[] fileBytes = in.readAllBytes();

            OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(DOWNLOAD_OFFSET_SECONDS);
            recordDownloadIfNew(version, ip, userAgent, cutoff);

            return fileBytes;
        }
    }

    /**
     * Records a download if it's not a duplicate within the cutoff period.
     * Parses the user agent to extract browser name and device type.
     *
     * @param version the implementation version being downloaded
     * @param ip the client IP address
     * @param userAgent the raw user agent string
     * @param cutoff the time threshold for duplicate detection
     */
    public void recordDownloadIfNew(ImplementationVersion version, String ip, String userAgent, OffsetDateTime cutoff) {
        String browserName = UserAgentParser.parseBrowserName(userAgent);
        String deviceType = UserAgentParser.parseDeviceType(userAgent);
        String parsedUserAgent = browserName + "," + deviceType;

        boolean duplicate = downloadRepository
                .existsByImplementationVersionAndIpAddressAndUserAgentAndDownloadedAt(
                        version, ip, parsedUserAgent, cutoff);

        if (!duplicate) {
            Download dl = new Download();
            dl.setImplementationVersion(version);
            dl.setIpAddress(ip);
            dl.setUserAgent(parsedUserAgent);
            dl.setDownloadedAt(OffsetDateTime.now());
            downloadRepository.save(dl);
        }
    }
}
