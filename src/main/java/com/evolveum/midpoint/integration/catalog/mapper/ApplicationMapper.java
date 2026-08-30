/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.mapper;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.dto.*;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.CatalogUserRepository;
import com.evolveum.midpoint.integration.catalog.repository.DownloadRepository;
import com.evolveum.midpoint.integration.catalog.repository.MidpointVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.repository.VoteRepository;
import com.evolveum.midpoint.integration.catalog.service.AuthService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

@Component
public class ApplicationMapper {

    private final RequestRepository requestRepository;
    private final VoteRepository voteRepository;
    private final DownloadRepository downloadRepository;
    private final CatalogUserRepository catalogUserRepository;
    private final MidpointVersionRepository midpointVersionRepository;
    private final AuthService authService;
    private final OpenProjectProperties openProjectProperties;

    public ApplicationMapper(RequestRepository requestRepository, VoteRepository voteRepository,
                             DownloadRepository downloadRepository, CatalogUserRepository catalogUserRepository,
                             MidpointVersionRepository midpointVersionRepository,
                             AuthService authService, OpenProjectProperties openProjectProperties) {
        this.requestRepository = requestRepository;
        this.voteRepository = voteRepository;
        this.downloadRepository = downloadRepository;
        this.catalogUserRepository = catalogUserRepository;
        this.midpointVersionRepository = midpointVersionRepository;
        this.authService = authService;
        this.openProjectProperties = openProjectProperties;
    }

    // ── Tag helpers ───────────────────────────────────────────────────────────

    public List<ApplicationTagDto> filterTagsByType(Application app, ApplicationTag.ApplicationTagType tagType) {
        if (app.getApplicationApplicationTags() == null) return null;
        return app.getApplicationApplicationTags().stream()
                .filter(aat -> aat.getApplicationTag().getTagType() == tagType)
                .map(this::mapToApplicationTagDto)
                .toList();
    }

    public List<ApplicationTagDto> mapAllTags(Application app) {
        if (app.getApplicationApplicationTags() == null) return null;
        return app.getApplicationApplicationTags().stream()
                .map(this::mapToApplicationTagDto)
                .toList();
    }

    public ApplicationTagDto mapToApplicationTagDto(ApplicationApplicationTag appTag) {
        return new ApplicationTagDto(
                appTag.getApplicationTag().getId(),
                appTag.getApplicationTag().getName(),
                appTag.getApplicationTag().getDisplayName(),
                appTag.getApplicationTag().getTagType() != null
                        ? appTag.getApplicationTag().getTagType().name() : null
        );
    }

    // ── Integration-method versions ───────────────────────────────────────────

    /**
     * Maps integration methods to IntegrationMethodDto.
     * Capabilities are collected from IntegrationMethodCapability → items → Capability.
     */
    public List<IntegrationMethodDto> mapIntegrationMethods(Application app) {
        return mapIntegrationMethods(app, null);
    }

