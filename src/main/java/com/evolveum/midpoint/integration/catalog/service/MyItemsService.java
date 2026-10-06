/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.dto.MyConnectorDto;
import com.evolveum.midpoint.integration.catalog.dto.MyIntegrationMethodDto;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorRepository;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodConnectorRepository;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.repository.MaintainerRepository;
import com.evolveum.midpoint.integration.catalog.repository.OrganizationRepository;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The items a user maintains, for the My integration methods and My connectors pages. Mirrors the
 * maintainer half of {@link AuthService#canEdit}: the user's own rows plus, for an organization
 * contributor, the organization's. Community items are left out, as they would match everyone.
 */
@Service
public class MyItemsService {

    private static final Comparator<String> NEWEST_REVISION_FIRST =
            ((Comparator<String>) MyItemsService::compareRevisions).reversed();
    /** By application, then method, newest revision first, so a method's revisions stay together. */
    private static final Comparator<IntegrationMethod> BY_METHOD = Comparator
            .comparing((IntegrationMethod m) -> nullToEmpty(m.getApplication().getDisplayName()),
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(m -> nullToEmpty(m.getDisplayName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(IntegrationMethod::getId)
            .thenComparing(IntegrationMethod::getRevision, NEWEST_REVISION_FIRST);

    private final MaintainerRepository maintainerRepository;
    private final OrganizationRepository organizationRepository;
    private final IntegrationMethodRepository integrationMethodRepository;
    private final ConnectorRepository connectorRepository;
    private final IntegrationMethodConnectorRepository integrationMethodConnectorRepository;
    private final OwnershipService ownershipService;
    private final OpenProjectProperties openProjectProperties;
    private final CatalogClaims claims;

    public MyItemsService(MaintainerRepository maintainerRepository,
                          OrganizationRepository organizationRepository,
                          IntegrationMethodRepository integrationMethodRepository,
                          ConnectorRepository connectorRepository,
                          IntegrationMethodConnectorRepository integrationMethodConnectorRepository,
                          OwnershipService ownershipService,
                          OpenProjectProperties openProjectProperties,
                          CatalogClaims claims) {
        this.maintainerRepository = maintainerRepository;
        this.organizationRepository = organizationRepository;
        this.integrationMethodRepository = integrationMethodRepository;
        this.connectorRepository = connectorRepository;
        this.integrationMethodConnectorRepository = integrationMethodConnectorRepository;
        this.ownershipService = ownershipService;
        this.openProjectProperties = openProjectProperties;
        this.claims = claims;
    }

    /** Every revision, grouped by method and newest first within one. */
    @Transactional(readOnly = true)
    public List<MyIntegrationMethodDto> myIntegrationMethods(String username, OidcUser oidcUser) {
        List<Maintainer> maintainers = ownMaintainers(username, oidcUser);
        if (maintainers.isEmpty()) {
            return List.of();
        }
        return integrationMethodRepository.findByMaintainerIn(maintainers).stream()
                .sorted(BY_METHOD)
                .map(this::toDto)
                .toList();
    }

    /** Every revision of every method in the catalog, for a superuser, who may see every ticket. */
    @Transactional(readOnly = true)
    public List<MyIntegrationMethodDto> allIntegrationMethods() {
        return integrationMethodRepository.findAll().stream()
                .sorted(BY_METHOD)
                .map(this::toDto)
                .toList();
    }

    /**
     * Every revision waiting for a superuser across the catalog, longest waiting first. The
     * caller is a superuser, which is what lets them see every ticket.
     */
    @Transactional(readOnly = true)
    public List<MyIntegrationMethodDto> reviewQueue() {
        return integrationMethodRepository
                .findByLifecycleStateIn(List.of(LifecycleType.IN_REVIEW, LifecycleType.REVIEWING)).stream()
                .sorted(Comparator.comparing(IntegrationMethod::getUpdated, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyConnectorDto> myConnectors(String username, OidcUser oidcUser) {
        List<Maintainer> maintainers = ownMaintainers(username, oidcUser);
        if (maintainers.isEmpty()) {
            return List.of();
        }
        Set<UUID> ownMethodIds = integrationMethodRepository.findByMaintainerIn(maintainers).stream()
                .map(IntegrationMethod::getId)
                .collect(Collectors.toSet());

        return connectorDtos(connectorRepository.findByMaintainerInAndClonedFromIsNull(maintainers), ownMethodIds::contains);
    }

    /** Every connector in the catalog, for a superuser, who may see every method using it, drafts included. */
    @Transactional(readOnly = true)
    public List<MyConnectorDto> allConnectors() {
        return connectorDtos(connectorRepository.findByClonedFromIsNull(), methodId -> true);
    }

    private List<MyConnectorDto> connectorDtos(List<Connector> found, Predicate<UUID> seesDrafts) {
        List<Connector> connectors = found.stream()
                .sorted(Comparator.comparing((Connector c) -> nullToEmpty(displayName(c)), String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<Integer, List<IntegrationMethodConnector>> linksByConnector = connectors.isEmpty()
                ? Map.of()
                : integrationMethodConnectorRepository.findByConnectorIn(connectors).stream()
                        .collect(Collectors.groupingBy(link -> link.getConnector().getId()));

        return connectors.stream()
                .map(c -> toDto(c, linksByConnector.getOrDefault(c.getId(), List.of()), seesDrafts))
                .toList();
    }

    /** The maintainer rows standing for the caller; none when the caller has never been made one. */
    private List<Maintainer> ownMaintainers(String username, OidcUser oidcUser) {
        List<Maintainer> maintainers = new ArrayList<>();
        maintainerRepository.findByCategoryAndUsernameIgnoreCase(MaintainerType.USER, username)
                .ifPresent(maintainers::add);
        if (oidcUser != null && claims.effectiveRole(oidcUser) == CatalogRole.ORGANIZATION_CONTRIBUTOR) {
            String organizationName = claims.organizationName(oidcUser);
            if (organizationName != null) {
                organizationRepository.findByName(organizationName)
                        .flatMap(org -> maintainerRepository.findByCategoryAndOrganization(MaintainerType.ORG, org))
                        .ifPresent(maintainers::add);
            }
        }
        return maintainers;
    }

    private MyIntegrationMethodDto toDto(IntegrationMethod method) {
        Application application = method.getApplication();
        // The caller maintains every listed method, which is what lets them see its ticket.
        Integer ticketId = openProjectProperties.isEnabled() ? method.getSupportTicketId() : null;
        String connectorDisplayName = method.getConnectors().stream()
                .map(IntegrationMethodConnector::getConnector)
                .filter(Objects::nonNull)
                .map(MyItemsService::displayName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new MyIntegrationMethodDto(
                application.getId(),
                application.getDisplayName(),
                application.getLogoPath() != null,
                method.getId(),
                method.getRevision(),
                method.getDisplayName(),
                connectorDisplayName,
                method.getLifecycleState() != null ? method.getLifecycleState().name() : null,
                toDate(method.getCreatedAt()),
                toDate(method.getUpdated()),
                method.getUpdated(),
                method.getAuthor() != null ? method.getAuthor().getUsername() : null,
                ticketId,
                ticketId != null ? openProjectProperties.workPackageUrl(ticketId) : null,
                ownershipService.toDto(method.getMaintainer()));
    }

    private static List<String> tagNames(Connector connector) {
        if (connector.getConnectorConnectorTags() == null) {
            return List.of();
        }
        return connector.getConnectorConnectorTags().stream()
                .map(ConnectorConnectorTag::getConnectorTag)
                .filter(Objects::nonNull)
                .map(ConnectorTag::getDisplayName)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * @param links every method revision linking the connector
     * @param seesDrafts whether the caller may see the not yet published revisions of a method
     */
    private MyConnectorDto toDto(Connector connector, List<IntegrationMethodConnector> links, Predicate<UUID> seesDrafts) {
        List<ConnectorVersion> versions = connector.getConnectorVersions().stream()
                .sorted(Comparator.comparing(ConnectorVersion::getId).reversed())
                .toList();

        // A link names no version row, only the connector: the version it was added with is its
        // min version, and one matching none of the rows is taken to use the newest.
        Map<ConnectorVersion, List<MyConnectorDto.Usage>> usages = new IdentityHashMap<>();
        for (IntegrationMethodConnector link : links) {
            IntegrationMethod method = link.getIntegrationMethod();
            if (versions.isEmpty()
                    || (method.getLifecycleState() != LifecycleType.ACTIVE && !seesDrafts.test(method.getId()))) {
                continue;
            }
            ConnectorVersion used = versions.stream()
                    .filter(v -> Objects.equals(versionOf(v), link.getConnectorMinVersion()))
                    .findFirst()
                    .orElse(versions.get(0));
            usages.computeIfAbsent(used, v -> new ArrayList<>()).add(new MyConnectorDto.Usage(
                    method.getApplication().getId(),
                    method.getApplication().getDisplayName(),
                    method.getId(),
                    method.getRevision(),
                    method.getDisplayName()));
        }

        return new MyConnectorDto(
                connector.getId(),
                displayName(connector),
                ownershipService.toDto(connector.getMaintainer()),
                tagNames(connector),
                versions.stream()
                        .map(v -> new MyConnectorDto.Version(
                                versionOf(v),
                                v.getAuthor() != null ? v.getAuthor().getUsername() : null,
                                toDate(v.getCreatedAt()),
                                v.getLifecycleState() != null ? v.getLifecycleState().name() : null,
                                v.getConnectorBundleVersion() != null ? v.getConnectorBundleVersion().getId() : null,
                                v.getConnectorBundleVersion() != null ? v.getConnectorBundleVersion().getSupportTier() : null,
                                usages.getOrDefault(v, List.of()).stream()
                                        .sorted(Comparator
                                                .comparing((MyConnectorDto.Usage u) -> nullToEmpty(u.displayName()),
                                                        String.CASE_INSENSITIVE_ORDER)
                                                .thenComparing(MyConnectorDto.Usage::revision, NEWEST_REVISION_FIRST))
                                        .toList()))
                        .toList());
    }

    /** The "Connector version" given when it was added, which lands on the bundle version. */
    private static String versionOf(ConnectorVersion version) {
        ConnectorBundleVersion bundleVersion = version.getConnectorBundleVersion();
        if (bundleVersion != null && bundleVersion.getBundleVersion() != null) {
            return bundleVersion.getBundleVersion();
        }
        return version.getRevision();
    }

    private static String displayName(Connector connector) {
        if (connector.getDisplayName() != null && !connector.getDisplayName().isBlank()) {
            return connector.getDisplayName();
        }
        ConnectorBundle bundle = connector.getConnectorBundle();
        return bundle != null ? bundle.getDisplayName() : null;
    }

    /** Orders "major.minor" revisions numerically, so 1.10 comes after 1.9. */
    private static int compareRevisions(String a, String b) {
        String[] left = nullToEmpty(a).split("\\.");
        String[] right = nullToEmpty(b).split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? parseOrZero(left[i]) : 0;
            int r = i < right.length ? parseOrZero(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int parseOrZero(String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static LocalDate toDate(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.toLocalDate() : null;
    }
}
