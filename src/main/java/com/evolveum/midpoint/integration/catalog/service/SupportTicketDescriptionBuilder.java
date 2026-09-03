/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.CatalogProperties;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.ApplicationApplicationTag;
import com.evolveum.midpoint.integration.catalog.object.ApplicationOrigin;
import com.evolveum.midpoint.integration.catalog.object.ApplicationTag;
import com.evolveum.midpoint.integration.catalog.object.Capability;
import com.evolveum.midpoint.integration.catalog.object.CountryOfOrigin;
import com.evolveum.midpoint.integration.catalog.object.ConnVersionCapability;
import com.evolveum.midpoint.integration.catalog.object.Connector;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapability;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodConnector;
import com.evolveum.midpoint.integration.catalog.object.OwnedItem;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodType;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import com.evolveum.midpoint.integration.catalog.object.MidpointVersion;
import com.evolveum.midpoint.integration.catalog.repository.MidpointVersionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Renders a submitted revision as the markdown body of its support work package - the whole of what a
 * reviewer sees before opening the catalog.
 *
 * <p>Must run inside the transaction that loaded the {@link IntegrationMethod}, as it walks lazy
 * associations. Every section degrades to a "not provided" note rather than disappearing, so an empty
 * field is not mistaken for a forgotten one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportTicketDescriptionBuilder {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Object class holding the resource-wide capabilities, as the detail page splits them. */
    private static final String GLOBAL_OBJECT_CLASS = "Global";

    private static final String NOT_PROVIDED = "_not provided_";

    private static final String NO_BUILD_ERROR = "_check comments if any_";

    private static final String NESTED = "    ";

    private static final String INTRO_NOTE =
            "An integration method has been submitted for review in the Integration Catalog.";
    private static final String CLOSING_NOTE =
            "Use this work package to discuss the submission with the author.";
    private static final String APPLICATION_PUBLISHED_NOTE =
            "Already published in the catalog, so there is nothing to review here.";
    private static final String CONNECTOR_PUBLISHED_NOTE =
            "Published in the catalog already and reused unchanged, so there is nothing to review here.";
    private static final String FILES_NOTE = "_Uploaded files are attached to this work package;"
            + " they also stay on the method's page in the catalog._";

    /**
     * The notes above, as they appear in a finished body. They explain the work package rather than
     * describe the submission, so {@link SupportTicketDeltaBuilder} leaves them out of what it reports.
     */
    static final List<String> NOTES = List.of(INTRO_NOTE, CLOSING_NOTE,
            APPLICATION_PUBLISHED_NOTE, CONNECTOR_PUBLISHED_NOTE, FILES_NOTE);

    private final MidpointVersionRepository midpointVersionRepository;
    private final CatalogContactResolver contactResolver;
    private final TutorialStorageService tutorialStorageService;
    private final CatalogProperties catalogProperties;
    private final OrganizationService organizationService;

    /** The submission as markdown, ready to be posted as the work package description. */
    public String build(IntegrationMethod method) {
        StringBuilder body = new StringBuilder();
        body.append(INTRO_NOTE).append('\n');

        appendApplication(body, method);
        appendSummary(body, method);
        appendCapabilities(body, method);
        appendTutorial(body, method);
        appendConnectors(body, method);

        body.append('\n').append(CLOSING_NOTE).append('\n');
        return body.toString();
    }

    /**
     * One connector as markdown, for a comment on a work package already open. Only the added
     * connector, rendered by the same methods the description uses, so it reads identically.
     */
    public String buildConnectorAddendum(IntegrationMethod method, Integer connectorId) {
        StringBuilder body = new StringBuilder();
        body.append("A connector has been added to this submission since this work package was opened.\n");

        IntegrationMethodConnector link = method.getConnectors() == null ? null
                : method.getConnectors().stream()
                        .filter(candidate -> candidate.getConnector() != null
                                && Objects.equals(candidate.getConnector().getId(), connectorId))
                        .findFirst()
                        .orElse(null);
        if (link == null) {
            body.append("\nIt is no longer linked to the revision, so there is nothing to describe.\n");
            return body.toString();
        }

        Connector connector = link.getConnector();
        if (needsPublishing(connector)) {
            appendConnectorForReview(body, link, connector);
        } else {
            appendPublishedConnector(body, link, connector);
        }
        body.append("\nEverything else about the submission is unchanged.\n");
        return body.toString();
    }

    /**
     * The application the method integrates, described in full only when approving this submission
     * would publish it too. Keyed on the same condition the approve step uses, so the two cannot disagree.
     */
    private void appendApplication(StringBuilder body, IntegrationMethod method) {
        Application application = method.getApplication();
        body.append("\n## Application\n\n");
        if (application == null) {
            body.append(NOT_PROVIDED).append('\n');
            return;
        }

        bullet(body, "App name", application.getDisplayName());
        if (application.getLifecycleState() == Application.ApplicationLifecycleType.ACTIVE) {
            body.append('\n').append(APPLICATION_PUBLISHED_NOTE).append('\n');
            return;
        }

        bullet(body, "App description", singleLine(application.getDescription()));
        bullet(body, "Origin", origins(application));
        bullet(body, "Category", tags(application, ApplicationTag.ApplicationTagType.CATEGORY));
        bullet(body, "Deployment type", tags(application, ApplicationTag.ApplicationTagType.DEPLOYMENT));
    }

    /** Countries the application is marked as originating from, as chosen on the publish form. */
    private String origins(Application application) {
        if (application.getApplicationOrigins() == null) {
            return null;
        }
        return application.getApplicationOrigins().stream()
                .map(ApplicationOrigin::getCountryOfOrigin)
                .filter(Objects::nonNull)
                .map(CountryOfOrigin::getDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }

    /**
     * The application's tags of one kind. Tags of every kind share a table and are told apart only by
     * {@code application_tag.tag_type}, so the category and the deployment type are the same query
     * with a different filter.
     */
    private String tags(Application application, ApplicationTag.ApplicationTagType type) {
        if (application.getApplicationApplicationTags() == null) {
            return null;
        }
        return application.getApplicationApplicationTags().stream()
                .map(ApplicationApplicationTag::getApplicationTag)
                .filter(tag -> tag != null && tag.getTagType() == type)
                .map(ApplicationTag::getDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }

    private void appendSummary(StringBuilder body, IntegrationMethod method) {
        body.append("\n## Integration method\n\n");
        bullet(body, "Integration method", method.getDisplayName());
        bullet(body, "Revision", method.getRevision());
        bullet(body, "Description", singleLine(method.getDescription()));
        bullet(body, "Integration method type", integrationMethodTypes(method));
        bullet(body, "Supported midPoint version", midpointVersionRange(method));
        bullet(body, "Author", method.getAuthor() != null ? withEmail(method, method.getAuthor()) : "unknown");
        bullet(body, "Maintainer", maintainer(method));
        bullet(body, "Submitted", timestamp(method.getCreatedAt()));
        appendCatalogLink(body, method, method.getApplication());
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
     * A name with its address and organization where those are known: {@code u1 (u1@acme.com), Acme co.}
     * Each part is optional, so a bare name is a normal result - see {@link CatalogContactResolver};
     * {@code item} is the row the name is stamped on, which is where both parts come from.
     */
    private String withEmail(OwnedItem item, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String labelled = contactResolver.emailOf(item, name)
                .map(email -> name + " (" + email + ")")
                .orElse(name);
        return contactResolver.organizationOf(item, name)
                .map(organization -> labelled + ", " + organization)
                .orElse(labelled);
    }

    /**
     * The maintainer as the ticket names them: the maintaining person, with whatever contact
     * details the catalog holds, or the maintaining organization when no person maintains the
     * item - an organization is named and nothing more, having no address to reach it at.
     *
     * <p>Needed because an organization maintainer is recorded as an organization reference with
     * no username at all (see {@code OwnershipService#assignMaintainer}), so reading the username
     * alone would report a maintained item as unmaintained.
     */
    private String maintainer(OwnedItem item) {
        String name = item.getMaintainer();
        return name != null && !name.isBlank()
                ? withEmail(item, name)
                : organizationService.displayName(item.getMaintainerOrgId());
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

    /**
     * The method's capabilities, resource-wide ones first and the rest under the object class they
     * were declared for. Ordered by the display order the catalog itself uses, so the ticket lists
     * them the way the detail page does rather than in insertion order.
     */
    private void appendCapabilities(StringBuilder body, IntegrationMethod method) {
        body.append("\n### Integration method capabilities\n\n");
        List<IntegrationMethodCapability> groups = method.getCapabilities() == null
                ? List.of()
                : method.getCapabilities();

        bullet(body, "Global", capabilityNames(groups.stream()
                .filter(group -> GLOBAL_OBJECT_CLASS.equalsIgnoreCase(group.getObjectClass()))
                .flatMap(this::capabilitiesOf)));

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
            bullet(body, "Object class `" + group.getObjectClass() + "`",
                    capabilityNames(capabilitiesOf(group)));
        }
    }

    /** One group's capabilities as a single comma-separated value, in the catalog's own order. */
    private String capabilityNames(Stream<Capability> capabilities) {
        return capabilities
                .sorted(byDisplayOrder())
                .map(SupportTicketDescriptionBuilder::capabilityLabel)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
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

    /**
     * A capability as a reader sees it. The catalog stores capability names as upper-case constants,
     * which is what the ticket used to repeat; sentence casing them is what the integration method
     * detail page does, so the two read alike.
     */
    private static String capabilityLabel(Capability capability) {
        return sentenceCase(capability.getName());
    }

    /**
     * An enum constant as a reader sees it: {@code JAVA_BASED} becomes "Java based". The stored
     * constants are an implementation detail of the catalog, not something a reviewer should have to
     * read, so they are cased the way the catalog UI cases them.
     */
    private static String enumLabel(Enum<?> value) {
        return value == null ? null : sentenceCase(value.name());
    }

    /**
     * Licenses are proper names rather than words, so casing rules do not help: they are spelled out
     * the way the publish form offers them.
     */
    private static String licenseLabel(ConnectorBundle.LicenseType license) {
        if (license == null) {
            return null;
        }
        return switch (license) {
            case MIT -> "MIT";
            case APACHE_2 -> "Apache 2.0";
            case BSD -> "BSD";
            case EUPL -> "EUPL 1.2";
        };
    }

    /** {@code PARTIAL_SCHEMA} to "Partial schema". */
    private static String sentenceCase(String constant) {
        String spaced = constant.replace('_', ' ').trim().toLowerCase();
        return spaced.isEmpty() ? constant : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static Comparator<Capability> byDisplayOrder() {
        return Comparator
                .comparing(Capability::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Capability::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * The tutorial and the names of the files uploaded alongside it. The tutorial is pointed at rather
     * than reproduced - it has no length limit and is attached instead. An empty file list points at
     * the Files tab, because on a first submission the uploads arrive after this is written.
     */
    private void appendTutorial(StringBuilder body, IntegrationMethod method) {
        body.append("\n### Integration tutorial\n\n");
        if (method.getTutorial() == null || method.getTutorial().isBlank()) {
            body.append(NOT_PROVIDED).append('\n');
        } else {
            body.append("Attached to this work package as `")
                    .append(SupportTicketService.TUTORIAL_ATTACHMENT)
                    .append("` - see the **Files** tab above.\n");
        }

        String files;
        try {
            files = join(tutorialStorageService.listTutorialFiles(method.getId(), method.getRevision()),
                    Function.identity());
        } catch (Exception e) {
            log.warn("Could not list tutorial files for {}/{}: {}",
                    method.getId(), method.getRevision(), e.getMessage());
            files = null;
        }
        body.append('\n');
        if (files == null) {
            bullet(body, "Additional tutorials/samples", "See the **Files** tab above");
        } else {
            bullet(body, "Additional tutorials/samples", files);
        }
        body.append('\n').append(FILES_NOTE).append('\n');
    }

    /**
     * Every connector the method links, split by whether approving this submission would publish it -
     * {@code IN_REVIEW} anywhere in the connector, its bundle or a bundle version. A reused published
     * connector is only named.
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
        bullet(body, "Description", singleLine(connector.getDescription()));
        bullet(body, "Connector versions (from - to)", versionRange(link));
        bullet(body, "Connector version", submittedVersion(connector));
        bullet(body, "Maintainer", maintainer(connector));
        body.append('\n').append(CONNECTOR_PUBLISHED_NOTE).append('\n');
    }

    private void appendConnectorForReview(StringBuilder body, IntegrationMethodConnector link, Connector connector) {
        body.append("\n### ").append(connectorLabel(connector)).append(" - to be published with this method\n\n");
        bullet(body, "Description", singleLine(connector.getDescription()));
        bullet(body, "Connector versions (from - to)", versionRange(link));
        bullet(body, "Connector version", submittedVersion(connector));
        bullet(body, "Author", withEmail(connector, connector.getAuthor()));
        bullet(body, "Maintainer", maintainer(connector));
        bullet(body, "Fully qualified class name", connector.getFullyQualifiedClassName());
        bullet(body, "Created", timestamp(connector.getCreatedAt()));
        if (connector.getClonedFrom() != null) {
            bullet(body, "Edit of", "an already published connector (id " + connector.getClonedFrom() + ")");
        }

        appendBundle(body, connector.getConnectorBundle());
        appendConnectorVersions(body, connector);
    }

    private void appendBundle(StringBuilder body, ConnectorBundle bundle) {
        if (bundle == null) {
            return;
        }
        body.append("\n**Bundle**\n\n");
        bullet(body, "Bundle name", bundle.getDisplayName());
        bullet(body, "Framework", enumLabel(bundle.getFramework()));
        bullet(body, "License", licenseLabel(bundle.getLicense()));
        bullet(body, "Git clone URL", bundle.getGitCloneUrl());
        bullet(body, "Project homepage", bundle.getProjectHomepage());
        bullet(body, "Support portal", bundle.getTicketingLink());
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
            body.append("\n## Connector version ").append(blankToDash(version.getRevision())).append("\n\n");
            bullet(body, "Author", withEmail(version, version.getAuthor()));
            bullet(body, "Maintainer", maintainer(version));
            bullet(body, "Created", timestamp(version.getCreatedAt()));
            appendBundleVersion(body, version.getConnectorBundleVersion());
            bullet(body, "Build error", blankTo(singleLine(version.getErrorMessage()), NO_BUILD_ERROR));
            appendVersionCapabilities(body, version);
        }
    }

    private void appendBundleVersion(StringBuilder body, ConnectorBundleVersion bundleVersion) {
        if (bundleVersion == null) {
            return;
        }
        bullet(body, "Bundle version", bundleVersion.getBundleVersion() != null
                ? bundleVersion.getBundleVersion() : bundleVersion.getRevision());
        bullet(body, "Commit hash", bundleVersion.getCommitTag());
        bullet(body, "Build framework", enumLabel(bundleVersion.getBuildFramework()));
        bullet(body, "Path to project", bundleVersion.getPathToProject());
    }

    /**
     * The version's capabilities, written exactly the way the method's are - resource-wide ones first,
     * then one line per object class - but nested under a single bullet so they stay part of the
     * version's own list instead of breaking out of it.
     */
    private void appendVersionCapabilities(StringBuilder body, ConnectorVersion version) {
        List<ConnVersionCapability> groups = version.getCapabilities() == null
                ? List.of()
                : version.getCapabilities();

        String global = capabilityNames(groups.stream()
                .filter(group -> GLOBAL_OBJECT_CLASS.equalsIgnoreCase(group.getObjectClass()))
                .flatMap(this::capabilitiesOf));
        List<ConnVersionCapability> specific = groups.stream()
                .filter(group -> !GLOBAL_OBJECT_CLASS.equalsIgnoreCase(group.getObjectClass()))
                .filter(group -> capabilitiesOf(group).findAny().isPresent())
                .sorted(Comparator.comparing(ConnVersionCapability::getObjectClass,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        if (global == null && specific.isEmpty()) {
            bullet(body, "Capabilities", null);
            return;
        }

        body.append("* **Capabilities:**\n");
        bullet(body, NESTED, "Global", global);
        if (specific.isEmpty()) {
            bullet(body, NESTED, "Object class specific", null);
            return;
        }
        for (ConnVersionCapability group : specific) {
            bullet(body, NESTED, "Object class `" + group.getObjectClass() + "`",
                    capabilityNames(capabilitiesOf(group)));
        }
    }

    private String connectorLabel(Connector connector) {
        String label = connector.getDisplayName();
        if (label == null || label.isBlank()) {
            label = connector.getFullyQualifiedClassName();
        }
        return (label == null || label.isBlank()) ? "Connector id " + connector.getId() : label;
    }

    /**
     * The version window the method declares for a connector, as "1.0 – 1.2", or "1.0 or newer" when
     * no upper bound was given - the same open-ended wording {@link #midpointVersionRange} uses, since
     * an uncapped range means the same thing in both places.
     */
    private String versionRange(IntegrationMethodConnector link) {
        String min = link.getConnectorMinVersion();
        String max = link.getConnectorMaxVersion();
        if (min == null || min.isBlank()) {
            return (max == null || max.isBlank()) ? null : "up to " + max;
        }
        if (max == null || max.isBlank()) {
            return min + " or newer";
        }
        return max.equals(min) ? min : min + " – " + max;
    }

    /**
     * The version the author typed in the publish form, which lives on the bundle version;
     * {@code connector.revision} is seeded to {@code 1.0.0} and only serves as a fallback. The version
     * under review wins over the connector's earlier ones.
     */
    private String submittedVersion(Connector connector) {
        List<ConnectorVersion> versions = connector.getConnectorVersions() == null
                ? List.of()
                : connector.getConnectorVersions();
        String submitted = bundleRevision(versions.stream()
                .filter(version -> version.getLifecycleState() == LifecycleType.IN_REVIEW));
        if (submitted == null) {
            submitted = bundleRevision(versions.stream());
        }
        return submitted != null ? submitted : connector.getRevision();
    }

    private String bundleRevision(Stream<ConnectorVersion> versions) {
        return versions
                .map(ConnectorVersion::getConnectorBundleVersion)
                .filter(Objects::nonNull)
                .map(ConnectorBundleVersion::getRevision)
                .filter(revision -> revision != null && !revision.isBlank())
                .max(String.CASE_INSENSITIVE_ORDER)
                .orElse(null);
    }

    /** A markdown bullet, with an explicit note when there is nothing to show. */
    private static void bullet(StringBuilder body, String label, String value) {
        bullet(body, "", label, value);
    }

    /** The same bullet, indented to sit under the one above it. */
    private static void bullet(StringBuilder body, String indent, String label, String value) {
        body.append(indent).append("* **").append(label).append(":** ")
                .append(value == null || value.isBlank() ? NOT_PROVIDED : value)
                .append('\n');
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String timestamp(LocalDateTime value) {
        return value == null ? null : TIMESTAMP.format(value);
    }

    /** Keeps a multi-line value from breaking the bullet list it sits in. */
    private static String singleLine(String value) {
        return value == null ? null : value.replaceAll("\\s*\\R\\s*", " ").trim();
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
