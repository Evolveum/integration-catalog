/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.Connector;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapability;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapabilityItem;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodConnector;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodType;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles a downloadable ZIP bundle for an integration-method revision, containing:
 * <ul>
 *     <li>the tutorial text (integration_method.tutorial) as {@code tutorial.md};</li>
 *     <li>every uploaded tutorial file from the method's file_path folder, under {@code files/};</li>
 *     <li>JSON metadata for the application, integration method, and connectors, under {@code metadata/}.</li>
 * </ul>
 */
@Slf4j
@Service
public class BundleService {

    private final IntegrationMethodRepository integrationMethodRepository;
    private final TutorialStorageService tutorialStorageService;
    private final ObjectWriter jsonWriter;

    public BundleService(IntegrationMethodRepository integrationMethodRepository,
                         TutorialStorageService tutorialStorageService,
                         ObjectMapper objectMapper) {
        this.integrationMethodRepository = integrationMethodRepository;
        this.tutorialStorageService = tutorialStorageService;
        this.jsonWriter = objectMapper.writerWithDefaultPrettyPrinter();
    }

    /** A built ZIP bundle together with a suggested download file name. */
    public record Bundle(String fileName, byte[] data) {}

    /**
     * Builds the bundle and returns the ZIP bytes plus a suggested file name.
     * Runs in a read-only transaction so the metadata builders can traverse the
     * method's lazy associations (application, connectors, capabilities).
     */
    @Transactional(readOnly = true)
    public Bundle buildBundle(UUID methodId, String revision) throws IOException {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Integration method not found: " + methodId + "/" + revision));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            addTutorialXml(zip, method);
            addTutorialFiles(zip, methodId, revision);
            addMetadata(zip, method);
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

    /** Writes JSON metadata for the application, integration method and connectors under {@code metadata/}. */
    private void addMetadata(ZipOutputStream zip, IntegrationMethod method) throws IOException {
        writeJson(zip, "metadata/application.json", buildApplicationMetadata(method.getApplication()));
        writeJson(zip, "metadata/integration-method.json", buildIntegrationMethodMetadata(method));
        writeJson(zip, "metadata/connectors.json", buildConnectorsMetadata(method));
    }

    private void writeJson(ZipOutputStream zip, String entryName, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(jsonWriter.writeValueAsBytes(value));
        zip.closeEntry();
    }

    private Map<String, Object> buildApplicationMetadata(Application app) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (app == null) {
            return meta;
        }
        meta.put("id", app.getId());
        meta.put("name", app.getName());
        meta.put("displayName", app.getDisplayName());
        meta.put("description", app.getDescription());
        meta.put("lifecycleState", app.getLifecycleState());
        meta.put("createdAt", app.getCreatedAt());
        meta.put("updated", app.getUpdated());
        return meta;
    }

    private Map<String, Object> buildIntegrationMethodMetadata(IntegrationMethod method) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", method.getId());
        meta.put("revision", method.getRevision());
        meta.put("displayName", method.getDisplayName());
        meta.put("description", method.getDescription());
        meta.put("lifecycleState", method.getLifecycleState());
        meta.put("author", method.getAuthor());
        meta.put("maintainer", method.getMaintainer());
        meta.put("appVersion", method.getAppVersion());
        meta.put("midpointMinVersionId", method.getMidpointMinVersionId());
        meta.put("midpointMaxVersionId", method.getMidpointMaxVersionId());
        meta.put("createdAt", method.getCreatedAt());
        meta.put("updated", method.getUpdated());

        List<String> types = new ArrayList<>();
        for (IntegrationMethodType type : method.getIntegMethodTypes()) {
            types.add(type.getDisplayName());
        }
        meta.put("types", types);

        List<Map<String, Object>> capabilities = new ArrayList<>();
        for (IntegrationMethodCapability cap : method.getCapabilities()) {
            Map<String, Object> capMeta = new LinkedHashMap<>();
            capMeta.put("objectClass", cap.getObjectClass());
            List<String> names = new ArrayList<>();
            for (IntegrationMethodCapabilityItem item : cap.getItems()) {
                if (item.getCapability() != null) {
                    names.add(item.getCapability().getName());
                }
            }
            capMeta.put("capabilities", names);
            capabilities.add(capMeta);
        }
        meta.put("capabilities", capabilities);
        return meta;
    }

    private List<Map<String, Object>> buildConnectorsMetadata(IntegrationMethod method) {
        List<Map<String, Object>> connectors = new ArrayList<>();
        for (IntegrationMethodConnector link : method.getConnectors()) {
            Connector connector = link.getConnector();
            if (connector == null) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", connector.getId());
            meta.put("displayName", connector.getDisplayName());
            meta.put("fullyQualifiedClassName", connector.getFullyQualifiedClassName());
            meta.put("description", connector.getDescription());
            meta.put("author", connector.getAuthor());
            meta.put("maintainer", connector.getMaintainer());
            meta.put("revision", connector.getRevision());
            meta.put("connectorMinVersion", link.getConnectorMinVersion());
            meta.put("connectorMaxVersion", link.getConnectorMaxVersion());

            ConnectorBundle bundle = connector.getConnectorBundle();
            if (bundle != null) {
                Map<String, Object> bundleMeta = new LinkedHashMap<>();
                bundleMeta.put("id", bundle.getId());
                bundleMeta.put("bundleName", bundle.getBundleName());
                bundleMeta.put("displayName", bundle.getDisplayName());
                bundleMeta.put("framework", bundle.getFramework());
                bundleMeta.put("license", bundle.getLicense());
                meta.put("connectorBundle", bundleMeta);
            }
            connectors.add(meta);
        }
        return connectors;
    }
}
