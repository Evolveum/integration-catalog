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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Talks to the support portal's REST API (OpenProject API v3). A singleton, unlike
 * {@link JenkinsClient} and {@link GithubClient}, because it may own a customised
 * {@link SSLContext}. Authentication is HTTP basic with the literal login {@code apikey} and an API
 * token, which is what OpenProject expects.
 */
@Component
public class OpenProjectClient {

    /** Fails the submission fast rather than holding the request open on an unreachable portal. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Far more files than a submission has, so {@link #listAttachments} never has to page. */
    private static final int ATTACHMENT_PAGE_SIZE = 200;

    private final OpenProjectProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;

    public OpenProjectClient(OpenProjectProperties properties) {
        this.properties = properties;
        this.client = buildClient(properties);
    }

    /**
     * Opens a work package in the configured project and returns its id.
     */
    public int createWorkPackage(String subject, String description) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("subject", subject);
        body.putObject("description")
                .put("format", "markdown")
                .put("raw", description);
        ObjectNode links = body.putObject("_links");
        links.putObject("type").put("href", "/api/v3/types/" + properties.typeId());
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
     * Attaches a file to a work package's Files tab, for content that would swamp the description.
     * The multipart body is assembled by hand, as {@link HttpRequest} has no support for it. The
     * portal notes the attachment in the activity itself, so no comment is needed.
     */
    public void addAttachment(int workPackageId, String fileName, byte[] content, String contentType)
            throws IOException, InterruptedException {
        String boundary = "catalog-" + UUID.randomUUID();
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("fileName", fileName);

        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"metadata\"\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + objectMapper.writeValueAsString(metadata) + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        String epilogue = "\r\n--" + boundary + "--\r\n";

        byte[] head = preamble.getBytes(StandardCharsets.UTF_8);
        byte[] tail = epilogue.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);

