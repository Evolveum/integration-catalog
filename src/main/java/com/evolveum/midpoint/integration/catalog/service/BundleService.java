/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.Connector;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapability;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles a downloadable ZIP bundle for an integration-method revision, containing:
 */
@Slf4j
@Service
public class BundleService {

    /** The reserved object class holding resource-wide capabilities; a connector's, not a method's. */
    private static final String GLOBAL_OBJECT_CLASS = "Global";

    private final IntegrationMethodRepository integrationMethodRepository;
    private final TutorialStorageService tutorialStorageService;
    private final OwnershipService ownershipService;
    private final ObjectWriter jsonWriter;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // Nexus may redirect to a storage host
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    /** Cache of fetched artifact bytes, keyed by artifact URL — release URLs are immutable. */
    private final Map<String, byte[]> artifactCache = new ConcurrentHashMap<>();

    public BundleService(IntegrationMethodRepository integrationMethodRepository,
                         TutorialStorageService tutorialStorageService,
                         OwnershipService ownershipService,
                         ObjectMapper objectMapper) {
        this.integrationMethodRepository = integrationMethodRepository;
        this.tutorialStorageService = tutorialStorageService;
        this.ownershipService = ownershipService;
        this.jsonWriter = objectMapper.writerWithDefaultPrettyPrinter();
    }

    /**
     * A built ZIP bundle together with a suggested download file name and an optional warning.
     * {@code warning} is {@code null} when the bundle is complete; otherwise it is a short summary of
     * how many errors/warnings were found (detailed in the ZIP's ERROR.txt / WARNING.txt), so callers
     * can surface it to the user — the ZIP is still valid either way.
     */
    public record Bundle(String fileName, byte[] data, String warning) {}

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