    /**
     * Maps integration methods to IntegrationMethodDto, telling {@code viewer} the support ticket of
     * every revision they are allowed to see one for.
     *
     * <p>The ticket rides along here rather than being fetched per revision because both of its parts
     * - the id and the URL built from it - are already at hand: the id is a column of the row being
     * mapped, and the URL is a format of it. Asking the portal instead would mean one round trip per
     * revision on every page load, for a link whose contents never depended on the portal.
     *
     * @param viewer who is asking, or null when nobody in particular is; the ticket is filled in only
     *               for the submitting side and the reviewer, by the same check that guards editing,
     *               because it points into the review conversation rather than the catalog's public
     *               face. Every other caller sees both ticket fields as null.
     */
    public List<IntegrationMethodDto> mapIntegrationMethods(Application app, String viewer) {
        if (app.getIntegrationMethods() == null) return null;

        return app.getIntegrationMethods().stream()
                .map(method -> {
                    List<String> capabilities = collectCapabilities(method);
                    String lifecycleState = method.getLifecycleState() != null
                            ? method.getLifecycleState().name() : null;

                    // Author's organization drives the org-mate access checks; an
                    // IndividualContributor's uploads stay personal even when they belong
                    // to an organization, so the org is only exposed for org contributors.
                    Integer organizationId = null;
                    if (method.getAuthor() != null) {
                        organizationId = catalogUserRepository.findByUsername(method.getAuthor())
                                .filter(u -> CatalogRole.ORGANIZATION_CONTRIBUTOR.matches(u.getRole()))
                                .map(u -> u.getOrganization() != null ? u.getOrganization().getId() : null)
                                .orElse(null);
                    }

                    // Connector info from first linked connector
                    String connectorVersion = null;
                    String framework = null;
                    String connectorDisplayName = null;
                    String downloadLink = null;
                    String errorMessage = null;
                    LocalDate releasedDate = null;
                    if (!method.getConnectors().isEmpty()) {
                        IntegrationMethodConnector link = method.getConnectors().get(0);
                        if (link.getConnector() != null) {
                            connectorDisplayName = link.getConnector().getDisplayName();
                            ConnectorBundle bundle = link.getConnector().getConnectorBundle();
                            if (bundle != null) {
                                if (bundle.getFramework() != null) {
                                    framework = bundle.getFramework().name();
                                }
                            }
                            connectorVersion = link.getConnector().getConnectorVersions().stream()
                                    .filter(cv -> cv.getConnectorBundleVersion() != null
                                            && cv.getConnectorBundleVersion().getBundleVersion() != null)
                                    .map(cv -> cv.getConnectorBundleVersion().getBundleVersion())
                                    .findFirst().orElse(null);
                            downloadLink = link.getConnector().getConnectorVersions().stream()
                                    .filter(cv -> cv.getConnectorBundleVersion() != null
                                            && cv.getConnectorBundleVersion().getBrowseLink() != null)
                                    .map(cv -> cv.getConnectorBundleVersion().getBrowseLink())
                                    .findFirst().orElse(null);
                            errorMessage = link.getConnector().getConnectorVersions().stream()
                                    .map(ConnectorVersion::getConnectorBundleVersion)
                                    .filter(cbv -> cbv != null && cbv.getErrorMessage() != null)
                                    .map(ConnectorBundleVersion::getErrorMessage)
                                    .findFirst().orElse(null);
                            releasedDate = link.getConnector().getConnectorVersions().stream()
                                    .map(ConnectorVersion::getConnectorBundleVersion)
                                    .filter(cbv -> cbv != null && cbv.getCreatedAt() != null)
                                    .map(ConnectorBundleVersion::getCreatedAt)
                                    .max(Comparator.naturalOrder())
                                    .map(java.time.LocalDateTime::toLocalDate)
                                    .orElse(null);
                        }
                    }

                    // Every linked connector, so the card can list them all (the flattened
                    // connector fields above only carry the first link).
                    List<IncludedConnectorDto> includedConnectors = method.getConnectors().stream()
                            .map(IntegrationMethodConnector::getConnector)
                            .filter(Objects::nonNull)
                            .map(c -> new IncludedConnectorDto(
                                    c.getFullyQualifiedClassName(),
                                    c.getDisplayName(),
                                    // Newest version row = the connector's current version
                                    // (see buildIntegrationMethodListItem).
                                    c.getConnectorVersions().stream()
                                            .filter(cv -> cv.getConnectorBundleVersion() != null)
                                            .max(Comparator.comparingInt(ConnectorVersion::getId))
                                            .map(cv -> {
                                                ConnectorBundleVersion cbv = cv.getConnectorBundleVersion();
                                                return cbv.getBundleVersion() != null
                                                        ? cbv.getBundleVersion() : cbv.getRevision();
                                            })
                                            .orElse(null),
                                    c.getDescription()))
                            .toList();

                    List<String> integMethodTypes = method.getIntegMethodTypes().stream()
                            .map(IntegrationMethodType::getDisplayName)
                            .toList();

                    List<ObjectClassCapabilityDto> objectClassCapabilities = method.getCapabilities().stream()
                            .filter(cap -> cap.getItems() != null && !cap.getItems().isEmpty())
                            .map(cap -> new ObjectClassCapabilityDto(
                                    cap.getObjectClass(),
                                    cap.getItems().stream()
                                            .filter(item -> item.getCapability() != null
                                                    && item.getCapability().getName() != null)
                                            .map(item -> item.getCapability().getName())
                                            .toList()
                            ))
                            .toList();

                    long downloadCount = method.getConnectors().stream()
                            .map(IntegrationMethodConnector::getConnector)
                            .filter(Objects::nonNull)
                            .flatMap(c -> c.getConnectorVersions().stream())
                            .map(ConnectorVersion::getConnectorBundleVersion)
                            .filter(Objects::nonNull)
                            .distinct()
                            .mapToLong(cbv -> cbv.getDownloads() != null ? cbv.getDownloads().size() : 0L)
                            .sum();

                    Integer supportTicketId = visibleSupportTicketId(method, viewer);
                    String supportTicketUrl = supportTicketId != null
                            ? openProjectProperties.workPackageUrl(supportTicketId) : null;

                    return new IntegrationMethodDto(
                            method.getId(),
                            method.getDescription(),
                            null,           // implementationTags
                            capabilities,
                            objectClassCapabilities,
                            connectorVersion,
                            null,           // systemVersion
                            releasedDate,   // connector_bundle_version.created_at
                            method.getAuthor(),
                            organizationId,
                            lifecycleState,
                            downloadLink,
                            framework,
                            errorMessage,
                            downloadCount,
                            method.getMidpointMinVersionId(),
                            method.getMidpointMaxVersionId(),
                            connectorDisplayName,
                            integMethodTypes,
                            method.getRevision(),
                            method.getDisplayName(),
                            method.getTutorial(),
                            method.getFilePath(),
                            method.getReviewedBy(),
                            method.getMaintainer(),
                            method.getCreatedAt() != null ? method.getCreatedAt().toLocalDate() : null,
                            method.getUpdated() != null ? method.getUpdated().toLocalDate() : null,
                            includedConnectors,
                            supportTicketId,
                            supportTicketUrl
                    );
                })
                .toList();
    }

