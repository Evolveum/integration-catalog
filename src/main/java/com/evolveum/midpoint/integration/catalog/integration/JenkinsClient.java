/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.configuration.TraceHeaders;

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
    private final TraceHeaders traceHeaders;

    public JenkinsClient(JenkinsProperties properties, TraceHeaders traceHeaders) {
        this.properties = properties;
        this.client = HttpClient.newHttpClient();
        this.traceHeaders = traceHeaders;
    }

    public HttpResponse<String> triggerJob(Map<String, String> parameters) throws IOException, InterruptedException {
        String jobUrl = String.format("/job/%s/buildWithParameters", properties.jobName());

        URI uri = UriComponentsBuilder.fromUriString(properties.url()).path(jobUrl)
                .queryParams(MultiValueMap.fromSingleValue(parameters))
                .build().toUri();

        HttpRequest.Builder requestBuilder = traceHeaders.addTo(HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", basicAuthHeader()));

        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.noBody()).build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String basicAuthHeader() {
        String auth = properties.username() + ":" + properties.apiToken();
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
    }
}
