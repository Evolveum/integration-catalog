/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reads the catalog's notion of role, group membership and organization out of OIDC token
 * claims.
 * <p>
 * The claim names are configuration, not code — {@code catalog.oidc.claims.*} in
 * application.properties — so pointing the catalog at a provider that names them
 * differently is a configuration change. The shapes accepted here are the ones OIDC
 * providers actually emit: a single value, an array of values, or (for the organization) an
 * object keyed by the organization's identifier.
 */
@Component
public class CatalogClaims {

    private final String rolesClaim;
    private final String groupsClaim;
    private final String organizationClaim;

    public CatalogClaims(
            @Value("${catalog.oidc.claims.roles:roles}") String rolesClaim,
            @Value("${catalog.oidc.claims.groups:groups}") String groupsClaim,
            @Value("${catalog.oidc.claims.organization:organization}") String organizationClaim) {
        this.rolesClaim = rolesClaim;
        this.groupsClaim = groupsClaim;
        this.organizationClaim = organizationClaim;
    }

    public String rolesClaim() {
        return rolesClaim;
    }

    public String groupsClaim() {
        return groupsClaim;
    }

    public String organizationClaim() {
        return organizationClaim;
    }

    /** The catalog roles carried by the token, in the order the provider listed them. */
    public List<String> roles(OidcUser oidcUser) {
        return stringList(claim(oidcUser, rolesClaim)).stream()
                .filter(CatalogRole.BY_PRECEDENCE::contains)
                .toList();
    }

    /** The strongest catalog role the token carries; ReadOnly when it carries none. */
    public String effectiveRole(OidcUser oidcUser) {
        List<String> roles = roles(oidcUser);
        return CatalogRole.BY_PRECEDENCE.stream()
                .filter(roles::contains)
                .findFirst()
                .orElse(CatalogRole.READ_ONLY);
    }

    /** Group membership (e.g. Partner, Subscriber); empty when the token carries none. */
    public List<String> groups(OidcUser oidcUser) {
        return stringList(claim(oidcUser, groupsClaim));
    }

    /**
     * The identifier of the user's organization, or {@code null} when they belong to none.
     * Only the first one is used: the catalog models a user as publishing on behalf of at
     * most one organization.
     */
    public String organizationId(OidcUser oidcUser) {
        return organizationIds(oidcUser).stream().findFirst().orElse(null);
    }

    /**
     * All organization identifiers in the claim. The claim is an array of identifiers when
     * the provider emits strings, and an object keyed by identifier when it emits JSON;
     * both shapes are accepted.
     */
    public List<String> organizationIds(OidcUser oidcUser) {
        Object claim = claim(oidcUser, organizationClaim);
        List<String> raw = claim instanceof Map<?, ?> byIdentifier
                ? byIdentifier.keySet().stream().map(String::valueOf).toList()
                : stringList(claim);
        return raw.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static Object claim(OidcUser oidcUser, String name) {
        return oidcUser != null ? oidcUser.getClaim(name) : null;
    }

    private static List<String> stringList(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return claim != null ? List.of(String.valueOf(claim)) : List.of();
    }
}
