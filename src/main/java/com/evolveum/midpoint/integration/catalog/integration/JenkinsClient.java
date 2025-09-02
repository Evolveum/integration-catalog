/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.evolveum.midpoint.integration.catalog.configure.JenkinsProperties;

import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;

public class JenkinsClient {

    private final JenkinsProperties properties;
    private final HttpClient client;

    public JenkinsClient(JenkinsProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newHttpClient();
    }

    public String triggerJob(String jobName, Map<String, String> parameters) throws IOException, InterruptedException {
        String jobUrl = String.format("/job/%s/buildWithParameters", jobName);

        URI uri = UriComponentsBuilder.fromUriString(properties.url()).path(jobUrl)
                .queryParams(MultiValueMap.fromSingleValue(parameters))
                .build().toUri();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", basicAuthHeader());

        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.noBody()).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        return "RESPONSE_CODE: " + response.statusCode() + ", RESPONSE_BODY: " + response.body();
    }

    private String basicAuthHeader() {
        String auth = properties.username() + ":" + properties.apiToken();
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes());
    }
}
