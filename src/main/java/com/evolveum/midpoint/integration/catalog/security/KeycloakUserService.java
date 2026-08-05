/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Read-only user directory backed by the Keycloak Admin REST API. Since the catalog keeps
 * no user, role or organization data of its own, every question about a user other than
 * the logged-in one (ownership checks, maintainer lists, organization members) is answered
 * here — the role from the {@code role} user attribute, the organization from Keycloak's
 * first-class <em>Organizations</em> (the org's immutable {@code alias} identifies it even
 * after a rename; the {@code name} is the display label).
 * <p>
 * Authentication uses the client-credentials grant of the same {@code integration-catalog}
 * client the login flow uses; its service account carries the {@code realm-management /
 * view-users} role for the user endpoints and {@code realm-management / manage-realm} for
 * the organization endpoints. Lookups are cached for a short time because list/detail
 * screens ask about the same few users repeatedly (one mapper pass can trigger a lookup
 * per row).
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

    /** A catalog-relevant view of a Keycloak user: effective role + organization membership. */
    public record KeycloakUser(String username, String role, String organizationAlias, String organizationName) {
    }

    /**
     * A Keycloak organization. The {@code alias} is immutable after creation and is the
     * stable identifier the catalog uses; the {@code name} may be renamed at any time.
     */
    public record KeycloakOrganization(String id, String alias, String name) {
    }

    private final RestClient restClient;
    /** Time source; a test seam so cache TTL and backoff expiry are testable without sleeping. */
    private final LongSupplier clock;
    private final String tokenEndpoint;
    private final String adminUsersEndpoint;
    private final String adminOrganizationsEndpoint;
    private final String clientId;
    private final String clientSecret;

    private record CachedUser(Optional<KeycloakUser> user, long loadedAt) {
    }

    private record CachedList(List<KeycloakUser> users, long loadedAt) {
    }

    /** Organizations plus a lowercase-username → organization membership index. */
    private record CachedOrgs(List<KeycloakOrganization> organizations,
            Map<String, KeycloakOrganization> byMemberUsername, long loadedAt) {
    }

    private final ConcurrentHashMap<String, CachedUser> userCache = new ConcurrentHashMap<>();
    private volatile CachedList listCache;
    private volatile CachedOrgs orgsCache;
    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    /** Until this instant Keycloak is considered unreachable and no calls are attempted. */
    private volatile long unavailableUntil;

    // @Autowired is required here: with the test-seam constructor below the class has two
    // constructors, and Spring only picks one implicitly when there is exactly one.
    @Autowired
    public KeycloakUserService(
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-secret}") String clientSecret) {
        this(issuerUri, clientId, clientSecret, buildRestClient(), System::currentTimeMillis);
    }

    /** Test seam: lets tests supply a mock-bound {@link RestClient} and a controllable clock. */
    KeycloakUserService(String issuerUri, String clientId, String clientSecret,
            RestClient restClient, LongSupplier clock) {
        this.restClient = restClient;
        this.clock = clock;
        this.tokenEndpoint = issuerUri + "/protocol/openid-connect/token";
        // http://host/realms/<realm> -> http://host/admin/realms/<realm>/users
        String adminRealmBase = issuerUri.replaceFirst("/realms/", "/admin/realms/");
        this.adminUsersEndpoint = adminRealmBase + "/users";
        this.adminOrganizationsEndpoint = adminRealmBase + "/organizations";
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
        if (cached != null && cached.loadedAt() + CACHE_TTL_MILLIS > clock.getAsLong()) {
            return cached.user();
        }
        if (backingOff()) {
            return cached != null ? cached.user() : Optional.empty();
        }
        Map<String, KeycloakOrganization> membership = organizationByMemberUsername();
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
                    .map(u -> u.toKeycloakUser(membership));
            userCache.put(key, new CachedUser(user, clock.getAsLong()));
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
        if (cached != null && cached.loadedAt() + CACHE_TTL_MILLIS > clock.getAsLong()) {
            return cached.users();
        }
        if (backingOff()) {
            return cached != null ? cached.users() : List.of();
        }
        Map<String, KeycloakOrganization> membership = organizationByMemberUsername();
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
                        .map(u -> u.toKeycloakUser(membership))
                        .forEach(users::add);
                if (page.size() < PAGE_SIZE) {
                    break;
                }
            }
            List<KeycloakUser> result = List.copyOf(users);
            listCache = new CachedList(result, clock.getAsLong());
            return result;
        } catch (RestClientException | IllegalStateException e) {
            markUnavailable(e);
            return cached != null ? cached.users() : List.of();
        }
    }

    /** Users belonging to the organization with the given alias. */
    public List<KeycloakUser> findUsersByOrganization(String organizationAlias) {
        if (organizationAlias == null || organizationAlias.isBlank()) {
            return List.of();
        }
        return listUsers().stream()
                .filter(u -> organizationAlias.trim().equalsIgnoreCase(u.organizationAlias()))
                .toList();
    }

    /** All organizations of the realm; empty while Keycloak is unreachable and nothing is cached. */
    public List<KeycloakOrganization> listOrganizations() {
        CachedOrgs orgs = organizations();
        return orgs != null ? orgs.organizations() : List.of();
    }

    /** Resolves an organization by its (immutable) alias, e.g. to display its current name. */
    public Optional<KeycloakOrganization> findOrganizationByAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        return listOrganizations().stream()
                .filter(o -> alias.trim().equalsIgnoreCase(o.alias()))
                .findFirst();
    }

    private Map<String, KeycloakOrganization> organizationByMemberUsername() {
        CachedOrgs orgs = organizations();
        return orgs != null ? orgs.byMemberUsername() : Map.of();
    }

    /**
     * Organizations and their memberships, fetched together: one organizations listing
     * plus one members listing per organization. Cached and fail-soft like the user
     * lookups; while unavailable users simply appear organization-less, which errs on
     * the deny side of the ownership checks.
     */
    private CachedOrgs organizations() {
        CachedOrgs cached = orgsCache;
        if (cached != null && cached.loadedAt() + CACHE_TTL_MILLIS > clock.getAsLong()) {
            return cached;
        }
        if (backingOff()) {
            return cached;
        }
        try {
            List<KeycloakOrganization> organizations = new ArrayList<>();
            Map<String, KeycloakOrganization> byMember = new HashMap<>();
            for (OrganizationRepresentation rep : pageThrough(adminOrganizationsEndpoint,
                    new ParameterizedTypeReference<List<OrganizationRepresentation>>() {
                    })) {
                KeycloakOrganization organization =
                        new KeycloakOrganization(rep.id(), rep.alias(), rep.name());
                organizations.add(organization);
                for (MemberRepresentation member : pageThrough(
                        adminOrganizationsEndpoint + "/" + rep.id() + "/members",
                        new ParameterizedTypeReference<List<MemberRepresentation>>() {
                        })) {
                    if (member.username() != null) {
                        byMember.put(member.username().toLowerCase(Locale.ROOT), organization);
                    }
                }
            }
            CachedOrgs result = new CachedOrgs(List.copyOf(organizations),
                    Map.copyOf(byMember), clock.getAsLong());
            orgsCache = result;
            return result;
        } catch (RestClientException | IllegalStateException e) {
            markUnavailable(e);
            return cached;
        }
    }

    private <T> List<T> pageThrough(String endpoint, ParameterizedTypeReference<List<T>> pageType) {
        List<T> all = new ArrayList<>();
        for (int first = 0; ; first += PAGE_SIZE) {
            List<T> page = restClient.get()
                    .uri(endpoint + "?first={first}&max={max}", first, PAGE_SIZE)
                    .header("Authorization", "Bearer " + adminToken())
                    .retrieve()
                    .body(pageType);
            if (page == null || page.isEmpty()) {
                break;
            }
            all.addAll(page);
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    private boolean backingOff() {
        return unavailableUntil > clock.getAsLong();
    }

    /**
     * Records a failed Keycloak call: suspends further calls for
     * {@value #FAILURE_BACKOFF_MILLIS} ms so page rendering does not wait for a connect
     * timeout per row. Logged once per backoff window (the failure itself is the trigger).
     */
    private void markUnavailable(Exception e) {
        unavailableUntil = clock.getAsLong() + FAILURE_BACKOFF_MILLIS;
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
            throw new IllegalStateException("Keycloak returned no service-account access token");
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
    private record UserRepresentation(String username, Map<String, List<String>> attributes) {

        KeycloakUser toKeycloakUser(Map<String, KeycloakOrganization> organizationByMemberUsername) {
            List<String> roles = attributes == null
                    ? List.of()
                    : attributes.getOrDefault("role", List.of());
            String effectiveRole = CatalogRole.BY_PRECEDENCE.stream()
                    .filter(roles::contains)
                    .findFirst()
                    .orElse(CatalogRole.READ_ONLY);
            KeycloakOrganization organization = username == null
                    ? null
                    : organizationByMemberUsername.get(username.toLowerCase(Locale.ROOT));
            return new KeycloakUser(username, effectiveRole,
                    organization != null ? organization.alias() : null,
                    organization != null ? organization.name() : null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrganizationRepresentation(String id, String alias, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MemberRepresentation(String username) {
    }
}
