/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Talks to the support portal's REST API (OpenProject API v3).
 *
 * <p>Unlike {@link JenkinsClient} and {@link GithubClient}, which are constructed per call, this
 * is a singleton: it may own a customised {@link SSLContext} and there is no reason to rebuild
 * that on every submission.
 *
 * <p>Authentication is HTTP basic. OpenProject rejects a real login over basic auth and expects
 * the literal user name {@code apikey} with an API token as the password; both come from
 * configuration, so a portal with different conventions needs no change here.
 */
@Component
public class OpenProjectClient {

    /** Fails the submission fast rather than holding the request open on an unreachable portal. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final OpenProjectProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;

    public OpenProjectClient(OpenProjectProperties properties) {
        this.properties = properties;
        this.client = buildClient(properties);
    }

    /**
     * Opens a work package in the configured project and returns its id.
     *
     * @param subject     work package subject
     * @param description body, interpreted as markdown by the portal
     * @return the new work package's id
     * @throws IOException if the portal answers with anything other than a 2xx
     */
    public int createWorkPackage(String subject, String description) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("subject", subject);
        body.putObject("description")
                .put("format", "markdown")
                .put("raw", description);
        ObjectNode links = body.putObject("_links");
        links.putObject("type").put("href", "/api/v3/types/" + properties.typeId());
        // Set the opening status explicitly rather than relying on the project's default, so a
        // submission always lands in a known state regardless of how the project is configured.
        links.putObject("status").put("href", "/api/v3/statuses/" + properties.initialStatusId());

        HttpRequest request = authorized(properties.apiBase() + "/projects/" + properties.project() + "/work_packages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessful(response)) {
            throw new IOException("Support portal rejected the work package (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        JsonNode created = objectMapper.readTree(response.body());
        JsonNode id = created.get("id");
        if (id == null || !id.isInt()) {
            throw new IOException("Support portal returned a work package without a numeric id: " + response.body());
        }
        return id.intValue();
    }

    /**
     * Looks a portal user up by login, which is how {@link OpenProjectProperties#watchers()} names
     * them - a login is exact and is what whoever configures the catalog can see in the portal.
     * People the catalog knows only from its own database are found by
     * {@link #findUserIdByEmail(String)} instead.
     *
     * @return the user's numeric id, or empty when the portal knows no such login
     * @throws IOException if the portal answers with anything other than a 2xx. Listing users is an
     * administrative call, so a service account without that privilege lands here rather than on an
     * empty result.
     */
    public OptionalInt findUserIdByLogin(String login) throws IOException, InterruptedException {
        JsonNode users = findUsers("login", "=", login);
        if (users.isEmpty()) {
            return OptionalInt.empty();
        }
        return idOf(users.get(0));
    }

    /**
     * Looks a portal user up by e-mail address, which is what the catalog knows about the people
     * named on a submission - {@code catalog_users.email} and {@code organizations.email}.
     *
     * <p>There is no filter on the address itself; the portal answers {@code "Filters Email filter
     * does not exist"}. What does work is {@code any_name_attribute}, which searches the address
     * along with the login and the names, but only as a substring - a search for
     * {@code "@example.com"} returns everybody. The address is therefore compared here as well, and
     * only an exact, unambiguous hit counts.
     *
     * @return the user's numeric id, or empty when no user carries exactly this address
     * @throws IOException if the portal answers with anything other than a 2xx. Listing users is an
     * administrative call, so a service account without that privilege lands here rather than on an
     * empty result.
     */
    public OptionalInt findUserIdByEmail(String email) throws IOException, InterruptedException {
        JsonNode users = findUsers("any_name_attribute", "~", email);
        OptionalInt found = OptionalInt.empty();
        for (JsonNode user : users) {
            if (!email.equalsIgnoreCase(user.path("email").asText(null))) {
                continue;
            }
            if (found.isPresent()) {
                // Two accounts on one address: the portal allows it, and picking either would be a
                // guess about who the submission's author actually is.
                return OptionalInt.empty();
            }
            found = idOf(user);
        }
        return found;
    }

    /** Runs one filter over {@code /users} and returns the matched elements, possibly none. */
    private JsonNode findUsers(String field, String operator, String value)
            throws IOException, InterruptedException {
        // Built through Jackson rather than concatenated: the value is a configured login or an
        // address out of the database, and a quote in it would produce an unparseable filter.
        ObjectNode condition = objectMapper.createObjectNode();
        condition.put("operator", operator);
        condition.putArray("values").add(value);
        ObjectNode filter = objectMapper.createObjectNode();
        filter.set(field, condition);
        String filters = objectMapper.writeValueAsString(objectMapper.createArrayNode().add(filter));

        HttpRequest request = authorized(properties.apiBase() + "/users?filters="
                + URLEncoder.encode(filters, StandardCharsets.UTF_8))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessful(response)) {
            throw new IOException("Support portal could not be queried for user '" + value
                    + "' (HTTP " + response.statusCode() + "): " + response.body());
        }
        JsonNode elements = objectMapper.readTree(response.body()).path("_embedded").path("elements");
        return elements.isArray() ? elements : objectMapper.createArrayNode();
    }

