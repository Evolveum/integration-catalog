/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.CatalogProperties;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.Capability;
import com.evolveum.midpoint.integration.catalog.object.CatalogUser;
import com.evolveum.midpoint.integration.catalog.object.ConnVersionCapability;
import com.evolveum.midpoint.integration.catalog.object.Connector;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapability;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodConnector;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodType;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import com.evolveum.midpoint.integration.catalog.object.MidpointVersion;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.repository.CatalogUserRepository;
import com.evolveum.midpoint.integration.catalog.repository.MidpointVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Renders a submitted revision as the markdown body of its support work package. This is the whole
 * of what a reviewer sees before opening the catalog, so it carries the submission's own data plus
 * everything about the connectors that ride along with it.
 *
 * <p>Must be called inside the transaction that loaded the {@link IntegrationMethod}: it walks lazy
 * associations (capabilities, connectors, bundle versions) as it goes.
 *
 * <p>Every section degrades to a "not provided" note rather than disappearing, so a reviewer can
 * tell an empty field from a field this builder forgot.
 *
 * <p>People are named in the body with their contact address rather than attached to the work package
 * as a watcher or an assignee: the portal addresses people by their own user id, which the catalog
 * cannot resolve from a username. The addresses come from {@code catalog_users.email} and
 * {@code organizations.email} via {@link #withEmail(String)}; a name with neither is written on its
 * own, as every name was before those columns existed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportTicketDescriptionBuilder {

    /** Readable to a human, unlike {@link LocalDateTime#toString()} with its microseconds. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Object class under which resource-wide capabilities are stored, as opposed to the ones that
     * belong to a real object class. Mirrors what the detail page does when it splits the two.
     */
    private static final String GLOBAL_OBJECT_CLASS = "Global";

    private static final String NOT_PROVIDED = "_not provided_";

    private final MidpointVersionRepository midpointVersionRepository;
    private final CatalogUserRepository catalogUserRepository;
    private final OrganizationRepository organizationRepository;
    private final TutorialStorageService tutorialStorageService;
    private final CatalogProperties catalogProperties;

    /** The submission as markdown, ready to be posted as the work package description. */
    public String build(IntegrationMethod method) {
        StringBuilder body = new StringBuilder();
        body.append("An integration method has been submitted for review in the Integration Catalog.\n");

        appendSummary(body, method);
        appendDescription(body, method);
        appendCapabilities(body, method);
        appendTutorial(body, method);
        appendConnectors(body, method);

        body.append("\nUse this work package to discuss the submission with the author.\n");
        return body.toString();
    }

    private void appendSummary(StringBuilder body, IntegrationMethod method) {
        Application application = method.getApplication();
        body.append("\n## Integration method\n\n");
        bullet(body, "Application", application != null ? application.getDisplayName() : null);
        bullet(body, "Integration method", method.getDisplayName());
        bullet(body, "Revision", method.getRevision());
        bullet(body, "Integration method type", integrationMethodTypes(method));
        bullet(body, "Supported midPoint version", midpointVersionRange(method));
        bullet(body, "Author", method.getAuthor() != null ? withEmail(method.getAuthor()) : "unknown");
        bullet(body, "Maintainer", withEmail(method.getMaintainer()));
        bullet(body, "Submitted", timestamp(method.getCreatedAt()));
        appendCatalogLink(body, method, application);
    }

    /**
     * Link to the revision's page in the catalog, so a reviewer reading the ticket can open the
     * submission itself instead of searching for it. Omitted entirely when
     * {@code catalog.public-url} is unset - a line saying the link is missing would help nobody.
     */
    private void appendCatalogLink(StringBuilder body, IntegrationMethod method, Application application) {
        String url = catalogProperties.integrationMethodUrl(
                application != null ? application.getId() : null, method.getId(), method.getRevision());
        if (url != null) {
            bullet(body, "Open in the catalog", url);
        }
    }

    /**
     * A name with its contact address in brackets, e.g. {@code u1 (u1@example.com)}, falling back to
     * the bare name when none is known.
     *
     * <p>The name is resolved as a person first and as an organization second, because
     * {@code integration_method.author} and {@code .maintainer} are free-text columns holding either
     * - an organization contributor publishes on behalf of their organization, so a maintainer is
     * often an organization name rather than a username.
     *
     * <p>A name matching neither is left as it is rather than reported: these columns are stamped on
     * the revision at write time and keep the value they had even after the user or the organization
     * is renamed or removed, so a miss is expected rather than a fault.
     */
    private String withEmail(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String email = catalogUserRepository.findByUsername(name)
                .map(CatalogUser::getEmail)
                .filter(value -> !value.isBlank())
                .or(() -> organizationRepository.findByNameIgnoreCase(name)
                        .map(Organization::getEmail)
                        .filter(value -> !value.isBlank()))
                .orElse(null);
        return email == null ? name : name + " (" + email + ")";
    }

    private String integrationMethodTypes(IntegrationMethod method) {
        return join(method.getIntegMethodTypes(), IntegrationMethodType::getDisplayName);
    }

    /**
     * The supported midPoint range as "4.8 – 4.9", or "4.8 or newer" when no maximum is set - an
     * open-ended range is the normal case for a method that simply has not been capped yet.
     */
    private String midpointVersionRange(IntegrationMethod method) {
        String min = midpointVersion(method.getMidpointMinVersionId());
        String max = midpointVersion(method.getMidpointMaxVersionId());
        if (min == null && max == null) {
            return null;
        }
        if (min == null) {
            return "up to " + max;
        }
        return max == null ? min + " or newer" : min + " – " + max;
    }

    /** Resolves a {@code midpoint_version} id to its version string, e.g. {@code 4.9}. */
    private String midpointVersion(Integer versionId) {
        if (versionId == null) {
            return null;
        }
        return midpointVersionRepository.findById(versionId)
                .map(MidpointVersion::getVersion)
                .orElseGet(() -> {
                    log.warn("Integration method references unknown midPoint version id {}", versionId);
                    return "id " + versionId;
                });
    }

    private void appendDescription(StringBuilder body, IntegrationMethod method) {
        body.append("\n### Description\n\n")
                .append(blankToNotProvided(method.getDescription()))
                .append('\n');
    }

    /**
     * The method's capabilities, resource-wide ones first and the rest under the object class they
     * were declared for. Ordered by the display order the catalog itself uses, so the ticket lists
     * them the way the detail page does rather than in insertion order.
     */
    private void appendCapabilities(StringBuilder body, IntegrationMethod method) {
        body.append("\n### Capabilities\n\n");
        List<IntegrationMethodCapability> groups = method.getCapabilities() == null
                ? List.of()
                : method.getCapabilities();

        String global = groups.stream()
                .filter(group -> GLOBAL_OBJECT_CLASS.equalsIgnoreCase(group.getObjectClass()))
                .flatMap(this::capabilitiesOf)
                .sorted(byDisplayOrder())
                .map(Capability::getName)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
        bullet(body, "Global", global);

        List<IntegrationMethodCapability> specific = groups.stream()
                .filter(group -> !GLOBAL_OBJECT_CLASS.equalsIgnoreCase(group.getObjectClass()))
                .filter(group -> capabilitiesOf(group).findAny().isPresent())
                .sorted(Comparator.comparing(IntegrationMethodCapability::getObjectClass,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        if (specific.isEmpty()) {
            bullet(body, "Object class specific", null);
            return;
        }
        for (IntegrationMethodCapability group : specific) {
            String names = capabilitiesOf(group)
                    .sorted(byDisplayOrder())
                    .map(Capability::getName)
                    .distinct()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(null);
            bullet(body, "Object class `" + group.getObjectClass() + "`", names);
        }
    }

    private Stream<Capability> capabilitiesOf(IntegrationMethodCapability group) {
        return group.getItems() == null ? Stream.empty() : group.getItems().stream()
                .map(item -> item.getCapability())
                .filter(capability -> capability != null && capability.getName() != null);
    }

    private Stream<Capability> capabilitiesOf(ConnVersionCapability group) {
        return group.getItems() == null ? Stream.empty() : group.getItems().stream()
                .map(item -> item.getCapability())
                .filter(capability -> capability != null && capability.getName() != null);
    }

    private static Comparator<Capability> byDisplayOrder() {
        return Comparator
                .comparing(Capability::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Capability::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * The tutorial the author wrote, plus the names of the files uploaded alongside it. Names only:
     * the files stay in the catalog, where the reviewer downloads them from the method's page.
     */
    private void appendTutorial(StringBuilder body, IntegrationMethod method) {
        body.append("\n### Integration tutorial\n\n")
                .append(blankToNotProvided(method.getTutorial()))
                .append('\n');

        String files;
        try {
            files = join(tutorialStorageService.listTutorialFiles(method.getId(), method.getRevision()),
                    Function.identity());
        } catch (Exception e) {
            // A ticket without the file list beats no ticket at all.
            log.warn("Could not list tutorial files for {}/{}: {}",
                    method.getId(), method.getRevision(), e.getMessage());
            files = null;
        }
        body.append('\n');
        bullet(body, "Additional files", files);
    }

    /**
     * Every connector the method links, split by whether the reviewer has to look at it.
     *
     * <p>A connector already published in the catalog and reused untouched needs no review, so it is
     * named and nothing more. One introduced or edited with this submission gets published when the
     * method is approved, so everything known about it goes here. The two are told apart by the
     * lifecycle state the approve step itself keys on: {@code IN_REVIEW} anywhere in the connector,
     * its bundle or a bundle version means the approval will promote it.
     */
    private void appendConnectors(StringBuilder body, IntegrationMethod method) {
        body.append("\n## Connectors\n");
        List<IntegrationMethodConnector> links = method.getConnectors() == null
                ? List.of()
                : method.getConnectors().stream().filter(link -> link.getConnector() != null).toList();
        if (links.isEmpty()) {
            body.append('\n').append("_No connector is linked to this integration method._\n");
            return;
        }

        for (IntegrationMethodConnector link : links) {
            Connector connector = link.getConnector();
            if (needsPublishing(connector)) {
                appendConnectorForReview(body, link, connector);
            } else {
                appendPublishedConnector(body, link, connector);
            }
        }
    }

    /**
     * Whether approving the method would publish this connector, i.e. whether it was introduced or
     * edited with this submission. Deliberately the same condition
     * {@code ConnectorUploadService.promoteConnectorsToActive} promotes on, so the ticket cannot
     * disagree with what approval will actually do.
     */
    private boolean needsPublishing(Connector connector) {
        ConnectorBundle bundle = connector.getConnectorBundle();
        if (bundle != null) {
            if (bundle.getLifecycleState() == LifecycleType.IN_REVIEW) {
                return true;
            }
            if (bundle.getBundleVersions() != null && bundle.getBundleVersions().stream()
                    .anyMatch(version -> version.getLifecycleState() == LifecycleType.IN_REVIEW)) {
                return true;
            }
        }
        return connector.getConnectorVersions() != null && connector.getConnectorVersions().stream()
                .anyMatch(version -> version.getLifecycleState() == LifecycleType.IN_REVIEW);
    }

    private void appendPublishedConnector(StringBuilder body, IntegrationMethodConnector link, Connector connector) {
        body.append("\n### ").append(connectorLabel(connector)).append(" - already published\n\n");
        bullet(body, "Versions used", versionRange(link));
        body.append("\nPublished in the catalog already and reused unchanged, so there is nothing to review here.\n");
    }

    private void appendConnectorForReview(StringBuilder body, IntegrationMethodConnector link, Connector connector) {
        body.append("\n### ").append(connectorLabel(connector)).append(" - to be published with this method\n\n");
        bullet(body, "Versions used", versionRange(link));
        bullet(body, "Connector revision", connector.getRevision());
        bullet(body, "Fully qualified class name", connector.getFullyQualifiedClassName());
        bullet(body, "Author", withEmail(connector.getAuthor()));
        bullet(body, "Maintainer", withEmail(connector.getMaintainer()));
        bullet(body, "Created", timestamp(connector.getCreatedAt()));
        if (connector.getClonedFrom() != null) {
            bullet(body, "Edit of", "an already published connector (id " + connector.getClonedFrom() + ")");
        }
        bullet(body, "Description", singleLine(connector.getDescription()));

        appendBundle(body, connector.getConnectorBundle());
        appendConnectorVersions(body, connector);
    }

    private void appendBundle(StringBuilder body, ConnectorBundle bundle) {
        if (bundle == null) {
            return;
        }
        body.append("\n**Bundle**\n\n");
        bullet(body, "Bundle name", bundle.getBundleName());
        bullet(body, "Display name", bundle.getDisplayName());
        bullet(body, "Lifecycle state", name(bundle.getLifecycleState()));
        bullet(body, "Framework", name(bundle.getFramework()));
        bullet(body, "Build framework", name(bundle.getBuildFramework()));
        bullet(body, "License", name(bundle.getLicense()));
        bullet(body, "Git clone URL", bundle.getGitCloneUrl());
        bullet(body, "Path to project", bundle.getPathToProject());
        bullet(body, "Project homepage", bundle.getProjectHomepage());
        bullet(body, "Ticketing link", bundle.getTicketingLink());
        bullet(body, "Description", singleLine(bundle.getDescription()));
    }

    /**
     * The connector's versions that this submission would publish. Versions already active are left
     * out: they are not what the reviewer is being asked about, even on a connector that also
     * carries a new one.
     */
    private void appendConnectorVersions(StringBuilder body, Connector connector) {
        List<ConnectorVersion> versions = connector.getConnectorVersions() == null
                ? List.of()
                : connector.getConnectorVersions().stream()
                        .filter(version -> version.getLifecycleState() == LifecycleType.IN_REVIEW)
                        .sorted(Comparator.comparing(ConnectorVersion::getRevision,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                        .toList();
        for (ConnectorVersion version : versions) {
            body.append("\n**Version ").append(blankToDash(version.getRevision())).append("**\n\n");
            bullet(body, "Lifecycle state", name(version.getLifecycleState()));
            bullet(body, "Fully qualified class name", version.getFullyQualifiedClassName());
            bullet(body, "Author", withEmail(version.getAuthor()));
            bullet(body, "Maintainer", withEmail(version.getMaintainer()));
            bullet(body, "Created", timestamp(version.getCreatedAt()));
            appendBundleVersion(body, version.getConnectorBundleVersion());
            bullet(body, "Build error", singleLine(version.getErrorMessage()));
            appendVersionCapabilities(body, version);
        }
    }

    private void appendBundleVersion(StringBuilder body, ConnectorBundleVersion bundleVersion) {
        if (bundleVersion == null) {
            return;
        }
        bullet(body, "Bundle version", bundleVersion.getBundleVersion() != null
                ? bundleVersion.getBundleVersion() : bundleVersion.getRevision());
        bullet(body, "Bundle version lifecycle", name(bundleVersion.getLifecycleState()));
        bullet(body, "Build framework", name(bundleVersion.getBuildFramework()));
        bullet(body, "Commit hash", bundleVersion.getCommitTag());
        bullet(body, "Git clone URL", bundleVersion.getGitCloneUrl());
        bullet(body, "Path to project", bundleVersion.getPathToProject());
        bullet(body, "Browse link", bundleVersion.getBrowseLink());
        bullet(body, "Artifact URL", bundleVersion.getArtifactUrl());
        bullet(body, "Bundle build error", singleLine(bundleVersion.getErrorMessage()));
    }

    /** The version's capabilities, in the same global-then-per-object-class shape as the method's. */
    private void appendVersionCapabilities(StringBuilder body, ConnectorVersion version) {
        List<ConnVersionCapability> groups = version.getCapabilities() == null
                ? List.of()
                : version.getCapabilities();
        if (groups.isEmpty()) {
            bullet(body, "Capabilities", null);
            return;
        }
        List<String> lines = new ArrayList<>();
        String global = groups.stream()
                .filter(group -> GLOBAL_OBJECT_CLASS.equalsIgnoreCase(group.getObjectClass()))
                .flatMap(this::capabilitiesOf)
                .sorted(byDisplayOrder())
                .map(Capability::getName)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
        if (global != null) {
            lines.add("Global: " + global);
        }
        groups.stream()
                .filter(group -> !GLOBAL_OBJECT_CLASS.equalsIgnoreCase(group.getObjectClass()))
                .sorted(Comparator.comparing(ConnVersionCapability::getObjectClass,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .forEach(group -> capabilitiesOf(group)
                        .sorted(byDisplayOrder())
                        .map(Capability::getName)
                        .distinct()
                        .reduce((a, b) -> a + ", " + b)
                        .ifPresent(names -> lines.add("`" + group.getObjectClass() + "`: " + names)));
        bullet(body, "Capabilities", lines.isEmpty() ? null : String.join("; ", lines));
    }

    private String connectorLabel(Connector connector) {
        String label = connector.getDisplayName();
        if (label == null || label.isBlank()) {
            label = connector.getFullyQualifiedClassName();
        }
        return (label == null || label.isBlank()) ? "Connector id " + connector.getId() : label;
    }

    /** The version window the method declares for a connector, as "1.0 - 1.2" or just "1.0". */
    private String versionRange(IntegrationMethodConnector link) {
        String min = link.getConnectorMinVersion();
        String max = link.getConnectorMaxVersion();
        if (min == null || min.isBlank()) {
            return (max == null || max.isBlank()) ? null : "up to " + max;
        }
        return (max == null || max.isBlank() || max.equals(min)) ? min : min + " – " + max;
    }

    /** A markdown bullet, with an explicit note when there is nothing to show. */
    private static void bullet(StringBuilder body, String label, String value) {
        body.append("* **").append(label).append(":** ")
                .append(value == null || value.isBlank() ? NOT_PROVIDED : value)
                .append('\n');
    }

    private static String timestamp(LocalDateTime value) {
        return value == null ? null : TIMESTAMP.format(value);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** Keeps a multi-line value from breaking the bullet list it sits in. */
    private static String singleLine(String value) {
        return value == null ? null : value.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    private static String blankToNotProvided(String value) {
        return value == null || value.isBlank() ? NOT_PROVIDED : value.trim();
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static <T> String join(List<T> values, Function<T, String> toLabel) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String joined = values.stream()
                .map(toLabel)
                .filter(Objects::nonNull)
                .filter(label -> !label.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
        return joined == null || joined.isBlank() ? null : joined;
    }
}
