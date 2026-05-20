/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.LogoStorageProperties;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
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
public class LogoStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/svg+xml", "image/webp"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp"
    );

    private final LogoStorageProperties logoStorageProperties;
    private final ApplicationRepository applicationRepository;
    private final Path basePath;

    public LogoStorageService(LogoStorageProperties logoStorageProperties,
                              ApplicationRepository applicationRepository) {
        this.logoStorageProperties = logoStorageProperties;
        this.applicationRepository = applicationRepository;
        this.basePath = Paths.get(logoStorageProperties.basePath()).toAbsolutePath().normalize();
        log.info("Logo storage base path resolved to: {}", this.basePath);
        validateAndInitializeStorageDirectory();
    }

    private void validateAndInitializeStorageDirectory() {
        try {
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
                log.info("Created logo storage directory: {}", basePath);
            }
            if (!Files.isDirectory(basePath)) {
                throw new IllegalStateException("Logo storage path is not a directory: " + basePath);
            }
            if (!Files.isWritable(basePath)) {
                throw new IllegalStateException("Logo storage directory is not writable: " + basePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create logo storage directory: " + basePath, e);
        }
    }

    public void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Logo file is required");
        }
        if (file.getSize() > logoStorageProperties.maxSizeBytes()) {
            throw new IllegalArgumentException(
                    "Logo file size exceeds maximum allowed size of " + logoStorageProperties.maxSizeBytes() + " bytes");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Invalid content type '" + contentType + "'");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null) {
            String extension = getFileExtension(originalFilename).toLowerCase();
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new IllegalArgumentException("Invalid file extension '" + extension + "'");
            }
        }
    }

    public String generateSafeFileName(String originalFilename) {
        return UUID.randomUUID().toString() + getFileExtension(originalFilename);
    }

    @Transactional
    public Application saveLogo(Application application, MultipartFile file) throws IOException {
        validateFile(file);
        deleteLogoFile(application.getLogoPath());

        String safeFileName = generateSafeFileName(file.getOriginalFilename());
        Path targetPath = getLogoPath(safeFileName);
        Files.createDirectories(targetPath.getParent());
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        application.setLogoPath(safeFileName);
        log.info("Saved logo for application {}: {}", application.getId(), safeFileName);
        return applicationRepository.save(application);
    }

    @Transactional
    public Application deleteLogo(Application application) {
        if (application.getLogoPath() != null) {
            deleteLogoFile(application.getLogoPath());
        }
        application.setLogoPath(null);
        log.info("Deleted logo for application {}", application.getId());
        return applicationRepository.save(application);
    }

    public byte[] loadLogoBytes(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) {
            return null;
        }
        Path filePath = getLogoPath(logoPath);
        if (!Files.exists(filePath)) {
            log.warn("Logo file not found: {}", filePath);
            return null;
        }
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("Failed to read logo file: {}", filePath, e);
            return null;
        }
    }

    public Path getLogoPath(String fileName) {
        return basePath.resolve(fileName);
    }

    private void deleteLogoFile(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) {
            return;
        }
        Path filePath = getLogoPath(logoPath);
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Deleted logo file: {}", filePath);
            }
        } catch (IOException e) {
            log.error("Failed to delete logo file: {}", filePath, e);
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot);
    }

    public boolean logoExists(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) return false;
        return Files.exists(getLogoPath(logoPath));
    }

    public String getBasePath() {
        return logoStorageProperties.basePath();
    }

    public long getMaxSizeBytes() {
        return logoStorageProperties.maxSizeBytes();
    }
}
