/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;

/**
 * Writes the trace context of the current request onto a response or an outgoing call, so one
 * request can be followed through the logs of the catalog and of the systems it calls.
 * <p>
 * Outgoing calls get the headers {@code management.tracing.propagation.type} selects ({@code
 * traceparent} for w3c). Spring does not add them to calls made with {@link java.net.http.HttpClient}
 * (TracePropagationIntegrationTest shows it), hence this by hand. The response header is named by
 * {@link #RESPONSE_HEADER} instead, so it can be renamed when infrastructure asks, without touching
 * what the catalog sends on. Outside a request - the retry job - there is no trace context and
 * nothing is written.
 */
@Component
public class TraceHeaders {

    /** Response header carrying the request's trace context, in the W3C traceparent format. */
    public static final String RESPONSE_HEADER = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public TraceHeaders(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this(tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    public TraceHeaders(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Writes nothing; for code constructed outside Spring, such as unit tests. */
    public static TraceHeaders none() {
        return new TraceHeaders(Tracer.NOOP, Propagator.NOOP);
    }

    public HttpRequest.Builder addTo(HttpRequest.Builder request) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context != null) {
            propagator.inject(context, request, (carrier, name, value) -> carrier.header(name, value));
        }
        return request;
    }

    public void addTo(HttpServletResponse response) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context != null) {
            response.setHeader(RESPONSE_HEADER, traceparent(context));
        }
    }

    /** version-traceid-spanid-flags, see https://www.w3.org/TR/trace-context/ */
    static String traceparent(TraceContext context) {
        return "00-" + context.traceId() + "-" + context.spanId() + "-"
                + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }
}