    /**
     * The revision's support ticket id if {@code viewer} may be told it, otherwise null.
     *
     * <p>The same boundary {@code SupportTicketService.describe} enforces: the reviewer (a superuser,
     * whom {@code canEdit} already lets through) and the submitting side. A portal that is not
     * configured has no tickets to point at, and a revision whose work package has not been opened -
     * because the portal was unreachable when it was submitted, and the retry has not caught up yet -
     * simply has no id to give.
     */
    private Integer visibleSupportTicketId(IntegrationMethod method, String viewer) {
        if (viewer == null || viewer.isBlank()
                || method.getSupportTicketId() == null
                || !openProjectProperties.enabled()) {
            return null;
        }
        return authService.canEdit(viewer, method.getAuthor(), method.getMaintainer())
                ? method.getSupportTicketId() : null;
    }

    private List<String> collectCapabilities(IntegrationMethod method) {
        if (method.getCapabilities() == null) return null;
        return method.getCapabilities().stream()
                .filter(cap -> cap.getItems() != null)
                .flatMap(cap -> cap.getItems().stream())
                .filter(item -> item.getCapability() != null && item.getCapability().getName() != null)
                .map(item -> item.getCapability().getName())
                .distinct()
                .toList();
    }

    public List<String> extractFrameworks(Application app) {
        if (app.getIntegrationMethods() == null || app.getIntegrationMethods().isEmpty()) return null;
        return app.getIntegrationMethods().stream()
                .flatMap(m -> m.getConnectors().stream())
                .map(IntegrationMethodConnector::getConnector)
                .filter(c -> c != null && c.getConnectorBundle() != null
                        && c.getConnectorBundle().getFramework() != null)
                .map(c -> c.getConnectorBundle().getFramework().name())
                .distinct()
                .toList();
    }

    // ── ApplicationDto mapping ────────────────────────────────────────────────

    public ApplicationDto mapToApplicationDto(Application app) {
        return mapToApplicationDto(app, (String) null);
    }

