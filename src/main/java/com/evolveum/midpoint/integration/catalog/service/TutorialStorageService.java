/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.TutorialStorageProperties;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class TutorialStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/xml", "text/xml",
            "application/json",
            "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml",
            "text/plain"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".xml", ".json", ".yaml", ".yml", ".txt"
    );

    private final TutorialStorageProperties properties;
    private final IntegrationMethodRepository integrationMethodRepository;
    private final Path basePath;

    @PersistenceContext
    private EntityManager entityManager;

    public TutorialStorageService(TutorialStorageProperties properties,
                                   IntegrationMethodRepository integrationMethodRepository) {
        this.properties = properties;
        this.integrationMethodRepository = integrationMethodRepository;
        this.basePath = Paths.get(properties.basePath()).toAbsolutePath().normalize();
        log.info("Tutorial storage base path resolved to: {}", this.basePath);
        initStorageDirectory();
    }

    private void initStorageDirectory() {
        try {
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
                log.info("Created tutorial storage directory: {}", basePath);
            }
            if (!Files.isDirectory(basePath)) {
                throw new IllegalStateException("Tutorial storage path is not a directory: " + basePath);
            }
            if (!Files.isWritable(basePath)) {
                throw new IllegalStateException("Tutorial storage directory is not writable: " + basePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create tutorial storage directory: " + basePath, e);
        }
    }

    public void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Tutorial file is required");
        }
        if (file.getSize() > properties.maxSizeBytes()) {
            throw new IllegalArgumentException(
                    "Tutorial file size exceeds maximum allowed size of " + properties.maxSizeBytes() + " bytes");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null) {
            String ext = getFileExtension(originalFilename).toLowerCase();
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException("Invalid file extension '" + ext + "'. Allowed: pdf, xml, json, yaml, txt");
            }
        }
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase().split(";")[0].trim())) {
            log.warn("Unexpected content type '{}' for tutorial file — accepted based on extension", contentType);
        }
    }

    @Transactional
    public void saveTutorial(UUID integrationMethodId, MultipartFile file) throws IOException {
        validateFile(file);

        IntegrationMethod method = integrationMethodRepository.findFirstByIdOrderByCreatedAtDesc(integrationMethodId)
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + integrationMethodId));

        String safeFileName = UUID.randomUUID() + getFileExtension(file.getOriginalFilename());
        Path target = basePath.resolve(safeFileName);
        Files.createDirectories(target.getParent());
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        updateFilePath(integrationMethodId, method.getRevision(), safeFileName);
        log.info("Saved tutorial for integration method {}: {}", integrationMethodId, safeFileName);
    }

    @Transactional
    public void saveTutorialForRevision(UUID integrationMethodId, String revision, MultipartFile file) throws IOException {
        validateFile(file);

        if (!integrationMethodRepository.existsById(new IntegrationMethodId(integrationMethodId, revision))) {
            throw new RuntimeException("Integration method not found: " + integrationMethodId + "/" + revision);
        }

        String safeFileName = UUID.randomUUID() + getFileExtension(file.getOriginalFilename());
        Path target = basePath.resolve(safeFileName);
        Files.createDirectories(target.getParent());
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        updateFilePath(integrationMethodId, revision, safeFileName);
        log.info("Saved tutorial for integration method {}/{}: {}", integrationMethodId, revision, safeFileName);
    }

    private void updateFilePath(UUID id, String revision, String filePath) {
        entityManager.createQuery(
                "UPDATE IntegrationMethod m SET m.filePath = :filePath WHERE m.id = :id AND m.revision = :revision")
                .setParameter("filePath", filePath)
                .setParameter("id", id)
                .setParameter("revision", revision)
                .executeUpdate();
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot);
    }
}
