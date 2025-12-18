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

/**
 * Service for managing logo file storage on disk.
 */
@Slf4j
@Service
public class LogoStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/gif",
            "image/svg+xml",
            "image/webp"
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
    }

    /**
     * Validates the uploaded file for size and content type.
     *
     * @param file the uploaded file
     * @throws IllegalArgumentException if validation fails
     */
    public void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Logo file is required");
        }

        // Validate file size
        if (file.getSize() > logoStorageProperties.maxSizeBytes()) {
            throw new IllegalArgumentException(
                    String.format("Logo file size exceeds maximum allowed size of %d bytes",
                            logoStorageProperties.maxSizeBytes()));
        }

        // Validate content type
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    String.format("Invalid content type '%s'. Allowed types: %s",
                            contentType, ALLOWED_CONTENT_TYPES));
        }

        // Validate file extension
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null) {
            String extension = getFileExtension(originalFilename).toLowerCase();
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new IllegalArgumentException(
                        String.format("Invalid file extension '%s'. Allowed extensions: %s",
                                extension, ALLOWED_EXTENSIONS));
            }
        }
    }

    /**
     * Generates a safe file name using UUID with the original file extension.
     *
     * @param originalFilename the original file name
     * @return a UUID-based safe file name
     */
    public String generateSafeFileName(String originalFilename) {
        String extension = getFileExtension(originalFilename);
        return UUID.randomUUID().toString() + extension;
    }

    /**
     * Saves a logo file to disk and updates the application entity.
     *
     * @param application the application to update
     * @param file the logo file to save
     * @return the updated application
     * @throws IOException if file operation fails
     */
    @Transactional
    public Application saveLogo(Application application, MultipartFile file) throws IOException {
        validateFile(file);

        // Delete old logo if exists
        deleteLogoFile(application.getLogoPath());

        // Generate safe file name
        String safeFileName = generateSafeFileName(file.getOriginalFilename());
        Path targetPath = getLogoPath(safeFileName);

        // Ensure directory exists
        Files.createDirectories(targetPath.getParent());

        // Save file to disk
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        // Update application entity with logo metadata
        application.setLogoPath(safeFileName);
        application.setLogoContentType(file.getContentType());
        application.setLogoOriginalName(file.getOriginalFilename());
        application.setLogoSizeBytes(file.getSize());

        log.info("Saved logo for application {}: {} ({} bytes)",
                application.getId(), safeFileName, file.getSize());

        return applicationRepository.save(application);
    }

    /**
     * Deletes the logo file from disk and clears metadata from application.
     *
     * @param application the application to update
     * @return the updated application
     */
    @Transactional
    public Application deleteLogo(Application application) {
        String logoPath = application.getLogoPath();

        if (logoPath != null) {
            deleteLogoFile(logoPath);
        }

        // Clear logo metadata
        application.setLogoPath(null);
        application.setLogoContentType(null);
        application.setLogoOriginalName(null);
        application.setLogoSizeBytes(null);

        log.info("Deleted logo for application {}", application.getId());

        return applicationRepository.save(application);
    }

    /**
     * Loads logo file bytes from disk.
     *
     * @param logoPath the relative path to the logo file
     * @return the file bytes, or null if not found
     */
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

    /**
     * Gets the full path to a logo file.
     *
     * @param fileName the file name
     * @return the full path
     */
    public Path getLogoPath(String fileName) {
        return basePath.resolve(fileName);
    }

    /**
     * Deletes a logo file from disk.
     *
     * @param logoPath the relative path to the logo file
     */
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

    /**
     * Extracts the file extension from a filename.
     *
     * @param filename the file name
     * @return the extension including the dot, or empty string
     */
    private String getFileExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) {
            return "";
        }
        return filename.substring(lastDot);
    }

    /**
     * Checks if a logo file exists on disk.
     *
     * @param logoPath the relative path to the logo file
     * @return true if the file exists
     */
    public boolean logoExists(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) {
            return false;
        }
        return Files.exists(getLogoPath(logoPath));
    }

    /**
     * Gets the configured base path for logo storage.
     *
     * @return the base path
     */
    public String getBasePath() {
        return logoStorageProperties.basePath();
    }

    /**
     * Gets the configured maximum file size in bytes.
     *
     * @return the max size in bytes
     */
    public long getMaxSizeBytes() {
        return logoStorageProperties.maxSizeBytes();
    }
}
