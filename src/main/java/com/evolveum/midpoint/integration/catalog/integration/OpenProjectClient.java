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
     * Attaches a text file to a work package, so it shows up under the work package's own Files tab.
     *
     * <p>For content that belongs with the review but would swamp the description if written into it -
     * an integration tutorial, which has no length limit. The reviewer gets it in the portal, next to
     * the submission, rather than having to open the catalog.
     *
     * <p>The request is multipart: a JSON {@code metadata} part naming the file, then the file itself.
     * {@link HttpRequest} has no multipart support, so the body is assembled here.
     *
     * <p>The portal records an attachment in the work package's activity by itself ("File x added as
     * attachment"), so a caller has no reason to also comment about it.
     *
     * @param fileName    name the attachment is stored and offered for download under
     * @param content     raw file content, assembled into the request as-is - the allowed uploads
     *                    include PDF, so this must stay bytes rather than text
     * @param contentType MIME type declared for the file part
     * @throws IOException if the portal answers with anything other than a 2xx, which includes the file
     * exceeding the instance's attachment size limit
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

        // Concatenated as bytes, not as a string: the content may be binary, and decoding it to text
        // would corrupt it.
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
     *
     * <p>Two ways it arrives, because two things can enforce a limit: a proxy in front of the portal
     * cuts the request off with {@code 413} before the portal sees it, while the portal itself
     * answers {@code 422} and says so in the body. The 422 is read by its wording, which is the only
     * thing distinguishing it from the other constraint violations that share its error identifier -
     * so a portal answering in a language this does not recognise is simply treated as a failure
     * worth retrying, as it was before.
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
     *
     * @param digest     md5 of the stored content, which the portal computes on upload. Lets a caller
     *                   tell a file it has already uploaded from one whose content has since changed,
     *                   without downloading it back.
     * @param authorHref who uploaded it, as an API link. The only way to tell the catalog's own
     *                   uploads from something a reviewer attached by hand.
     */
    public record Attachment(int id, String fileName, String digest, String authorHref) {
    }

    /**
     * Everything attached to a work package.
     *
     * <p>Asks for a large page rather than paging: a submission's files are a tutorial and a handful
     * of samples. An instance holding more than fits in one page is reported, because a caller that
     * replaces files would take the short list for the whole truth.
     *
     * @throws IOException if the portal answers with anything other than a 2xx
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

        // Against what came back rather than against the page size asked for, so an instance that
        // ignores the parameter is caught as well.
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
     *
     * @return {@code false} when the attachment is already gone
     * @throws IOException if the portal answers with anything other than a 2xx
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
     *
     * @return the id, or empty when the portal answers without one
     * @throws IOException if the portal answers with anything other than a 2xx
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
     *
     * @param comment body, interpreted as markdown by the portal like a work package description
     * @throws IOException if the portal answers with anything other than a 2xx, which includes the
     * work package having been deleted in the portal after the catalog recorded its id
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
     * named on a submission - {@code catalog_users.email}.
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
     *
     * @return the subject, or empty when the work package no longer exists
     */
    public Optional<String> readSubject(int workPackageId) throws IOException, InterruptedException {
        return readWorkPackage(workPackageId)
                .map(workPackage -> workPackage.path("subject"))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText);
    }

    /**
     * Rewrites an existing work package's subject and description, for a submission edited while it is
     * still under review: what the reviewer reads has to be the submission as it stands now, not as it
     * was first sent. The portal keeps the superseded text in the work package's activity, so the
     * change is visible to everyone watching rather than silently swapped in.
     *
     * <p>The portal guards work packages with an optimistic lock, so the current {@code lockVersion} is
     * read first and sent back with the change; a concurrent edit in the portal makes that stale and
     * the write is refused rather than overwriting somebody.
     *
     * @return the description this call replaced, so a caller can say what the edit changed rather
     * than leaving the reviewer to spot it; empty when the work package no longer exists in the portal
     * @throws IOException if the portal answers with anything other than a 2xx
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
        // The raw markdown, which is what was written here in the first place - the portal also keeps
        // an html rendering of it, and comparing that would compare its formatting rather than the text.
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
