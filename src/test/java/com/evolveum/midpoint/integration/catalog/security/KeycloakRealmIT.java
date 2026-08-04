/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.evolveum.midpoint.integration.catalog.security.KeycloakUserService.KeycloakOrganization;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserService.KeycloakUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link KeycloakUserService} against a <b>real</b> Keycloak (the same 26.2 image the
 * {@code keycloak_for_auth} compose file uses) with our actual realm export imported. This
 * is the only test layer that validates what the mocked tests must assume:
 * <ul>
 * <li>the realm export (incl. the {@code organizations} array with members resolved by
 *     username) still imports into the current Keycloak version,</li>
 * <li>the service account's {@code view-users} + {@code manage-realm} roles really are
 *     sufficient for the user and organization endpoints,</li>
 * <li>the Admin API response shapes still match our JSON bindings,</li>
 * <li>the {@code organization} token claim produced by the built-in client scope still has
 *     a shape {@code AuthService} can parse,</li>
 * <li>an organization rename leaves the alias — our stable identifier — untouched.</li>
 * </ul>
 * A failure here after a Keycloak image bump means the upgrade broke one of these contracts.
 * <p>
 * <b>Opt-in:</b> needs Docker and ~30–60 s, so it only runs with the system property
 * {@code -Dit.keycloak=true} (in IntelliJ: run configuration → VM options). It is also
 * skipped automatically when Docker is not available.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "it.keycloak", matches = "true",
        disabledReason = "Real-Keycloak integration test; enable with -Dit.keycloak=true (needs Docker)")
@Testcontainers(disabledWithoutDocker = true)
class KeycloakRealmIT {

    private static final String REALM = "integration-catalog";
    private static final String CLIENT_ID = "integration-catalog";
    private static final String CLIENT_SECRET = "FCPyoevkTqLJq8vx9N9qbtcMQJFej146";

    @Container
    private static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>("quay.io/keycloak/keycloak:26.2")
                    .withCommand("start-dev", "--import-realm")
                    .withCopyFileToContainer(realmExport(),
                            "/opt/keycloak/data/import/integration-catalog-realm.json")
                    .withExposedPorts(8080)
                    // The realm endpoint answers 200 only once the import has succeeded.
                    .waitingFor(Wait.forHttp("/realms/" + REALM).forPort(8080)
                            .withStartupTimeout(Duration.ofMinutes(3)));

    private final RestClient rest = RestClient.create();
    private final ObjectMapper json = new ObjectMapper();

    /** Controllable "current time" for the service under test, so cache TTLs can be expired. */
    private long now;
    private KeycloakUserService service;

    private static MountableFile realmExport() {
        Path export = Path.of("keycloak_for_auth", "import", "integration-catalog-realm.json");
        if (!Files.exists(export)) {
            throw new IllegalStateException("Realm export not found at " + export.toAbsolutePath()
                    + " — run this test with the project root as the working directory");
        }
        return MountableFile.forHostPath(export);
    }

