/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.evolveum.midpoint.integration.catalog.service.UserProvisioningService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the identity Keycloak asserts into the application's security context. All three
 * catalog claims are sourced from plain Keycloak <em>user attributes</em> ({@code role},
 * {@code group}, {@code organization}) exposed by attribute protocol mappers on the client —
 * the realm carries no catalog-specific role or group objects:
 * <ul>
 *   <li>the "roles" claim becomes {@code ROLE_*} authorities and the strongest known
 *       catalog role becomes the user's effective role (default: ReadOnly);</li>
 *   <li>the "groups" claim (e.g. Partner, Subscriber) becomes {@code GROUP_*} authorities;</li>
 *   <li>the "organization" claim is mirrored, with the user row, into the local database so
 *       the DB-driven ownership logic keeps working.</li>
 * </ul>
 */
@Service
public class CatalogOidcUserService extends OidcUserService {

    public static final String ROLES_CLAIM = "roles";
    public static final String GROUPS_CLAIM = "groups";
    public static final String ORGANIZATION_CLAIM = "organization";
    public static final String GROUP_AUTHORITY_PREFIX = "GROUP_";

    private final UserProvisioningService userProvisioningService;

    public CatalogOidcUserService(UserProvisioningService userProvisioningService) {
        this.userProvisioningService = userProvisioningService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        Map<String, Object> claims = oidcUser.getClaims();

        List<String> catalogRoles = stringList(claims.get(ROLES_CLAIM)).stream()
                .filter(CatalogRole.BY_PRECEDENCE::contains)
                .toList();
        String effectiveRole = CatalogRole.BY_PRECEDENCE.stream()
                .filter(catalogRoles::contains)
                .findFirst()
                .orElse(CatalogRole.READ_ONLY);
        List<String> groups = stringList(claims.get(GROUPS_CLAIM));

        Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
        authorities.add(new SimpleGrantedAuthority("ROLE_" + effectiveRole));
        catalogRoles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        groups.forEach(group -> authorities.add(new SimpleGrantedAuthority(GROUP_AUTHORITY_PREFIX + group)));

        userProvisioningService.provision(
                oidcUser.getName(), effectiveRole, singleString(claims.get(ORGANIZATION_CLAIM)));

        String userNameAttribute = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), userNameAttribute);
    }

    private static List<String> stringList(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return claim != null ? List.of(String.valueOf(claim)) : List.of();
    }

    private static String singleString(Object claim) {
        List<String> values = stringList(claim);
        return values.isEmpty() ? null : values.get(0);
    }
}
