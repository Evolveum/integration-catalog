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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

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

    /** Folder name (and the value stored in integration_method.file_path) for a given method revision. */
    public String folderName(UUID integrationMethodId, String revision) {
        return integrationMethodId + "_" + sanitizeSegment(revision);
    }

    @Transactional
    public void saveTutorial(UUID integrationMethodId, MultipartFile file) throws IOException {
        IntegrationMethod method = integrationMethodRepository.findFirstByIdOrderByCreatedAtDesc(integrationMethodId)
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + integrationMethodId));
        saveTutorialForRevision(integrationMethodId, method.getRevision(), file);
    }

    @Transactional
    public void saveTutorialForRevision(UUID integrationMethodId, String revision, MultipartFile file) throws IOException {
        validateFile(file);

        if (!integrationMethodRepository.existsById(new IntegrationMethodId(integrationMethodId, revision))) {
            throw new RuntimeException("Integration method not found: " + integrationMethodId + "/" + revision);
        }

        String folder = folderName(integrationMethodId, revision);
        Path folderPath = basePath.resolve(folder);
        Files.createDirectories(folderPath);

        String fileName = uniqueFileName(folderPath, sanitizeFileName(file.getOriginalFilename()));
        Path target = folderPath.resolve(fileName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        updateFilePath(integrationMethodId, revision, folder);
        log.info("Saved tutorial file for integration method {}/{}: {}", integrationMethodId, revision, fileName);
    }

    /** Lists the tutorial file names stored for a given method revision. */
    public List<String> listTutorialFiles(UUID integrationMethodId, String revision) {
        Path folderPath = basePath.resolve(folderName(integrationMethodId, revision));
        if (!Files.isDirectory(folderPath)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(folderPath)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list tutorial files for " + integrationMethodId + "/" + revision, e);
        }
    }

    /** Resolves a single tutorial file to an absolute path, guarding against path traversal. */
    public Path resolveTutorialFile(UUID integrationMethodId, String revision, String fileName) {
        Path folderPath = basePath.resolve(folderName(integrationMethodId, revision)).normalize();
        Path target = folderPath.resolve(Paths.get(fileName).getFileName().toString()).normalize();
        if (!target.startsWith(folderPath)) {
            throw new IllegalArgumentException("Invalid tutorial file name: " + fileName);
        }
        if (!Files.isRegularFile(target)) {
            throw new RuntimeException("Tutorial file not found: " + fileName);
        }
        return target;
    }

    @Transactional
    public void deleteTutorialFile(UUID integrationMethodId, String revision, String fileName) throws IOException {
        Path folderPath = basePath.resolve(folderName(integrationMethodId, revision)).normalize();
        Path target = folderPath.resolve(Paths.get(fileName).getFileName().toString()).normalize();
        if (!target.startsWith(folderPath)) {
            throw new IllegalArgumentException("Invalid tutorial file name: " + fileName);
        }
        boolean deleted = Files.deleteIfExists(target);
        log.info("Delete tutorial file {} for {}/{}: {}", fileName, integrationMethodId, revision, deleted ? "removed" : "not found");
    }

    /**
     * Copies all tutorial files from one revision folder to another (used when a new revision is created).
     * Returns the destination folder name.
     */
    public String copyTutorialFolder(UUID integrationMethodId, String fromRevision, String toRevision) {
        String destFolder = folderName(integrationMethodId, toRevision);
        Path from = basePath.resolve(folderName(integrationMethodId, fromRevision));
        Path to = basePath.resolve(destFolder);
        if (from.equals(to) || !Files.isDirectory(from)) {
            return destFolder;
        }
        try {
            Files.createDirectories(to);
            try (Stream<Path> entries = Files.list(from)) {
                entries.filter(Files::isRegularFile).forEach(src -> {
                    try {
                        Files.copy(src, to.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy tutorial folder for " + integrationMethodId
                    + " from " + fromRevision + " to " + toRevision, e);
        }
        return destFolder;
    }

    /**
     * Moves a revision's tutorial folder to the target revision (used when a revision is rewritten in
     * place with a bump). Returns the destination folder name; a no-op if the source folder is absent.
     */
    public String renameTutorialFolder(UUID integrationMethodId, String fromRevision, String toRevision) {
        String destFolder = folderName(integrationMethodId, toRevision);
        Path from = basePath.resolve(folderName(integrationMethodId, fromRevision));
        Path to = basePath.resolve(destFolder);
        if (from.equals(to) || !Files.isDirectory(from)) {
            return destFolder;
        }
        try {
            if (Files.exists(to)) {
                deleteDirectoryRecursively(to);
            }
            Files.move(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rename tutorial folder for " + integrationMethodId
                    + " from " + fromRevision + " to " + toRevision, e);
        }
        return destFolder;
    }

    /** Removes a revision's tutorial folder entirely (used when a superseded revision is dropped). */
    public void deleteTutorialFolder(UUID integrationMethodId, String revision) {
        Path folder = basePath.resolve(folderName(integrationMethodId, revision));
        try {
            deleteDirectoryRecursively(folder);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete tutorial folder for "
                    + integrationMethodId + "/" + revision, e);
        }
    }

    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
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

    /** Reduces an uploaded name to a safe base file name (no path separators, restricted character set). */
    private String sanitizeFileName(String original) {
        String base = original == null ? "" : Paths.get(original).getFileName().toString();
        base = base.replaceAll("[^A-Za-z0-9._() -]", "_").trim();
        return base.isEmpty() ? "file" : base;
    }

    private String sanitizeSegment(String segment) {
        return segment == null ? "" : segment.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Appends " (n)" before the extension until the name is free within the folder. */
    private String uniqueFileName(Path folder, String fileName) {
        Path candidate = folder.resolve(fileName);
        if (!Files.exists(candidate)) {
            return fileName;
        }
        String ext = getFileExtension(fileName);
        String stem = ext.isEmpty() ? fileName : fileName.substring(0, fileName.length() - ext.length());
        for (int i = 1; ; i++) {
            String next = stem + " (" + i + ")" + ext;
            if (!Files.exists(folder.resolve(next))) {
                return next;
            }
        }
    }
}
