/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.framework;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Does in OpenProject what a reviewer does there: closes the review ticket, which the catalog
 * requires before approving. OpenProject's pages are not ours, so this uses its REST API.
 * <p>
 * The API key comes from the environment variable {@code GUI_OPENPROJECT_API_KEY}, not a system
 * property, because Surefire copies system properties into its reports.
 */
public final class SupportPortal {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url = System.getProperty("gui.openproject.url", "http://localhost:8090");
    private final String apiKey = System.getenv("GUI_OPENPROJECT_API_KEY");
    private final HttpClient http = HttpClient.newHttpClient();

    public void closeTicket(int workPackageId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Ticket #" + workPackageId + " must be closed before approving."
                    + " Set GUI_OPENPROJECT_API_KEY to an OpenProject API key, or run the backend with the portal off.");
        }
        JsonNode workPackage = send("GET", "/api/v3/work_packages/" + workPackageId, null);
        String body = JSON.createObjectNode()
                .put("lockVersion", workPackage.path("lockVersion").asInt())
                .set("_links", JSON.createObjectNode()
                        .set("status", JSON.createObjectNode().put("href", closedStatusHref())))
                .toString();
        send("PATCH", "/api/v3/work_packages/" + workPackageId, body);
    }

    /** "Closed" if the portal has it, otherwise its first status that counts as closed. */
    private String closedStatusHref() {
        JsonNode chosen = null;
        for (JsonNode status : send("GET", "/api/v3/statuses", null).path("_embedded").path("elements")) {
            if (status.path("isClosed").asBoolean()
                    && (chosen == null || "Closed".equalsIgnoreCase(status.path("name").asText()))) {
                chosen = status;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException("OpenProject at " + url + " has no closed status");
        }
        return chosen.path("_links").path("self").path("href").asText();
    }

    private JsonNode send(String method, String path, String body) {
        String credentials = Base64.getEncoder().encodeToString(("apikey:" + apiKey).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + path))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(method + " " + path + " returned " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " was interrupted", e);
        }
    }
}
