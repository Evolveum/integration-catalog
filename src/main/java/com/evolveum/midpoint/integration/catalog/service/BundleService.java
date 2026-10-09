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
import com.evolveum.midpoint.integration.catalog.object.ConnectorTag;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapability;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodConnector;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodType;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.util.ConnectorVersions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    private final IntegrationMethodRepository integrationMethodRepository;
    private final TutorialStorageService tutorialStorageService;
    private final OwnershipService ownershipService;
    private final ObjectWriter jsonWriter;
    private final HttpClient httpClient;
    private final Duration readTimeout;
    /** Cache of fetched artifact bytes, keyed by artifact URL — release URLs are immutable. */
    private final Map<String, byte[]> artifactCache = new ConcurrentHashMap<>();

    public BundleService(IntegrationMethodRepository integrationMethodRepository,
                         TutorialStorageService tutorialStorageService,
                         OwnershipService ownershipService,
                         ObjectMapper objectMapper,
                         @Value("${catalog.bundle-download.connect-timeout}") Duration connectTimeout,
                         @Value("${catalog.bundle-download.read-timeout}") Duration readTimeout) {
        this.integrationMethodRepository = integrationMethodRepository;
        this.tutorialStorageService = tutorialStorageService;
        this.ownershipService = ownershipService;
        this.jsonWriter = objectMapper.writerWithDefaultPrettyPrinter();
        this.readTimeout = readTimeout;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // Nexus may redirect to a storage host
                .connectTimeout(connectTimeout)
                .build();
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
        // gaps (a missing connector build JAR), WARNING.txt collects minor ones (no tutorial text, or a
        // tutorial/sample file that could not be included). Both are advisory — the ZIP is still produced.
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            addTutorial(zip, method, warnings);
            addTutorialFiles(zip, methodId, revision, warnings);
            addMetadata(zip, method);
            addConnectorJars(zip, method, errors, warnings);
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

    /**
     * Adds the method's additional tutorial/sample files. Having none is normal and not reported; only a
     * file that exists but cannot be included is a warning, and the rest of the bundle is built anyway.
     */
    private void addTutorialFiles(ZipOutputStream zip, UUID methodId, String revision, List<String> warnings) throws IOException {
        List<String> files;
        try {
            files = tutorialStorageService.listTutorialFiles(methodId, revision);
        } catch (UncheckedIOException e) {
            log.warn("Failed to list tutorial files for {}/{}: {}", methodId, revision, e.getMessage());
            warnings.add("The additional tutorial/sample files could not be read, so none were included.");
            return;
        }
        for (String name : files) {
            byte[] content;
            try {
                // Read before opening the entry, so a failure never leaves a half-written file in the ZIP.
                content = Files.readAllBytes(tutorialStorageService.resolveTutorialFile(methodId, revision, name));
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to include tutorial file {} for {}/{}: {}", name, methodId, revision, e.getMessage());
                warnings.add("Additional tutorial/sample file " + name + " could not be included.");
                continue;
            }
            zip.putNextEntry(new ZipEntry("files/" + name));
            zip.write(content);
            zip.closeEntry();
        }
    }

    /**
     * Adds a build JAR for every connector linked to the method, each resolved from that connector's
     * latest bundle version artifact URL. Never throws on a missing or unreachable artifact: the JAR is
     * an important part of the bundle, so any connector without one is recorded as an error (surfaced in
     * ERROR.txt). Duplicate JAR file names are de-duplicated so the ZIP never has clashing entries.
     * A connector published without an artifact URL has no JAR by design: its description takes the
     * JAR's place and WARNING.txt points to it.
     */
    private void addConnectorJars(ZipOutputStream zip, IntegrationMethod method, List<String> errors,
                                  List<String> warnings) throws IOException {
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
            if (connector.hasTag(ConnectorTag.ARTIFACT_URLLESS)) {
                addArtifactUrllessDescription(zip, method, connector, label, warnings);
                continue;
            }
            String artifactUrl = resolveArtifactUrl(link);
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
     * Ships the description of a connector published without an artifact URL under the same name the
     * support ticket attaches it with, since that is where its user learns how to get the connector.
     */
    private void addArtifactUrllessDescription(ZipOutputStream zip, IntegrationMethod method, Connector connector,
                                               String label, List<String> warnings) throws IOException {
        String fileName = SupportTicketAttachments.connectorDescriptionAttachment(method, connector);
        if (fileName == null) {
            warnings.add("Connector " + label + " was published without an artifact URL, so no build file (.jar)"
                    + " is included, and it has no description either: check its page in the catalog.");
            return;
        }
        String entryName = "connectors/" + fileName;
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(connector.getDescription().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        warnings.add("Connector " + label + " was published without an artifact URL, so no build file (.jar)"
                + " is included. See details in " + entryName + ".");
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
     * The build-artifact URL of the connector version the method uses (see {@link ConnectorVersions}),
     * or {@code null} when that version carries none.
     */
    private String resolveArtifactUrl(IntegrationMethodConnector link) {
        return ConnectorVersions.used(link)
                .map(ConnectorVersion::getConnectorBundleVersion)
                .map(ConnectorBundleVersion::getArtifactUrl)
                .filter(url -> !url.isBlank())
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
                .timeout(readTimeout)
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
        meta.put("appMinVersion", method.getAppMinVersion());
        meta.put("appMaxVersion", method.getAppMaxVersion());
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
            meta.put("revision", ConnectorVersions.used(link).map(ConnectorVersions::versionOf)
                    .orElse(connector.getRevision()));
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
