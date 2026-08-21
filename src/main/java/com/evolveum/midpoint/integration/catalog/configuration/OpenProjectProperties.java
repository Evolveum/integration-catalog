/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Connection to the support portal that holds the review conversation for a submitted
 * integration method. Everything the integration needs is configured here, so pointing the
 * catalog at a different portal instance or a different target project is a change of
 * properties only - no value below appears anywhere in the code.
 *
 * @param url                 base URL of the portal, e.g. {@code https://support.evolveum.com}.
 *                            Empty disables the integration entirely: no work package is created
 *                            and the reviewer's ticket check is skipped.
 * @param username            service account user name. OpenProject does not accept a real login
 *                            over basic auth - it wants the literal {@code apikey} here and the
 *                            account's API token as the password.
 * @param password            service account secret matching {@link #username()}.
 * @param project             identifier of the project the work packages are created in.
 * @param type                work package type to create; the create call rejects a request
 *                            without a type. Named rather than numbered so the choice is legible
 *                            and a typo fails at startup instead of on the first submission. The
 *                            type also has to be enabled in {@link #project()}, and its workflow
 *                            decides which statuses the work package can later reach - see
 *                            {@link OpenProjectType}.
 * @param typeId              overrides the id of {@link #type()}, for a portal that numbers its
 *                            types differently from a stock instance. Normally unset.
 * @param initialStatus       status a newly opened work package is created with. Set explicitly
 *                            rather than left to the project default, so a submission always
 *                            lands in a known state.
 * @param initialStatusId     overrides the id of {@link #initialStatus()}, for a portal that
 *                            numbers its statuses differently. Normally unset.
 * @param approvalStatuses    work package statuses that release the reviewer's "Confirm approval"
 *                            button; any one of them is enough. Free text rather than
 *                            {@link OpenProjectStatus} constants, because a portal may carry
 *                            statuses this catalog has never heard of; both spellings work, as
 *                            matching goes through {@link OpenProjectStatus#normalize(String)}.
 * @param watchers            portal logins added as watchers of every work package the catalog
 *                            opens, so the reviewing side is notified of a submission without
 *                            anyone polling the catalog. Logins rather than e-mail addresses,
 *                            because OpenProject offers no filter on the address; each has to be
 *                            a member of {@link #project()} with permission to see the work
 *                            package. Empty leaves the portal's own default - the account behind
 *                            {@link #username()} watches what it creates.
 * @param trustAllCertificates disables TLS verification. For the local docker test instance,
 *                            which serves a self-signed certificate. Never enable in production.
 */
@ConfigurationProperties(prefix = "openproject")
public record OpenProjectProperties(
        String url,
        String username,
        String password,
        String project,
        OpenProjectType type,
        Integer typeId,
        OpenProjectStatus initialStatus,
        Integer initialStatusId,
        List<String> approvalStatuses,
        List<String> watchers,
        boolean trustAllCertificates
) {

    /**
     * Defaults for the values that have a sensible one, so a deployment that configures only the
     * URL, the token and the project still works rather than sending "types/null" to the portal.
     *
     * <p>The two numeric ids are resolved here as well: unset, they follow the chosen constant, so
     * {@link #typeId()} and {@link #initialStatusId()} always answer with the id to actually send.
     */
    public OpenProjectProperties {
        username = (username == null || username.isBlank()) ? "apikey" : username;
        type = type != null ? type : OpenProjectType.TASK;
        initialStatus = initialStatus != null ? initialStatus : OpenProjectStatus.NEW;
        typeId = typeId != null ? typeId : type.id();
        initialStatusId = initialStatusId != null ? initialStatusId : initialStatus.id();
        approvalStatuses = (approvalStatuses == null || approvalStatuses.isEmpty())
                ? List.of(OpenProjectStatus.TESTED.title(), OpenProjectStatus.CLOSED.title())
                : List.copyOf(approvalStatuses);
        // Trimmed and emptied out, because "openproject.watchers=" binds to a list holding one
        // blank string rather than to no list at all, and a blank login would be looked up.
        watchers = watchers == null ? List.of() : watchers.stream()
                .filter(login -> login != null && !login.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * Whether a work package reporting {@code status} lets the reviewer approve. Compared through
     * {@link OpenProjectStatus#normalize(String)}, so the configured value only has to mean what the
     * portal displays - {@code Tested}, {@code tested} and {@code TESTED} are all the same status.
     */
    public boolean isApprovalStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = OpenProjectStatus.normalize(status);
        return !normalized.isEmpty() && approvalStatuses.stream()
                .anyMatch(candidate -> OpenProjectStatus.normalize(candidate).equals(normalized));
    }

    /** Whether the portal is configured at all; when false the catalog behaves as it did before. */
    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    /** Browser URL of a work package, for the reviewer's and the author's links. */
    public String workPackageUrl(int workPackageId) {
        return stripTrailingSlash(url) + "/work_packages/" + workPackageId;
    }

    /** Base of the REST API, without a trailing slash. */
    public String apiBase() {
        return stripTrailingSlash(url) + "/api/v3";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