        HttpRequest request = authorized(properties.apiBase() + "/work_packages/" + workPackageId + "/attachments")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessful(response)) {
            String detail = "Support portal rejected attachment '" + fileName + "' on work package "
                    + workPackageId + " (HTTP " + response.statusCode() + "): " + response.body();
            if (isTooLarge(response.statusCode(), response.body())) {
                throw new AttachmentTooLargeException(detail);
            }
            throw new IOException(detail);
        }
    }

    /**
     * Thrown when the portal will not take a file however often it is offered, because the file is
     * larger than the attachment size limit it or its proxy enforces.
     *
     * <p>Its own type so a caller can tell this apart from the portal being unreachable, which looks
     * the same from the outside and is the opposite case: one is worth retrying, this one never is.
     */
    public static class AttachmentTooLargeException extends IOException {

        public AttachmentTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Whether a refused upload was refused for the file's size.
     */
    static boolean isTooLarge(int statusCode, String body) {
        if (statusCode == 413) {
            return true;
        }
        if (statusCode != 422 || body == null) {
            return false;
        }
        String lowerCase = body.toLowerCase(Locale.ROOT);
        return lowerCase.contains("too large") || lowerCase.contains("maximum size")
                || lowerCase.contains("file size");
    }

    /**
     * One file on a work package, as far as a caller replacing files needs to know it.
     */
    public record Attachment(int id, String fileName, String digest, String authorHref) {
    }

    /**
     * Everything attached to a work package. Asks for one large page rather than paging, and reports
     * an overflow, since a caller replacing files would take a short list for the whole truth.
     */
    public List<Attachment> listAttachments(int workPackageId) throws IOException, InterruptedException {
        HttpRequest request = authorized(properties.apiBase() + "/work_packages/" + workPackageId
                + "/attachments?pageSize=" + ATTACHMENT_PAGE_SIZE)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessful(response)) {
            throw new IOException("Support portal could not be queried for the attachments of work package "
                    + workPackageId + " (HTTP " + response.statusCode() + "): " + response.body());
        }

        JsonNode collection = objectMapper.readTree(response.body());
        List<Attachment> attachments = new ArrayList<>();
        for (JsonNode element : collection.path("_embedded").path("elements")) {
            JsonNode id = element.path("id");
            if (!id.isInt()) {
                continue;
            }
            attachments.add(new Attachment(
                    id.intValue(),
                    element.path("fileName").asText(""),
                    element.path("digest").path("hash").asText(""),
                    element.path("_links").path("author").path("href").asText("")));
        }

        int total = collection.path("total").asInt(attachments.size());
        if (total > attachments.size()) {
            throw new IOException("Work package " + workPackageId + " has " + total + " attachments but only "
                    + attachments.size() + " were listed, so they cannot be read in full");
        }
        return attachments;
    }

    /**
     * Removes a file from the work package it is attached to. The portal notes the removal in the
     * work package's activity, so nothing disappears without a trace.
     */
    public boolean deleteAttachment(int attachmentId) throws IOException, InterruptedException {
        HttpRequest request = authorized(properties.apiBase() + "/attachments/" + attachmentId)
                .DELETE()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return false;
        }
        if (!isSuccessful(response)) {
            throw new IOException("Support portal refused to delete attachment " + attachmentId
                    + " (HTTP " + response.statusCode() + "): " + response.body());
        }
        return true;
    }

    /**
     * The portal user this client authenticates as, which is the author of everything the catalog has
     * uploaded. A caller replacing the catalog's own files needs it to leave everybody else's alone.
     */
    public OptionalInt findSelfId() throws IOException, InterruptedException {
        HttpRequest request = authorized(properties.apiBase() + "/users/me").GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessful(response)) {
            throw new IOException("Support portal could not be asked who the catalog signs in as (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        return idOf(objectMapper.readTree(response.body()));
    }

    /**
     * Appends a comment to an existing work package, for something that grows a review already under
     * way - a connector added to a revision that is still in front of a reviewer. Keeps one submission
     * to one conversation instead of opening a second work package beside it.
     */
    public void addComment(int workPackageId, String comment) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("comment").put("raw", comment);

        HttpRequest request = authorized(properties.apiBase() + "/work_packages/" + workPackageId + "/activities")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessful(response)) {
            throw new IOException("Support portal rejected a comment on work package " + workPackageId
                    + " (HTTP " + response.statusCode() + "): " + response.body());
        }
    }

    /**
     * Looks a portal user up by login, which is how {@link OpenProjectProperties#watchers()} names them.
     */
    public OptionalInt findUserIdByLogin(String login) throws IOException, InterruptedException {
        JsonNode users = findUsers("login", "=", login);
        if (users.isEmpty()) {
            return OptionalInt.empty();
        }
        return idOf(users.get(0));
    }

    /**
     * Looks a portal user up by e-mail address, which is what the catalog holds for a person.
     */
    public OptionalInt findUserIdByEmail(String email) throws IOException, InterruptedException {
        JsonNode users = findUsers("any_name_attribute", "~", email);
        OptionalInt found = OptionalInt.empty();
        for (JsonNode user : users) {
            if (!email.equalsIgnoreCase(user.path("email").asText(null))) {
                continue;
            }
            if (found.isPresent()) {
                return OptionalInt.empty();
            }
            found = idOf(user);
        }
        return found;
    }

    /** Runs one filter over {@code /users} and returns the matched elements, possibly none. */
    private JsonNode findUsers(String field, String operator, String value)
            throws IOException, InterruptedException {
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
     * Adds a user to a work package's watchers. A separate call because watchers exist only as this
     * sub-resource, not in the work package's writable schema; repeating it is harmless.
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
     */
    public Optional<String> readStatus(int workPackageId) throws IOException, InterruptedException {
        Optional<JsonNode> workPackage = readWorkPackage(workPackageId);
        if (workPackage.isEmpty()) {
            return Optional.empty();
        }
        // The status is a link, carrying the human-readable name in its title.
        JsonNode title = workPackage.get().path("_links").path("status").path("title");
        return title.isTextual() ? Optional.of(title.asText()) : Optional.empty();
    }

    /**
     * Reads a work package's current subject, so a caller rewriting it can keep whatever the portal
     * already calls the review.
     */
    public Optional<String> readSubject(int workPackageId) throws IOException, InterruptedException {
        return readWorkPackage(workPackageId)
                .map(workPackage -> workPackage.path("subject"))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText);
    }

    /**
     * Rewrites a work package's subject and description, for a submission edited while under review.
     * The portal keeps the superseded text in the activity, and guards the write with an optimistic
     * lock, so the current {@code lockVersion} is read first and a concurrent portal edit refuses it.
     */
    public Optional<String> updateWorkPackage(int workPackageId, String subject, String description)
            throws IOException, InterruptedException {
        Optional<JsonNode> current = readWorkPackage(workPackageId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        JsonNode lockVersion = current.get().path("lockVersion");
        if (!lockVersion.isInt()) {
            throw new IOException("Support portal returned work package " + workPackageId
                    + " without a lock version, so it cannot be updated");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("lockVersion", lockVersion.intValue());
        body.put("subject", subject);
        body.putObject("description")
                .put("format", "markdown")
                .put("raw", description);

        HttpRequest request = authorized(properties.apiBase() + "/work_packages/" + workPackageId)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (!isSuccessful(response)) {
            throw new IOException("Support portal rejected an update of work package " + workPackageId
                    + " (HTTP " + response.statusCode() + "): " + response.body());
        }
        return Optional.of(current.get().path("description").path("raw").asText(""));
    }

    /** One work package as the portal has it, or empty when it no longer exists. */
    private Optional<JsonNode> readWorkPackage(int workPackageId) throws IOException, InterruptedException {
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
        return Optional.of(objectMapper.readTree(response.body()));
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
