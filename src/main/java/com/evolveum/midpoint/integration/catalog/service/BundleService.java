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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles a downloadable ZIP bundle for an integration-method revision, containing:
 * <ul>
 *     <li>the tutorial text (integration_method.tutorial) as {@code tutorial.md};</li>
 *     <li>every uploaded tutorial file from the method's file_path folder, under {@code files/}.</li>
 * </ul>
 */
@Slf4j
@Service
public class BundleService {

    private final IntegrationMethodRepository integrationMethodRepository;
    private final TutorialStorageService tutorialStorageService;

    public BundleService(IntegrationMethodRepository integrationMethodRepository,
                         TutorialStorageService tutorialStorageService) {
        this.integrationMethodRepository = integrationMethodRepository;
        this.tutorialStorageService = tutorialStorageService;
    }

    /** A built ZIP bundle together with a suggested download file name. */
    public record Bundle(String fileName, byte[] data) {}

    /** Builds the bundle and returns the ZIP bytes plus a suggested file name. */
    public Bundle buildBundle(UUID methodId, String revision) throws IOException {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Integration method not found: " + methodId + "/" + revision));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            addTutorialXml(zip, method);
            addTutorialFiles(zip, methodId, revision);
        }
        log.info("Built bundle for integration method {}/{}: {} bytes", methodId, revision, baos.size());
        return new Bundle(buildFileName(method), baos.toByteArray());
    }

    /** Builds a download file name from the method's display name and revision, sanitised for filesystems. */
    private String buildFileName(IntegrationMethod method) {
        String displayName = (method.getDisplayName() == null || method.getDisplayName().isBlank())
                ? method.getId().toString()
                : method.getDisplayName();
        String revision = method.getRevision() == null ? "" : method.getRevision();
        String raw = displayName + "-" + revision;
        String safe = raw.trim().replaceAll("[^a-zA-Z0-9._-]+", "_");
        return safe + ".zip";
    }

    private void addTutorialXml(ZipOutputStream zip, IntegrationMethod method) throws IOException {
        String tutorial = method.getTutorial();
        if (tutorial == null || tutorial.isBlank()) {
            log.debug("No tutorial text for {}/{}; skipping tutorial.md", method.getId(), method.getRevision());
            return;
        }
        zip.putNextEntry(new ZipEntry("tutorial.md"));
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
}