    private static OptionalInt idOf(JsonNode user) {
        JsonNode id = user.path("id");
        return id.isInt() ? OptionalInt.of(id.intValue()) : OptionalInt.empty();
    }

    /**
     * Adds a user to a work package's watchers, so the portal notifies them of what happens to it.
     *
     * <p>A second call rather than part of {@link #createWorkPackage(String, String)}: watchers are
     * not part of the work package's writable schema, they only exist as this sub-resource.
     * Repeating it for a user who already watches is harmless.
     *
     * @throws IOException if the portal answers with anything other than a 2xx, which includes the
     * user not being allowed to see the work package - a watcher has to be able to read what they
     * are watching.
     */
    public void addWatcher(int workPackageId, int userId) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("user").put("href", "/api/v3/users/" + userId);

        HttpRequest request = authorized(properties.apiBase() + "/work_packages/" + workPackageId + "/watchers")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessful(response)) {
            throw new IOException("Support portal refused to add user " + userId + " as a watcher of "
                    + workPackageId + " (HTTP " + response.statusCode() + "): " + response.body());
        }
    }

    /**
     * Reads a work package's current status title, e.g. {@code "In progress"} or {@code "Resolved"}.
     *
     * @return the status title, or empty when the work package no longer exists (it may have been
     * deleted in the portal after the catalog recorded its id)
     */
    public Optional<String> readStatus(int workPackageId) throws IOException, InterruptedException {
        HttpRequest request = authorized(properties.apiBase() + "/work_packages/" + workPackageId)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (!isSuccessful(response)) {
            throw new IOException("Support portal could not be queried for work package " + workPackageId
                    + " (HTTP " + response.statusCode() + "): " + response.body());
        }
        // The status is a link, carrying the human-readable name in its title.
        JsonNode title = objectMapper.readTree(response.body()).path("_links").path("status").path("title");
        return title.isTextual() ? Optional.of(title.asText()) : Optional.empty();
    }

    private HttpRequest.Builder authorized(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", basicAuthHeader());
    }

    private String basicAuthHeader() {
        String auth = properties.username() + ":" + properties.password();
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isSuccessful(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static HttpClient buildClient(OpenProjectProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        if (properties.trustAllCertificates()) {
            builder.sslContext(trustAllContext());
            // Trusting the certificate is not enough on its own: the local test instance is
            // reached as "localhost" while its self-signed certificate names the container.
            // Clearing the identification algorithm turns that mismatch off too.
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm(null);
            builder.sslParameters(parameters);
        }
        return builder.build();
    }

    /**
     * Accepts any server certificate. Only ever reached when {@code openproject.trust-all-certificates}
     * is explicitly enabled, which exists for the self-signed local test instance.
     */
    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build a certificate-trusting SSL context", e);
        }
    }
}
