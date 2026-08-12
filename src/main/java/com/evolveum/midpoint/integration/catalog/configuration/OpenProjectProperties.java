/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

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
 * @param typeId              work package type id to create ({@code /api/v3/types/{id}}); the
 *                            create call rejects a request without a type.
 * @param initialStatusId     status id a newly opened work package is created with
 *                            ({@code /api/v3/statuses/{id}}). An id rather than a name because
 *                            creating needs a link, and resolving a name would cost a lookup.
 * @param approvalStatuses    work package statuses that release the reviewer's "Confirm approval"
 *                            button; any one of them is enough. Names rather than ids because
 *                            these are compared against what the portal reports for a work
 *                            package, which is the status title.
 * @param trustAllCertificates disables TLS verification. For the local docker test instance,
 *                            which serves a self-signed certificate. Never enable in production.
 */
@ConfigurationProperties(prefix = "openproject")
public record OpenProjectProperties(
        String url,
        String username,
        String password,
        String project,
        Integer typeId,
        Integer initialStatusId,
        List<String> approvalStatuses,
        boolean trustAllCertificates
) {

    /**
     * Defaults for the values that have a sensible one, so a deployment that configures only the
     * URL, the token and the project still works rather than sending "types/null" to the portal.
     */
    public OpenProjectProperties {
        username = (username == null || username.isBlank()) ? "apikey" : username;
        typeId = typeId != null ? typeId : 1;
        initialStatusId = initialStatusId != null ? initialStatusId : 1;
        approvalStatuses = (approvalStatuses == null || approvalStatuses.isEmpty())
                ? List.of("Tested", "Closed")
                : List.copyOf(approvalStatuses);
    }

    /**
     * Whether a work package reporting {@code status} lets the reviewer approve. Compared by title
     * and ignoring case and surrounding whitespace, so the configured value only has to match what
     * the portal displays.
     */
    public boolean isApprovalStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return approvalStatuses.stream()
                .anyMatch(candidate -> candidate.trim().toLowerCase(Locale.ROOT).equals(normalized));
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
