/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.MyItemsDto;
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
import java.util.stream.Collectors;

/**
 * The items a user maintains, for the My profile page. Mirrors the maintainer half of
 * {@link AuthService#canEdit}: the user's own rows plus, for an organization contributor, the
 * organization's. Community items are left out, as they would match everyone.
 */
@Service
public class MyItemsService {

    private static final Comparator<String> NEWEST_REVISION_FIRST =
            ((Comparator<String>) MyItemsService::compareRevisions).reversed();

    private final MaintainerRepository maintainerRepository;
    private final OrganizationRepository organizationRepository;
    private final IntegrationMethodRepository integrationMethodRepository;
    private final ConnectorRepository connectorRepository;
    private final IntegrationMethodConnectorRepository integrationMethodConnectorRepository;
    private final CatalogClaims claims;

    public MyItemsService(MaintainerRepository maintainerRepository,
                          OrganizationRepository organizationRepository,
                          IntegrationMethodRepository integrationMethodRepository,
                          ConnectorRepository connectorRepository,
                          IntegrationMethodConnectorRepository integrationMethodConnectorRepository,
                          CatalogClaims claims) {
        this.maintainerRepository = maintainerRepository;
        this.organizationRepository = organizationRepository;
        this.integrationMethodRepository = integrationMethodRepository;
        this.connectorRepository = connectorRepository;
        this.integrationMethodConnectorRepository = integrationMethodConnectorRepository;
        this.claims = claims;
    }

    @Transactional(readOnly = true)
    public MyItemsDto myItems(String username, OidcUser oidcUser) {
        List<Maintainer> maintainers = ownMaintainers(username, oidcUser);
        if (maintainers.isEmpty()) {
            return new MyItemsDto(List.of(), List.of());
        }

        List<IntegrationMethod> methods = integrationMethodRepository.findByMaintainerIn(maintainers).stream()
                .sorted(Comparator
                        .comparing((IntegrationMethod m) -> nullToEmpty(m.getApplication().getDisplayName()),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(m -> nullToEmpty(m.getDisplayName()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(IntegrationMethod::getId)
                        .thenComparing(IntegrationMethod::getRevision, NEWEST_REVISION_FIRST))
                .toList();
        Set<UUID> ownMethodIds = methods.stream().map(IntegrationMethod::getId).collect(Collectors.toSet());

        List<Connector> connectors = connectorRepository.findByMaintainerInAndClonedFromIsNull(maintainers).stream()
                .sorted(Comparator.comparing((Connector c) -> nullToEmpty(displayName(c)), String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<Integer, List<IntegrationMethodConnector>> linksByConnector = connectors.isEmpty()
                ? Map.of()
                : integrationMethodConnectorRepository.findByConnectorIn(connectors).stream()
                        .collect(Collectors.groupingBy(link -> link.getConnector().getId()));

        return new MyItemsDto(
                methods.stream().map(MyItemsService::toItem).toList(),
                connectors.stream()
                        .map(c -> toItem(c, linksByConnector.getOrDefault(c.getId(), List.of()), ownMethodIds))
                        .toList());
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

    private static MyItemsDto.IntegrationMethodItem toItem(IntegrationMethod method) {
        Application application = method.getApplication();
        return new MyItemsDto.IntegrationMethodItem(
                application.getId(),
                application.getDisplayName(),
                application.getLogoPath() != null,
                method.getId(),
                method.getRevision(),
                method.getDisplayName(),
                method.getLifecycleState() != null ? method.getLifecycleState().name() : null,
                toDate(method.getCreatedAt()),
                toDate(method.getUpdated()));
    }

    /**
     * @param links every method revision linking the connector
     * @param ownMethodIds the caller's methods; drafts of anyone else's are not theirs to see
     */
    private static MyItemsDto.ConnectorItem toItem(Connector connector, List<IntegrationMethodConnector> links,
                                                   Set<UUID> ownMethodIds) {
        // One entry per method: its published revision when it has one, else its newest.
        Map<UUID, IntegrationMethod> byMethod = new LinkedHashMap<>();
        for (IntegrationMethodConnector link : links) {
            IntegrationMethod method = link.getIntegrationMethod();
            if (method.getLifecycleState() != LifecycleType.ACTIVE && !ownMethodIds.contains(method.getId())) {
                continue;
            }
            byMethod.merge(method.getId(), method, MyItemsService::preferredRevision);
        }
        List<MyItemsDto.ConnectorUsage> usedBy = byMethod.values().stream()
                .map(m -> new MyItemsDto.ConnectorUsage(
                        m.getApplication().getId(), m.getId(), m.getRevision(), m.getDisplayName()))
                .sorted(Comparator.comparing(u -> nullToEmpty(u.displayName()), String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new MyItemsDto.ConnectorItem(
                connector.getId(),
                displayName(connector),
                connector.getRevision(),
                lifecycleState(connector),
                usedBy);
    }

    private static IntegrationMethod preferredRevision(IntegrationMethod a, IntegrationMethod b) {
        boolean aActive = a.getLifecycleState() == LifecycleType.ACTIVE;
        boolean bActive = b.getLifecycleState() == LifecycleType.ACTIVE;
        if (aActive != bActive) {
            return aActive ? a : b;
        }
        return compareRevisions(a.getRevision(), b.getRevision()) >= 0 ? a : b;
    }

    /** ACTIVE once any version is published, otherwise the state of the newest version. */
    private static String lifecycleState(Connector connector) {
        List<ConnectorVersion> versions = connector.getConnectorVersions();
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        if (versions.stream().anyMatch(v -> v.getLifecycleState() == LifecycleType.ACTIVE)) {
            return LifecycleType.ACTIVE.name();
        }
        return versions.stream()
                .max(Comparator.comparing(ConnectorVersion::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ConnectorVersion::getLifecycleState)
                .map(Enum::name)
                .orElse(null);
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
