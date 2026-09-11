/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.evolveum.midpoint.integration.catalog.configuration.GraviteeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the Gravitee management API, which owns the API keys: the catalog has it mint one and
 * never sees the value again. One application per key, each with a single subscription.
 */
@Component
public class GraviteeClient {

    /** A key is minted while the user waits, so a stalled APIM must fail rather than hang. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final int HTTP_NOT_FOUND = 404;

    private static final Logger LOGGER = LoggerFactory.getLogger(GraviteeClient.class);

    private final GraviteeProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public GraviteeClient(GraviteeProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates the Gravitee application that carries one key; its name and description only keep the
     * Gravitee console readable.
     */
    public String createApplication(String username, String subject, String keyName)
            throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode()
                .put("name", "ic-" + username + "-" + keyName)
                .put("description", "Integration Catalog key \"" + keyName + "\" of " + username
                        + " (sub " + subject + ")");

        JsonNode created = send(post(properties.managementV1Base() + "/applications", body), "create the application");
        return requireText(created, "id", "application");
    }

    /**
     * Subscribes an application to the catalog's API-key plan; one subscription is one key.
     *
     * @return the subscription id
     */
    public String createSubscription(String applicationId) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode()
                .put("applicationId", applicationId)
                .put("planId", properties.planId());

        JsonNode created = send(
                post(properties.managementV2Base() + "/apis/" + properties.apiId() + "/subscriptions", body),
                "create the subscription");
        return requireText(created, "id", "subscription");
    }

    /** Reads the key a subscription produced - the one moment the catalog may hold the value. */
    public ApiKeyMaterial fetchApiKey(String subscriptionId) throws IOException, InterruptedException {
        JsonNode response = send(
                get(properties.managementV2Base() + "/apis/" + properties.apiId()
                        + "/subscriptions/" + subscriptionId + "/api-keys"),
                "read the API key");

        JsonNode keys = response.path("data");
        if (!keys.isArray() || keys.isEmpty()) {
            throw new IOException("Gravitee created the subscription but returned no API key for it.");
        }
        JsonNode key = keys.get(0);
        String value = key.path("key").asText(null);
        if (value == null || value.isBlank()) {
            throw new IOException("Gravitee returned an API key without a value.");
        }
        return new ApiKeyMaterial(key.path("id").asText(null), value);
    }

    /**
     * Sets when the subscription, and with it its key, stops working. Best-effort: the key already
     * exists, so a refusal is logged rather than losing the whole creation.
     */
    // The v1 endpoint taking epoch millis: v2 has no PUT here (405) and takes no ISO endingAt.
    public boolean expireSubscription(String subscriptionId, Instant expiresAt) {
        ObjectNode body = objectMapper.createObjectNode().put("ending_at", expiresAt.toEpochMilli());
        try {
            send(put(properties.managementV1Base() + "/apis/" + properties.apiId()
                    + "/subscriptions/" + subscriptionId, body), "set the expiration");
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Gravitee did not accept the expiration of subscription {}; the key does not expire.",
                    subscriptionId, e);
            return false;
        }
    }

    /**
     * Revokes the key by closing its subscription, which Gravitee marks revoked with it. A
     * subscription Gravitee no longer knows counts as closed - it cannot authenticate anything,
     * and refusing here would leave such a key un-revocable.
     *
     * @param subscriptionId the subscription behind the key
     */
    public void closeSubscription(String subscriptionId) throws IOException, InterruptedException {
        HttpRequest request = post(properties.managementV2Base() + "/apis/" + properties.apiId()
                + "/subscriptions/" + subscriptionId + "/_close", objectMapper.createObjectNode());
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == HTTP_NOT_FOUND) {
            LOGGER.warn("Gravitee does not know subscription {}; the key counts as revoked.", subscriptionId);
            return;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Gravitee refused to close the subscription (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
    }

    /**
     * Mints a new key on the subscription. Gravitee gives every key the subscription already had an
     * expiration two hours out, so callers can switch over without an outage.
     */
    public ApiKeyMaterial renewApiKey(String subscriptionId) throws IOException, InterruptedException {
        JsonNode key = send(post(subscriptionUrl(subscriptionId) + "/api-keys/_renew", objectMapper.createObjectNode()),
                "renew the API key");
        String value = key.path("key").asText(null);
        if (value == null || value.isBlank()) {
            throw new IOException("Gravitee renewed the API key but returned no value for it.");
        }
        return new ApiKeyMaterial(key.path("id").asText(null), value, instantOf(key, "expireAt"));
    }

    /** The keys of a subscription as Gravitee sees them now; their values are left out. */
    public List<ApiKeyState> listApiKeys(String subscriptionId) throws IOException, InterruptedException {
        JsonNode keys = send(get(subscriptionUrl(subscriptionId) + "/api-keys"), "read the API keys").path("data");
        List<ApiKeyState> states = new ArrayList<>();
        for (JsonNode key : keys) {
            states.add(new ApiKeyState(key.path("id").asText(null), instantOf(key, "expireAt"),
                    key.path("revoked").asBoolean(false)));
        }
        return states;
    }

    /**
     * Revokes one key of a subscription and leaves its other keys working. A key Gravitee no longer
     * knows counts as revoked, for the same reason as in {@link #closeSubscription(String)}.
     */
    public void revokeApiKey(String subscriptionId, String apiKeyId) throws IOException, InterruptedException {
        HttpRequest request = post(subscriptionUrl(subscriptionId) + "/api-keys/" + apiKeyId + "/_revoke",
                objectMapper.createObjectNode());
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == HTTP_NOT_FOUND) {
            LOGGER.warn("Gravitee does not know API key {}; it counts as revoked.", apiKeyId);
            return;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Gravitee refused to revoke the API key (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
    }

    /** The key as Gravitee minted it: its id, the value the user sees once, and its own expiration. */
    public record ApiKeyMaterial(String id, String value, Instant expireAt) {

        public ApiKeyMaterial(String id, String value) {
            this(id, value, null);
        }
    }

    /** One key of a subscription without its value; a null expiration means it does not expire. */
    public record ApiKeyState(String id, Instant expireAt, boolean revoked) {
    }

    private String subscriptionUrl(String subscriptionId) {
        return properties.managementV2Base() + "/apis/" + properties.apiId() + "/subscriptions/" + subscriptionId;
    }

    /** An ISO date-time field, or null when Gravitee left it out. */
    private static Instant instantOf(JsonNode node, String field) {
        String text = node.path(field).asText(null);
        return text == null || text.isBlank() ? null : OffsetDateTime.parse(text).toInstant();
    }

    private HttpRequest post(String url, ObjectNode body) throws IOException {
        return authorized(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest put(String url, ObjectNode body) throws IOException {
        return authorized(url)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest get(String url) {
        return authorized(url).GET().build();
    }

    private HttpRequest.Builder authorized(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + properties.token())
                .header("Accept", "application/json");
    }

    /** Sends the request and parses the body, turning a non-2xx into a readable failure. */
    private JsonNode send(HttpRequest request, String action) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Gravitee refused to " + action + " (HTTP " + response.statusCode()
                    + "): " + response.body());
        }
        return response.body() == null || response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
    }

    private static String requireText(JsonNode node, String field, String what) throws IOException {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IOException("Gravitee returned a " + what + " without an " + field + ": " + node);
        }
        return value;
    }
}
