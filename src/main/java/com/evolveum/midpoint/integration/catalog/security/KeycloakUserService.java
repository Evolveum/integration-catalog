/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only user directory backed by the Keycloak Admin REST API. Since the catalog keeps
 * no user, role or organization data of its own, every question about a user other than
 * the logged-in one (ownership checks, maintainer lists, organization members) is answered
 * here, straight from the Keycloak user attributes ({@code role}, {@code organization}).
 * <p>
 * Authentication uses the client-credentials grant of the same {@code integration-catalog}
 * client the login flow uses; its service account carries the {@code realm-management /
 * view-users} role. Lookups are cached for a short time because list/detail screens ask
 * about the same few users repeatedly (one mapper pass can trigger a lookup per row).
 * <p>
 * <b>Fail-soft:</b> a Keycloak outage must not take the catalog down with it. Every
 * lookup that cannot reach Keycloak falls back to the (possibly stale) cached answer,
 * or to "unknown user" / empty list when there is none, and lookups are suspended for
 * a short backoff so an unreachable Keycloak is not re-probed on every request.
 * Anonymous browsing then works fully; only ownership checks and maintainer listings
 * degrade until Keycloak is back.
 */
@Service
public class KeycloakUserService {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakUserService.class);

    /** How long a user lookup may be served from cache. */
    private static final long CACHE_TTL_MILLIS = 60_000;
    /** How long to suspend Keycloak calls after a failed one. */
    private static final long FAILURE_BACKOFF_MILLIS = 15_000;
    private static final int PAGE_SIZE = 100;

    /** A catalog-relevant view of a Keycloak user: effective role + organization attribute. */
    public record KeycloakUser(String username, String role, String organization) {
    }

    private final RestClient restClient = buildRestClient();
    private final String tokenEndpoint;
    private final String adminUsersEndpoint;
    private final String clientId;
    private final String clientSecret;

    private record CachedUser(Optional<KeycloakUser> user, long loadedAt) {
    }

    private record CachedList(List<KeycloakUser> users, long loadedAt) {
    }

    private final ConcurrentHashMap<String, CachedUser> userCache = new ConcurrentHashMap<>();
    private volatile CachedList listCache;
    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    /** Until this instant Keycloak is considered unreachable and no calls are attempted. */
    private volatile long unavailableUntil;

    public KeycloakUserService(
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-secret}") String clientSecret) {
        this.tokenEndpoint = issuerUri + "/protocol/openid-connect/token";
        // http://host/realms/<realm> -> http://host/admin/realms/<realm>/users
        this.adminUsersEndpoint = issuerUri.replaceFirst("/realms/", "/admin/realms/") + "/users";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** Looks a user up by username (case-insensitive, as usernames are in Keycloak). */
    public Optional<KeycloakUser> findUser(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String key = username.trim().toLowerCase(Locale.ROOT);
        CachedUser cached = userCache.get(key);
        if (cached != null && cached.loadedAt() + CACHE_TTL_MILLIS > System.currentTimeMillis()) {
            return cached.user();
        }
        if (backingOff()) {
            return cached != null ? cached.user() : Optional.empty();
        }
        try {
            List<UserRepresentation> found = restClient.get()
                    .uri(adminUsersEndpoint + "?exact=true&username={username}", key)
                    .header("Authorization", "Bearer " + adminToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            Optional<KeycloakUser> user = found == null ? Optional.empty() : found.stream()
                    .filter(u -> key.equalsIgnoreCase(u.username()))
                    .findFirst()
                    .map(UserRepresentation::toKeycloakUser);
            userCache.put(key, new CachedUser(user, System.currentTimeMillis()));
            return user;
        } catch (RestClientException | IllegalStateException e) {
            markUnavailable(e);
            return cached != null ? cached.user() : Optional.empty();
        }
    }

    /**
     * All (human) users of the realm. Service accounts are filtered out. Paginates through
     * the admin API, so it stays correct past {@value #PAGE_SIZE} users; cached briefly.
     */
    public List<KeycloakUser> listUsers() {
        CachedList cached = listCache;
        if (cached != null && cached.loadedAt() + CACHE_TTL_MILLIS > System.currentTimeMillis()) {
            return cached.users();
        }
        if (backingOff()) {
            return cached != null ? cached.users() : List.of();
        }
        try {
            List<KeycloakUser> users = new ArrayList<>();
            for (int first = 0; ; first += PAGE_SIZE) {
                List<UserRepresentation> page = restClient.get()
                        .uri(adminUsersEndpoint + "?first={first}&max={max}", first, PAGE_SIZE)
                        .header("Authorization", "Bearer " + adminToken())
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {
                        });
                if (page == null || page.isEmpty()) {
                    break;
                }
                page.stream()
                        .filter(u -> u.username() != null && !u.username().startsWith("service-account-"))
                        .map(UserRepresentation::toKeycloakUser)
                        .forEach(users::add);
                if (page.size() < PAGE_SIZE) {
                    break;
                }
            }
            List<KeycloakUser> result = List.copyOf(users);
            listCache = new CachedList(result, System.currentTimeMillis());
            return result;
        } catch (RestClientException | IllegalStateException e) {
            markUnavailable(e);
            return cached != null ? cached.users() : List.of();
        }
    }

    /** Users whose {@code organization} attribute equals the given name (case-insensitive). */
    public List<KeycloakUser> findUsersByOrganization(String organizationName) {
        if (organizationName == null || organizationName.isBlank()) {
            return List.of();
        }
        // The admin API's attribute query (?q=key:value) cannot express values containing
        // spaces ("Acme co."), so filter the (cached) full listing instead.
        return listUsers().stream()
                .filter(u -> organizationName.trim().equalsIgnoreCase(u.organization()))
                .toList();
    }

    private boolean backingOff() {
        return unavailableUntil > System.currentTimeMillis();
    }

    /**
     * Records a failed Keycloak call: suspends further calls for
     * {@value #FAILURE_BACKOFF_MILLIS} ms so page rendering does not wait for a connect
     * timeout per row. Logged once per backoff window (the failure itself is the trigger).
     */
    private void markUnavailable(Exception e) {
        unavailableUntil = System.currentTimeMillis() + FAILURE_BACKOFF_MILLIS;
        LOG.warn("Keycloak admin API unavailable ({}); serving cached/empty user data for the next {} s",
                e.getMessage(), FAILURE_BACKOFF_MILLIS / 1000);
    }

    /** Short timeouts: a dead Keycloak must degrade pages, not hang them. */
    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private synchronized String adminToken() {
        if (accessToken != null && tokenExpiresAt > System.currentTimeMillis()) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        TokenResponse token = restClient.post()
                .uri(tokenEndpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (token == null || token.access_token() == null) {
            throw new IllegalStateException("Keycloak returned no service-account access token");
        }
        accessToken = token.access_token();
        // Renew slightly early so an almost-expired token is never sent.
        tokenExpiresAt = System.currentTimeMillis() + (token.expires_in() - 10) * 1000L;
        return accessToken;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(String access_token, long expires_in) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserRepresentation(String username, Map<String, List<String>> attributes) {

        KeycloakUser toKeycloakUser() {
            List<String> roles = attributeValues("role");
            String effectiveRole = CatalogRole.BY_PRECEDENCE.stream()
                    .filter(roles::contains)
                    .findFirst()
                    .orElse(CatalogRole.READ_ONLY);
            List<String> organizations = attributeValues("organization");
            String organization = organizations.isEmpty() ? null : organizations.get(0).trim();
            return new KeycloakUser(username, effectiveRole,
                    organization == null || organization.isEmpty() ? null : organization);
        }

        private List<String> attributeValues(String name) {
            if (attributes == null) {
                return List.of();
            }
            return attributes.getOrDefault(name, List.of());
        }
    }
}