        // Problems found while assembling the bundle are split by severity: ERROR.txt collects important
        // gaps (a missing connector build JAR), WARNING.txt collects minor ones (no tutorial text or no
        // sample files). Both are advisory — the ZIP is still produced.
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            addTutorial(zip, method, warnings);
            addTutorialFiles(zip, methodId, revision, warnings);
            addMetadata(zip, method);
            addConnectorJars(zip, method, errors);
            writeIssues(zip, "ERROR.txt", "important problems", errors);
            writeIssues(zip, "WARNING.txt", "minor problems", warnings);
        }
        String warning = summarizeIssues(errors, warnings);
        log.info("Built bundle for integration method {}/{}: {} bytes ({} error(s), {} warning(s))",
                methodId, revision, baos.size(), errors.size(), warnings.size());
        return new Bundle(buildFileName(method), baos.toByteArray(), warning);
    }

    /** Builds the download file name as "application name (integration method name).zip". */
    private String buildFileName(IntegrationMethod method) {
        String appName = method.getApplication() == null ? null : method.getApplication().getDisplayName();
        String methodName = method.getDisplayName();
        String safeApp = sanitiseNamePart(appName);
        String safeMethod = sanitiseNamePart(methodName);
        if (safeMethod.isEmpty()) {
            safeMethod = method.getId().toString();
        }
        return safeApp.isEmpty()
                ? safeMethod + ".zip"
                : safeApp + " (" + safeMethod + ").zip";
    }

    /**
     * Keeps a name part to characters every filesystem accepts, and that are safe to place inside the
     * quoted Content-Disposition filename: letters, digits, spaces, brackets and . _ - are kept, any
     * other run (quotes and line breaks included) collapses to a single underscore.
     */
    private String sanitiseNamePart(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim()
                .replaceAll("[^a-zA-Z0-9 ._()\\[\\]-]+", "_")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private void addTutorial(ZipOutputStream zip, IntegrationMethod method, List<String> warnings) throws IOException {
        String tutorial = method.getTutorial();
        if (tutorial == null || tutorial.isBlank()) {
            log.debug("No tutorial text for {}/{}; skipping tutorial.md", method.getId(), method.getRevision());
            warnings.add("No tutorial text: tutorial.md was not included.");
            return;
        }
        // The tutorial is authored as Markdown and ships that way, unconverted.
        zip.putNextEntry(new ZipEntry("tutorial.md"));
        zip.write(tutorial.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void addTutorialFiles(ZipOutputStream zip, UUID methodId, String revision, List<String> warnings) throws IOException {
        List<String> files = tutorialStorageService.listTutorialFiles(methodId, revision);
        if (files.isEmpty()) {
            warnings.add("No additional tutorial/sample files were included (the files/ folder is empty).");
            return;
        }
        for (String name : files) {
            Path file = tutorialStorageService.resolveTutorialFile(methodId, revision, name);
            zip.putNextEntry(new ZipEntry("files/" + name));
            Files.copy(file, zip);
            zip.closeEntry();
        }
    }

    /**
     * Adds a build JAR for every connector linked to the method, each resolved from that connector's
     * latest bundle version artifact URL. Never throws on a missing or unreachable artifact: the JAR is
     * an important part of the bundle, so any connector without one is recorded as an error (surfaced in
     * ERROR.txt). Duplicate JAR file names are de-duplicated so the ZIP never has clashing entries.
     */
    private void addConnectorJars(ZipOutputStream zip, IntegrationMethod method, List<String> errors) throws IOException {
        List<IntegrationMethodConnector> links = method.getConnectors();
        if (links == null || links.isEmpty()) {
            errors.add("No connector is linked to this integration method, so no build file (.jar) is included.");
            return;
        }

        Set<String> usedEntryNames = new HashSet<>();

        for (IntegrationMethodConnector link : links) {
            Connector connector = link.getConnector();
            if (connector == null) {
                continue;
            }
            String label = connectorLabel(connector);
            String artifactUrl = resolveArtifactUrl(connector);
            if (artifactUrl == null || artifactUrl.isBlank()) {
                errors.add("Missing build file (.jar) for connector " + label + ": no build file available.");
                continue;
            }
            try {
                byte[] jar = artifactBytes(artifactUrl);
                String entryName = "connectors/" + uniqueEntryName(artifactEntryName(artifactUrl), usedEntryNames);
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(jar);
                zip.closeEntry();
            } catch (IOException e) {
                log.warn("Failed to fetch connector artifact {} for {}/{}: {}",
                        artifactUrl, method.getId(), method.getRevision(), e.getMessage());
                errors.add("Missing build file (.jar) for connector " + label
                        + ": could not be retrieved (" + e.getMessage() + ").");
            }
        }
    }

    /**
     * Writes a severity file (ERROR.txt / WARNING.txt) listing the collected issues, or nothing when
     * there are none. {@code kind} is a short phrase used in the file's header line.
     */
    private void writeIssues(ZipOutputStream zip, String entryName, String kind, List<String> issues) throws IOException {
        if (issues.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("The following ").append(kind).append(" were found while building this bundle:\n\n");
        for (int i = 0; i < issues.size(); i++) {
            sb.append(i + 1).append(". ").append(issues.get(i)).append('\n');
        }
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Short user-facing download warning summarising the collected issues (null when there are none). */
    private String summarizeIssues(List<String> errors, List<String> warnings) {
        if (errors.isEmpty() && warnings.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("This bundle has ");
        if (!errors.isEmpty()) {
            sb.append(errors.size()).append(errors.size() == 1 ? " error" : " errors").append(" (see ERROR.txt)");
        }
        if (!warnings.isEmpty()) {
            if (!errors.isEmpty()) {
                sb.append(" and ");
            }
            sb.append(warnings.size()).append(warnings.size() == 1 ? " warning" : " warnings").append(" (see WARNING.txt)");
        }
        sb.append('.');
        return sb.toString();
    }

    private String connectorLabel(Connector connector) {
        String name = connector.getDisplayName();
        return (name != null && !name.isBlank()) ? name : "connector " + connector.getId();
    }

    /**
     * Resolves the build-artifact URL for a single connector, using its latest connector bundle
     * version (most recently updated). Returns {@code null} if no bundle version carries an artifact URL.
     */
    private String resolveArtifactUrl(Connector connector) {
        return connector.getConnectorVersions().stream()
                .map(ConnectorVersion::getConnectorBundleVersion)
                .filter(cbv -> cbv != null && cbv.getArtifactUrl() != null && !cbv.getArtifactUrl().isBlank())
                .max(Comparator.comparing(ConnectorBundleVersion::getUpdated,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ConnectorBundleVersion::getArtifactUrl)
                .orElse(null);
    }

    /** Returns {@code name} if unused, otherwise appends {@code -2}, {@code -3}, … before the extension. */
    private String uniqueEntryName(String name, Set<String> used) {
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int counter = 2;
        String candidate;
        do {
            candidate = base + "-" + counter + ext;
            counter++;
        } while (!used.add(candidate));
        return candidate;
    }

    /** Derives a ZIP entry name from the artifact URL's last path segment. */
    private String artifactEntryName(String artifactUrl) {
        String path = URI.create(artifactUrl).getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? "connector.jar" : name;
    }

    private byte[] artifactBytes(String artifactUrl) throws IOException {
        byte[] cached = artifactCache.get(artifactUrl);
        if (cached != null) {
            return cached;
        }
        byte[] fetched = downloadArtifact(artifactUrl);
        artifactCache.put(artifactUrl, fetched);
        return fetched;
    }

    private byte[] downloadArtifact(String artifactUrl) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(artifactUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            log.info("Fetched connector artifact ({} bytes) from {}", response.body().length, artifactUrl);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading connector artifact", e);
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
        meta.put("maintainer", ownershipService.maintainerLabel(method));
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
            // Resource-wide capabilities are exported with the connector, not with the method.
            if (GLOBAL_OBJECT_CLASS.equalsIgnoreCase(cap.getObjectClass())) {
                continue;
            }
            Map<String, Object> capMeta = new LinkedHashMap<>();
            capMeta.put("objectClass", cap.getObjectClass());
            List<Map<String, Object>> states = new ArrayList<>();
            cap.getItems().stream()
                    .filter(item -> item.getCapability() != null)
                    .sorted(Comparator.comparing(item -> item.getCapability().getDisplayOrder(),
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(item -> {
                        Map<String, Object> state = new LinkedHashMap<>();
                        state.put("name", item.getCapability().getName());
                        state.put("state", item.getState());
                        states.add(state);
                    });
            capMeta.put("capabilities", states);
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
            meta.put("maintainer", ownershipService.maintainerLabel(connector));
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
