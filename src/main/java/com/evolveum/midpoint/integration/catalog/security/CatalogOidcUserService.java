/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the identity the provider asserts into the application's security context. Which
 * claims carry the role, the groups and the organization is configuration rather than code
 * — see {@link CatalogClaims}:
 * <ul>
 *   <li>the roles claim becomes {@code ROLE_*} authorities and the strongest known catalog
 *       role becomes the user's effective role (default: ReadOnly);</li>
 *   <li>the groups claim (e.g. Partner, Subscriber) becomes {@code GROUP_*} authorities;</li>
 *   <li>the organization claim carries the organization's immutable identifier;
 *       /api/auth/me resolves it to the current display name.</li>
 * </ul>
 * No user data is persisted — the catalog keeps none. Questions about other users are
 * answered from what was recorded on catalog items when they were written.
 */
@Service
public class CatalogOidcUserService extends OidcUserService {

    public static final String GROUP_AUTHORITY_PREFIX = "GROUP_";

    private final CatalogClaims claims;

    public CatalogOidcUserService(CatalogClaims claims) {
        this.claims = claims;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        List<String> catalogRoles = claims.roles(oidcUser);
        String effectiveRole = claims.effectiveRole(oidcUser);
        List<String> groups = claims.groups(oidcUser);

        Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
        authorities.add(new SimpleGrantedAuthority("ROLE_" + effectiveRole));
        catalogRoles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        groups.forEach(group -> authorities.add(new SimpleGrantedAuthority(GROUP_AUTHORITY_PREFIX + group)));

        String userNameAttribute = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), userNameAttribute);
    }
}
