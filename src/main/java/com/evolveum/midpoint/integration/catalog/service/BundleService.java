/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles a downloadable ZIP bundle for an integration-method revision, containing:
 * <ul>
 *     <li>the tutorial text (integration_method.tutorial) as {@code tutorial.xml};</li>
 *     <li>every uploaded tutorial file from the method's file_path folder, under {@code files/};</li>
 *     <li>a Notepad++ installer fetched from its pinned GitHub release (for testing).</li>
 * </ul>
 */
@Slf4j
@Service
public class BundleService {

    /**
     * Pinned, version-locked Notepad++ installer. The release tag never moves, so the URL is stable
     * and needs no version checking. Bump deliberately if a newer build is wanted.
     */
    private static final String NOTEPAD_INSTALLER_URL =
            "https://github.com/notepad-plus-plus/notepad-plus-plus/releases/download/v8.9.6.4/npp.8.9.6.4.Installer.x64.exe";
    private static final String NOTEPAD_INSTALLER_ENTRY = "npp.8.9.6.4.Installer.x64.exe";

    private final IntegrationMethodRepository integrationMethodRepository;
    private final TutorialStorageService tutorialStorageService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub release assets redirect to a CDN host
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public BundleService(IntegrationMethodRepository integrationMethodRepository,
                         TutorialStorageService tutorialStorageService) {
        this.integrationMethodRepository = integrationMethodRepository;
        this.tutorialStorageService = tutorialStorageService;
    }

    /** Builds the bundle and returns the complete ZIP as a byte array. */
    public byte[] buildBundle(UUID methodId, String revision) throws IOException, InterruptedException {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Integration method not found: " + methodId + "/" + revision));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            addTutorialXml(zip, method);
            addTutorialFiles(zip, methodId, revision);
            addNotepadInstaller(zip);
        }
        log.info("Built bundle for integration method {}/{}: {} bytes", methodId, revision, baos.size());
        return baos.toByteArray();
    }

    private void addTutorialXml(ZipOutputStream zip, IntegrationMethod method) throws IOException {
        String tutorial = method.getTutorial();
        if (tutorial == null || tutorial.isBlank()) {
            log.debug("No tutorial text for {}/{}; skipping tutorial.xml", method.getId(), method.getRevision());
            return;
        }
        zip.putNextEntry(new ZipEntry("tutorial.xml"));
        zip.write(tutorial.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void addTutorialFiles(ZipOutputStream zip, UUID methodId, String revision) throws IOException {
        List<String> files = tutorialStorageService.listTutorialFiles(methodId, revision);
        for (String name : files) {
            Path file = tutorialStorageService.resolveTutorialFile(methodId, revision, name);
            zip.putNextEntry(new ZipEntry("files/" + name));
            Files.copy(file, zip);
            zip.closeEntry();
        }
    }

    private void addNotepadInstaller(ZipOutputStream zip) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(NOTEPAD_INSTALLER_URL))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download Notepad++ installer: HTTP " + response.statusCode());
        }
        zip.putNextEntry(new ZipEntry(NOTEPAD_INSTALLER_ENTRY));
        zip.write(response.body());
        zip.closeEntry();
    }
}
