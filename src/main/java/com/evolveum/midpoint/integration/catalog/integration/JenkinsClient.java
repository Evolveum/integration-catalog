/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;

import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public class JenkinsClient {

    private final JenkinsProperties properties;
    private final HttpClient client;

    public JenkinsClient(JenkinsProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newHttpClient();
    }

    public HttpResponse<String> triggerJob(String jobName, Map<String, String> parameters) throws IOException, InterruptedException {
        String jobUrl = String.format("/job/%s/buildWithParameters", jobName);

        URI uri = UriComponentsBuilder.fromUriString(properties.url()).path(jobUrl)
                .queryParams(MultiValueMap.fromSingleValue(parameters))
                .build().toUri();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", basicAuthHeader());

        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.noBody()).build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String basicAuthHeader() {
        String auth = properties.username() + ":" + properties.apiToken();
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
    }
}