    private static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080)
                + "/realms/" + REALM;
    }

    private static String adminOrganizationsUrl() {
        return issuer().replaceFirst("/realms/", "/admin/realms/") + "/organizations";
    }

    @BeforeEach
    void setUp() {
        now = 0;
        service = new KeycloakUserService(issuer(), CLIENT_ID, CLIENT_SECRET,
                RestClient.create(), () -> now);
    }

    @Test
    void importedRealmAnswersUserAndOrganizationLookups() {
        assertEquals(Map.of("evolveum", "Evolveum", "acme", "Acme co."),
                service.listOrganizations().stream()
                        .collect(Collectors.toMap(KeycloakOrganization::alias, KeycloakOrganization::name)));

        // Membership comes from the realm export's organizations array (members are
        // referenced by username there) — this proves that import format still works.
        assertEquals(Optional.of(new KeycloakUser("u1", "OrganizationContributor", "acme", "Acme co.")),
                service.findUser("u1"));
        assertEquals(Optional.of(new KeycloakUser("kcuser", "Superuser", "evolveum", "Evolveum")),
                service.findUser("kcuser"));
        assertEquals(Optional.of(new KeycloakUser("u3", "IndividualContributor", null, null)),
                service.findUser("u3"));

        assertEquals(Set.of("kcuser", "u1", "u2", "u3", "u4", "u5"),
                service.listUsers().stream().map(KeycloakUser::username).collect(Collectors.toSet()));
        assertEquals(List.of("u1", "u4"),
                service.findUsersByOrganization("acme").stream()
                        .map(KeycloakUser::username).sorted().toList());
    }

    @Test
    void loginTokenCarriesParseableOrganizationClaim() throws Exception {
        // Log in as u1 the way the application does (same client, same scopes), then look at
        // the claims the way AuthService does: ID token merged with the userinfo response.
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "password");
        form.add("username", "u1");
        form.add("password", "u1");
        form.add("scope", "openid profile email organization");
        Map<String, Object> token = postToken(form);

        Map<String, Object> claims = new HashMap<>(jwtPayload((String) token.get("id_token")));
        claims.putAll(userinfo((String) token.get("access_token")));

        assertEquals("u1", claims.get("preferred_username"));
        assertTrue(organizationAliases(claims.get("organization")).contains("acme"),
                "organization claim should carry the org alias; was: " + claims.get("organization"));
    }

    @Test
    void organizationRenameKeepsAliasStable() {
        String adminToken = serviceAccountToken();
        String acmeId = organizationId(adminToken, "acme");

        // Prime the caches with the original name.
        assertEquals("Acme co.", service.findOrganizationByAlias("acme").orElseThrow().name());
        try {
            setOrganizationName(adminToken, acmeId, "Acme Corporation");
            now += 61_000;

            KeycloakOrganization renamed = service.findOrganizationByAlias("acme").orElseThrow();
            assertEquals("acme", renamed.alias());
            assertEquals("Acme Corporation", renamed.name());
            assertEquals("Acme Corporation",
                    service.findUser("u1").orElseThrow().organizationName());
        } finally {
            setOrganizationName(adminToken, acmeId, "Acme co.");
        }
    }

    // ---- Keycloak HTTP helpers ----

    private MultiValueMap<String, String> clientForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", CLIENT_ID);
        form.add("client_secret", CLIENT_SECRET);
        return form;
    }

    private Map<String, Object> postToken(MultiValueMap<String, String> form) {
        Map<String, Object> token = rest.post()
                .uri(issuer() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertNotNull(token);
        return token;
    }

    private String serviceAccountToken() {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "client_credentials");
        return (String) postToken(form).get("access_token");
    }

    private Map<String, Object> userinfo(String accessToken) {
        Map<String, Object> claims = rest.get()
                .uri(issuer() + "/protocol/openid-connect/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        return claims != null ? claims : Map.of();
    }

    private String organizationId(String adminToken, String alias) {
        List<Map<String, Object>> organizations = rest.get()
                .uri(adminOrganizationsUrl())
                .header("Authorization", "Bearer " + adminToken)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
        assertNotNull(organizations);
        return organizations.stream()
                .filter(o -> alias.equals(o.get("alias")))
                .map(o -> (String) o.get("id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No organization with alias " + alias));
    }

    private void setOrganizationName(String adminToken, String id, String name) {
        Map<String, Object> organization = rest.get()
                .uri(adminOrganizationsUrl() + "/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertNotNull(organization);
        organization.put("name", name);
        rest.put()
                .uri(adminOrganizationsUrl() + "/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(organization)
                .retrieve()
                .toBodilessEntity();
    }

    // ---- claim helpers ----

    private Map<String, Object> jwtPayload(String jwt) throws Exception {
        byte[] payload = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        return json.readValue(payload, new TypeReference<Map<String, Object>>() {
        });
    }

    /** Mirrors AuthService's parsing: the claim is either a list of aliases or a map keyed by alias. */
    private static List<String> organizationAliases(Object claim) {
        if (claim instanceof Map<?, ?> byAlias) {
            return byAlias.keySet().stream().map(String::valueOf).toList();
        }
        if (claim instanceof List<?> aliases) {
            return aliases.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
