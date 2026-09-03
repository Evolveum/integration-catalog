/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.function.LongSupplier;

/**
 * The realm's usernames, read from the Keycloak Admin REST API.
 *
 * <p>The catalog keeps no user table, so the only people it can otherwise name are those already
 * recorded in an owner column - which leaves a newly created user impossible to designate as
 * maintainer until they have somehow published something themselves. This closes that one gap and
 * nothing more: roles still come from the token and organizations from the organizations table, so
 * the service account needs only {@code realm-management / view-users}.
 *
 * <p>Authentication is the client-credentials grant of the same {@code integration-catalog} client
 * the login flow uses, so no second credential is configured anywhere - only the realm has to
 * enable the client's service account.
 *
 * <p><b>Fail-soft.</b> Every failure returns the last known answer, or an empty list, and never an
 * error: an unreachable, unconfigured or deliberately disabled Keycloak costs the maintainer list
 * its Keycloak half and leaves the rest of the catalog working. Failures also suspend calls for a
 * short backoff, so a dead Keycloak is not re-probed on every request.
 */
@Slf4j
@Service
public class KeycloakUserDirectory {

    /** How long a listing may be served from cache. */
    private static final long CACHE_TTL_MILLIS = 60_000;

    /** How long to suspend calls after a failed one. */
    private static final long FAILURE_BACKOFF_MILLIS = 15_000;

    private static final int PAGE_SIZE = 100;

    /**
     * Keycloak names its own client service accounts {@code service-account-<client>}; they are
     * machine identities and can maintain nothing.
     */
    private static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    private final RestClient restClient;

    /** Time source; a test seam, so cache expiry and backoff are testable without sleeping. */
    private final LongSupplier clock;

    private final String tokenEndpoint;
    private final String adminUsersEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final boolean enabled;

    private record CachedNames(List<String> usernames, long loadedAt) {
    }

    private volatile CachedNames cache;
    private volatile String accessToken;
    private volatile long tokenExpiresAt;

    /** Until this instant Keycloak is considered unreachable and no calls are attempted. */
    private volatile long unavailableUntil;

    // @Autowired is required here: the test-seam constructor below makes this a two-constructor
    // class, and Spring only picks one implicitly when there is exactly one.
    @Autowired
    public KeycloakUserDirectory(
            @Value("${spring.security.oauth2.client.provider.oidc.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.client.registration.oidc.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.oidc.client-secret}") String clientSecret,
            @Value("${catalog.keycloak.directory-enabled:true}") boolean enabled) {
        this(issuerUri, clientId, clientSecret, enabled, buildRestClient(), System::currentTimeMillis);
    }

    /** Test seam: lets tests supply a mock-bound {@link RestClient} and a controllable clock. */
    KeycloakUserDirectory(String issuerUri, String clientId, String clientSecret, boolean enabled,
                          RestClient restClient, LongSupplier clock) {
        this.restClient = restClient;
        this.clock = clock;
        this.tokenEndpoint = issuerUri + "/protocol/openid-connect/token";
        // http://host/realms/<realm> -> http://host/admin/realms/<realm>/users
        this.adminUsersEndpoint = issuerUri.replaceFirst("/realms/", "/admin/realms/") + "/users";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.enabled = enabled;
    }

    /**
     * Every human user of the realm, in the order Keycloak returns them. Service accounts are left
     * out. Empty when the directory is disabled or Keycloak cannot be reached and nothing was
     * cached - callers must treat an empty answer as "no addition", never as "no users exist".
     */
    public List<String> listUsernames() {
        if (!enabled) {
            return List.of();
        }
        CachedNames cached = cache;
        if (cached != null && cached.loadedAt() + CACHE_TTL_MILLIS > clock.getAsLong()) {
            return cached.usernames();
        }
        if (backingOff()) {
            return cached != null ? cached.usernames() : List.of();
        }
        try {
            List<String> usernames = new ArrayList<>();
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
                        .map(UserRepresentation::username)
                        .filter(username -> username != null && !username.isBlank())
                        .filter(username -> !username.startsWith(SERVICE_ACCOUNT_PREFIX))
                        .forEach(usernames::add);
                if (page.size() < PAGE_SIZE) {
                    break;
                }
            }
            List<String> result = List.copyOf(usernames);
            cache = new CachedNames(result, clock.getAsLong());
            return result;
        } catch (RestClientException | IllegalStateException e) {
            markUnavailable(e);
            return cached != null ? cached.usernames() : List.of();
        }
    }

    private boolean backingOff() {
        return unavailableUntil > clock.getAsLong();
    }

    /**
     * Records a failed call and suspends further ones for {@value #FAILURE_BACKOFF_MILLIS} ms, so a
     * dead Keycloak costs one connect timeout per window rather than one per request.
     */
    private void markUnavailable(Exception e) {
        unavailableUntil = clock.getAsLong() + FAILURE_BACKOFF_MILLIS;
        log.warn("Keycloak admin API unavailable ({}); the maintainer list keeps to the users the"
                + " catalog itself knows for the next {} s", e.getMessage(), FAILURE_BACKOFF_MILLIS / 1000);
    }

    /** Short timeouts: a dead Keycloak must degrade the list, not hang the request. */
    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /** The service-account token, re-fetched only once the cached one is about to expire. */
    private synchronized String adminToken() {
        if (accessToken != null && tokenExpiresAt > clock.getAsLong()) {
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
            throw new IllegalStateException("Keycloak returned no service-account access token"
                    + " (is the client's service account enabled?)");
        }
        accessToken = token.access_token();
        // Renew slightly early so an almost-expired token is never sent.
        tokenExpiresAt = clock.getAsLong() + (token.expires_in() - 10) * 1000L;
        return accessToken;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(String access_token, long expires_in) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserRepresentation(String username) {
    }
}
