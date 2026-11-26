/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.it;

import com.evolveum.midpoint.integration.catalog.configuration.TraceHeaders;
import com.evolveum.midpoint.integration.catalog.integration.GraviteeClient;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserDirectory;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Request tracing in the running application: the response carries the trace header, a caller's trace
 * id is continued, and the calls to Gravitee, the support portal and Keycloak carry it on. The external
 * systems are a local stub server that records what arrives.
 * <p>
 * The last two tests answer whether Spring would send the header on its own: it does not, neither on
 * {@link HttpClient} nor on a {@link RestClient} built without the observation registry. That is why
 * the clients go through {@link TraceHeaders} and the Keycloak directory registers the registry.
 */
class TracePropagationIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final String TRACE_HEADER = "traceparent";
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");

    /** A path that was called, and the trace header it arrived with (null when none). */
    private record Received(String path, String traceparent) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = startStub();

    @Autowired
    private Tracer tracer;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private GraviteeClient graviteeClient;

    @Autowired
    private OpenProjectClient openProjectClient;

    @DynamicPropertySource
    static void pointExternalSystemsAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("gravitee.managementUrl", TracePropagationIntegrationTest::stubUrl);
        registry.add("gravitee.token", () -> "stub-token");
        registry.add("openproject.url", TracePropagationIntegrationTest::stubUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void forgetEarlierCalls() {
        RECEIVED.clear();
    }

    @Test
    void responseCarriesANewTraceWhenTheCallerSendsNone() throws Exception {
        mockMvc.perform(get("/api/integration-method-types"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceHeaders.RESPONSE_HEADER, matchesPattern(TRACEPARENT)));
    }

    @Test
    void responseContinuesTheCallersTrace() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        mockMvc.perform(get("/api/integration-method-types")
                        .header(TRACE_HEADER, "00-" + traceId + "-00f067aa0ba902b7-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceHeaders.RESPONSE_HEADER,
                        matchesPattern("00-" + traceId + "-[0-9a-f]{16}-01")));
    }

    @Test
    void graviteeCallCarriesTheTrace() {
        String traceId = inSpan(() -> graviteeClient.listApiKeys(UUID.randomUUID()));

        assertThat(traceIdsReceivedAt("/apikeys")).containsOnly(traceId);
    }

    @Test
    void supportPortalCallCarriesTheTrace() {
        String traceId = inSpan(() -> openProjectClient.findUserIdByLogin("someone"));

        assertThat(traceIdsReceivedAt("/api/v3/users")).containsOnly(traceId);
    }

    @Test
    void keycloakCallsCarryTheTrace() {
        KeycloakUserDirectory directory = new KeycloakUserDirectory(stubUrl() + "/realms/test", "client",
                "secret", true, Duration.ofSeconds(2), Duration.ofSeconds(5), observationRegistry);

        String traceId = inSpan(directory::listUsernames);

        assertThat(traceIdsReceivedAt("/protocol/openid-connect/token")).containsOnly(traceId);
        assertThat(traceIdsReceivedAt("/admin/realms/test/users")).containsOnly(traceId);
    }

    @Test
    void springDoesNotAddTheTraceToAPlainHttpClient() {
        inSpan(() -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(stubUrl() + "/plain-http-client")).GET().build(),
                HttpResponse.BodyHandlers.ofString()));

        assertThat(RECEIVED).extracting(Received::traceparent).containsOnlyNulls();
    }

    @Test
    void springDoesNotAddTheTraceToARestClientWithoutTheObservationRegistry() {
        inSpan(() -> RestClient.builder().build().get()
                .uri(stubUrl() + "/plain-rest-client").retrieve().toBodilessEntity());

        assertThat(RECEIVED).extracting(Received::traceparent).containsOnlyNulls();
    }

    /** Runs the call as part of a trace, like code serving a request does, and returns its trace id. */
    private String inSpan(ThrowingCall call) {
        Span span = tracer.nextSpan().name("trace-propagation-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            call.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            span.end();
        }
        return span.context().traceId();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    /** The trace ids the stub received on paths containing the fragment; fails when nothing came. */
    private static List<String> traceIdsReceivedAt(String pathFragment) {
        List<String> traceparents = RECEIVED.stream()
                .filter(received -> received.path().contains(pathFragment))
                .map(Received::traceparent)
                .toList();
        assertThat(traceparents).as("calls to " + pathFragment).isNotEmpty();
        return traceparents.stream().map(TracePropagationIntegrationTest::traceIdOf).toList();
    }

    private static String traceIdOf(String traceparent) {
        assertThat(traceparent).as(TRACE_HEADER).isNotNull();
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        assertThat(matcher.matches()).as("W3C traceparent: " + traceparent).isTrue();
        return matcher.group(1);
    }

    private static String stubUrl() {
        return "http://localhost:" + STUB.getAddress().getPort();
    }

    /** Answers every call with an empty result of the shape the caller expects. */
    private static HttpServer startStub() {
        Map<String, String> bodies = Map.of(
                "/protocol/openid-connect/token", "{\"access_token\": \"token\", \"expires_in\": 300}",
                "/api/v3/users", "{\"_embedded\": {\"elements\": []}}");
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                RECEIVED.add(new Received(path, exchange.getRequestHeaders().getFirst(TRACE_HEADER)));
                byte[] body = bodies.entrySet().stream()
                        .filter(entry -> path.endsWith(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse("[]")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