    /**
     * The application as {@code viewer} may see it, which for the submitting side and the reviewer
     * includes the support ticket of each revision they are concerned with - see
     * {@link #mapIntegrationMethods(Application, String)}. Pass null for an anonymous read.
     */
    public ApplicationDto mapToApplicationDto(Application app, String viewer) {
        List<String> capabilities = null;
        List<ObjectClassCapabilityDto> objectClassCapabilities = null;
        String requester = null;
        Long requestId = null;
        Long voteCount = null;

        if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
            Optional<Request> requestOpt = requestRepository.findByApplicationId(app.getId());
            if (requestOpt.isPresent()) {
                Request request = requestOpt.get();
                objectClassCapabilities = request.getObjectClassCapabilities().stream()
                        .filter(occ -> occ.getCapabilities() != null && occ.getCapabilities().length > 0)
                        .map(occ -> new ObjectClassCapabilityDto(
                                occ.getObjectName(),
                                Arrays.stream(occ.getCapabilities()).map(Enum::name).toList()
                        ))
                        .toList();
                capabilities = objectClassCapabilities.stream()
                        .flatMap(occ -> occ.capabilities().stream())
                        .distinct()
                        .collect(java.util.stream.Collectors.toList());
                requester = request.getRequester();
                requestId = request.getId();
                voteCount = voteRepository.countByRequestId(requestId);
            }
        }
        return mapToApplicationDto(app, capabilities, requester, requestId, voteCount,
                objectClassCapabilities, viewer);
    }

    public ApplicationDto mapToApplicationDto(Application app, List<String> capabilities, String requester,
                                               Long requestId, Long voteCount) {
        return mapToApplicationDto(app, capabilities, requester, requestId, voteCount, null, null);
    }

    public ApplicationDto mapToApplicationDto(Application app, List<String> capabilities, String requester,
                                               Long requestId, Long voteCount,
                                               List<ObjectClassCapabilityDto> objectClassCapabilities) {
        return mapToApplicationDto(app, capabilities, requester, requestId, voteCount,
                objectClassCapabilities, null);
    }

    /** @param viewer who is asking, see {@link #mapIntegrationMethods(Application, String)}. */
    public ApplicationDto mapToApplicationDto(Application app, List<String> capabilities, String requester,
                                               Long requestId, Long voteCount,
                                               List<ObjectClassCapabilityDto> objectClassCapabilities,
                                               String viewer) {
        List<CountryOfOriginDto> origins = mapOrigins(app);
        List<ApplicationTagDto> categories = filterTagsByType(app, ApplicationTag.ApplicationTagType.CATEGORY);
        List<ApplicationTagDto> tags = mapAllTags(app);
        List<IntegrationMethodDto> integrationMethods = mapIntegrationMethods(app, viewer);
        List<String> frameworks = extractFrameworks(app);
        String lifecycleState = app.getLifecycleState() != null ? app.getLifecycleState().name() : null;

        return ApplicationDto.builder()
                .id(app.getId())
                .displayName(app.getDisplayName())
                .description(app.getDescription())
                .logoPath(app.getLogoPath())
                .lifecycleState(lifecycleState)
                .updated(app.getUpdated())
                .createdAt(app.getCreatedAt())
                .capabilities(capabilities)
                .requester(requester)
                .origins(origins)
                .categories(categories)
                .tags(tags)
                .integrationMethods(integrationMethods)
                .requestId(requestId)
                .voteCount(voteCount)
                .frameworks(frameworks)
                .objectClassCapabilities(objectClassCapabilities)
                .build();
    }

    // ── ApplicationCardDto mapping ────────────────────────────────────────────

    public ApplicationCardDto toCardDto(Application app) {
        String lifecycleState = app.getLifecycleState() != null ? app.getLifecycleState().name() : null;
        List<CountryOfOriginDto> origins = mapOrigins(app);

        List<ApplicationTagDto> categories = null;
        List<ApplicationTagDto> tags = null;
        if (app.getApplicationApplicationTags() != null) {
            categories = app.getApplicationApplicationTags().stream()
                    .filter(aat -> aat.getApplicationTag().getTagType() == ApplicationTag.ApplicationTagType.CATEGORY)
                    .map(aat -> new ApplicationTagDto(aat.getApplicationTag().getId(),
                            aat.getApplicationTag().getName(), aat.getApplicationTag().getDisplayName(),
                            aat.getApplicationTag().getTagType().name()))
                    .toList();
            tags = app.getApplicationApplicationTags().stream()
                    .filter(aat -> aat.getApplicationTag().getTagType() != ApplicationTag.ApplicationTagType.CATEGORY)
                    .map(aat -> new ApplicationTagDto(aat.getApplicationTag().getId(),
                            aat.getApplicationTag().getName(), aat.getApplicationTag().getDisplayName(),
                            aat.getApplicationTag().getTagType().name()))
                    .toList();
        }

        Long requestId = null;
        Long voteCount = null;
        List<String> capabilities = new ArrayList<>();

        if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
            Optional<Request> requestOpt = requestRepository.findByApplicationId(app.getId());
            if (requestOpt.isPresent()) {
                Request request = requestOpt.get();
                requestId = request.getId();
                voteCount = voteRepository.countByRequestId(request.getId());
                request.getObjectClassCapabilities().stream()
                        .filter(occ -> occ.getCapabilities() != null)
                        .flatMap(occ -> Arrays.stream(occ.getCapabilities()))
                        .map(Enum::name)
                        .forEach(capabilities::add);
            }
        }

        // Collect capabilities from integration methods
        if (app.getIntegrationMethods() != null) {
            app.getIntegrationMethods().stream()
                    .flatMap(m -> collectCapabilities(m) != null ? collectCapabilities(m).stream() : Stream.empty())
                    .filter(cap -> !capabilities.contains(cap))
                    .forEach(capabilities::add);
        }

        List<String> frameworks = extractFrameworks(app);

        // Covered midPoint version IDs = union of each integration method's
        // [midpoint_minVersion, midpoint_maxVersion] range, so the "MidPoint version"
        // filter matches any selected version within range. A null bound is open-ended
        // (clamped to the lowest/highest known version), matching the app detail view.
        List<String> midpointVersions = new ArrayList<>();
        if (app.getIntegrationMethods() != null) {
            List<Integer> allVersionIds = midpointVersionRepository.findAll().stream()
                    .map(MidpointVersion::getId)
                    .sorted()
                    .toList();
            if (!allVersionIds.isEmpty()) {
                int globalMin = allVersionIds.get(0);
                int globalMax = allVersionIds.get(allVersionIds.size() - 1);
                Set<Integer> coveredIds = new TreeSet<>();
                for (IntegrationMethod method : app.getIntegrationMethods()) {
                    // Only active integration methods count toward supported versions.
                    if (LifecycleType.ACTIVE != method.getLifecycleState()) {
                        continue;
                    }
                    Integer min = method.getMidpointMinVersionId();
                    Integer max = method.getMidpointMaxVersionId();
                    int lo = (min != null) ? min : globalMin;
                    int hi = (max != null) ? max : globalMax;
                    if (lo > hi) {
                        int tmp = lo; lo = hi; hi = tmp;
                    }
                    for (Integer vid : allVersionIds) {
                        if (vid >= lo && vid <= hi) {
                            coveredIds.add(vid);
                        }
                    }
                }
                for (Integer id : coveredIds) {
                    midpointVersions.add(String.valueOf(id));
                }
            }
        }

        String currentMidpointVersion = null;
        Optional<MidpointVersion> currentVersionOpt = midpointVersionRepository.findByIsCurrentTrue();
        if (currentVersionOpt.isPresent() && app.getIntegrationMethods() != null) {
            Integer currentVersionId = currentVersionOpt.get().getId();
            boolean hasCurrentVersion = app.getIntegrationMethods().stream()
                    .anyMatch(m -> LifecycleType.ACTIVE == m.getLifecycleState()
                               && currentVersionId.equals(m.getMidpointMinVersionId()));
            if (hasCurrentVersion) {
                currentMidpointVersion = currentVersionOpt.get().getVersion();
            }
        }

        // Distinct integration method type display names across the app's methods,
        // used by the catalog's "Integration method" filter.
        List<String> integrationMethodTypes = null;
        if (app.getIntegrationMethods() != null) {
            integrationMethodTypes = app.getIntegrationMethods().stream()
                    .filter(m -> LifecycleType.ACTIVE == m.getLifecycleState())
                    .filter(m -> m.getIntegMethodTypes() != null)
                    .flatMap(m -> m.getIntegMethodTypes().stream())
                    .map(IntegrationMethodType::getDisplayName)
                    .filter(name -> name != null)
                    .distinct()
                    .toList();
            if (integrationMethodTypes.isEmpty()) {
                integrationMethodTypes = null;
            }
        }

        // Distinct maintainer categories (Evolveum/Partner/Community) derived from the
        // role of each integration method's author, for the "Maintainer" filter. We use
        // `author` (the publishing catalog_user's username) rather than `maintainer`,
        // which is a free-text field that does not link to catalog_users.
        List<String> maintainers = null;
        if (app.getIntegrationMethods() != null) {
            maintainers = app.getIntegrationMethods().stream()
                    .map(IntegrationMethod::getAuthor)
                    .filter(username -> username != null)
                    .map(this::maintainerCategoryForUser)
                    .filter(category -> category != null)
                    .distinct()
                    .toList();
            if (maintainers.isEmpty()) {
                maintainers = null;
            }
        }

        return new ApplicationCardDto(
                app.getId(),
                app.getDisplayName(),
                app.getDescription(),
                app.getLogoPath(),
                lifecycleState,
                origins,
                categories,
                tags,
                capabilities.isEmpty() ? null : capabilities,
                requestId,
                voteCount,
                frameworks,
                midpointVersions.isEmpty() ? null : midpointVersions,
                currentMidpointVersion,
                integrationMethodTypes,
                maintainers
        );
    }

    /** Maps an integration method's maintainer username to its maintainer category. */
    private String maintainerCategoryForUser(String username) {
        return catalogUserRepository.findByUsername(username)
                .map(CatalogUser::getRole)
                .map(ApplicationMapper::roleToMaintainerCategory)
                .orElse(null);
    }

    /**
     * Maps a catalog_users.role to the maintainer category shown in the catalog:
     * Superuser → Evolveum, OrganizationContributor → Partner, IndividualContributor → Community.
     */
    private static String roleToMaintainerCategory(String role) {
        // Explicit type argument: one branch yields null, so there is nothing to infer U from.
        return CatalogRole.of(role)
                .<String>map(catalogRole -> switch (catalogRole) {
                    case SUPERUSER -> "Evolveum";
                    case ORGANIZATION_CONTRIBUTOR -> "Partner";
                    case INDIVIDUAL_CONTRIBUTOR -> "Community";
                    case READ_ONLY -> null; // a reader maintains nothing
                })
                .orElse(null);
    }

    // ── IntegrationMethod list item ───────────────────────────────────────────

    public ImplementationListItemDto mapToIntegrationMethodListItemDto(IntegrationMethod method) {
        if (method == null) return null;
        IntegrationMethodConnector link = method.getConnectors().isEmpty()
                ? null
                : method.getConnectors().get(0);
        return buildIntegrationMethodListItem(method, link);
    }

    /**
     * Maps every connector linked to the given integration method to its own list item,
     * so a method revision with multiple connectors yields multiple entries.
     */
    public List<ImplementationListItemDto> mapConnectorsForMethod(IntegrationMethod method) {
        if (method == null) return List.of();
        return method.getConnectors().stream()
                .filter(link -> link.getConnector() != null)
                .map(link -> buildIntegrationMethodListItem(method, link))
                .toList();
    }

    private ImplementationListItemDto buildIntegrationMethodListItem(IntegrationMethod method, IntegrationMethodConnector link) {
        Connector connector = link != null ? link.getConnector() : null;
        String connectorMinVersion = link != null ? link.getConnectorMinVersion() : null;
        String connectorMaxVersion = link != null ? link.getConnectorMaxVersion() : null;
        Integer connectorId = null;
        String connectorVersion = null;
        String browseLink = null;
        String gitCloneUrl = null;
        String buildFramework = null;
        String pathToProject = null;
        String className = null;
        String maintainer = null;
        String connectorDescription = null;
        String licenseType = null;
        String ticketingLink = null;
        String connectorDisplayName = null;
        String bundleDisplayName = null;
        String bundleName = null;
        String bundleFramework = null;
        String commitTag = null;
        List<ObjectClassCapabilityDto> objectClassCapabilities = List.of();

        if (connector != null) {
            connectorId = connector.getId();
            className = connector.getFullyQualifiedClassName();
            maintainer = connector.getMaintainer();
            connectorDescription = connector.getDescription();
            connectorDisplayName = connector.getDisplayName();
            ConnectorBundle bundle = connector.getConnectorBundle();
            if (bundle != null) {
                licenseType = bundle.getLicense() != null ? bundle.getLicense().name() : null;
                ticketingLink = bundle.getTicketingLink();
                bundleDisplayName = bundle.getDisplayName();
                bundleName = bundle.getBundleName();
                bundleFramework = bundle.getFramework() != null ? bundle.getFramework().name() : null;
            }
            Optional<ConnectorVersion> latestCv = connector.getConnectorVersions().stream()
                    .filter(cv -> cv.getConnectorBundleVersion() != null)
                    .max(java.util.Comparator.comparingInt(ConnectorVersion::getId));
            if (latestCv.isPresent()) {
                ConnectorBundleVersion cbv = latestCv.get().getConnectorBundleVersion();
                // bundle_version is the editable, user-facing version; fall back to the PK revision.
                connectorVersion = cbv.getBundleVersion() != null ? cbv.getBundleVersion() : cbv.getRevision();
                browseLink = cbv.getBrowseLink();
                gitCloneUrl = cbv.getGitCloneUrl();
                buildFramework = cbv.getBuildFramework() != null ? cbv.getBuildFramework().name() : null;
                pathToProject = cbv.getPathToProject();
                commitTag = cbv.getCommitTag();
            }
            objectClassCapabilities = mapConnectorVersionCapabilities(connector);
        }

        String maintainerOrganization = maintainer == null ? null
                : catalogUserRepository.findByUsername(maintainer)
                        .filter(u -> CatalogRole.ORGANIZATION_CONTRIBUTOR.matches(u.getRole()))
                        .map(u -> u.getOrganization() != null ? u.getOrganization().getName() : null)
                        .orElse(null);

        return new ImplementationListItemDto(
                method.getId(),
                connectorId,
                method.getDisplayName(),
                method.getDescription(),
                null,               // publishedDate (no direct field)
                connectorVersion,
                method.getDisplayName(),
                maintainer,
                maintainerOrganization,
                licenseType,
                connectorDescription,
                browseLink,
                ticketingLink,
                buildFramework,
                gitCloneUrl,
                pathToProject,
                className,
                connectorDisplayName,
                bundleDisplayName,
                bundleName,
                bundleFramework,
                commitTag,
                objectClassCapabilities,
                connectorMinVersion,
                connectorMaxVersion
        );
    }

    /**
     * Collects the object-class capabilities of the connector's first connector version,
     * grouped by object class, so the edit form can pre-fill the capability picker.
     */
    private List<ObjectClassCapabilityDto> mapConnectorVersionCapabilities(Connector connector) {
        return connector.getConnectorVersions().stream()
                .max(java.util.Comparator.comparingInt(ConnectorVersion::getId))
                .map(this::mapCapabilitiesOf)
                .orElseGet(List::of);
    }

    /**
     * Collects the object-class capabilities of the connector's latest <em>published</em>
     * (ACTIVE) connector version, grouped by object class, so the publish form can pre-fill
     * the capability picker. Versions still in review (IN_REVIEW) are ignored.
     */
    public List<ObjectClassCapabilityDto> mapLatestPublishedConnectorVersionCapabilities(Connector connector) {
        return connector.getConnectorVersions().stream()
                .filter(cv -> cv.getLifecycleState() == LifecycleType.ACTIVE)
                .max(java.util.Comparator.comparingInt(ConnectorVersion::getId))
                .map(this::mapCapabilitiesOf)
                .orElseGet(List::of);
    }

    private List<ObjectClassCapabilityDto> mapCapabilitiesOf(ConnectorVersion cv) {
        return cv.getCapabilities().stream()
                .filter(cap -> cap.getItems() != null && !cap.getItems().isEmpty())
                .map(cap -> new ObjectClassCapabilityDto(
                        cap.getObjectClass(),
                        cap.getItems().stream()
                                .filter(item -> item.getCapability() != null
                                        && item.getCapability().getName() != null)
                                .map(item -> item.getCapability().getName())
                                .toList()
                ))
                .toList();
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private List<CountryOfOriginDto> mapOrigins(Application app) {
        if (app.getApplicationOrigins() == null) return null;
        return app.getApplicationOrigins().stream()
                .map(ao -> new CountryOfOriginDto(
                        ao.getCountryOfOrigin().getId(),
                        ao.getCountryOfOrigin().getName(),
                        ao.getCountryOfOrigin().getDisplayName()))
                .toList();
    }
}
