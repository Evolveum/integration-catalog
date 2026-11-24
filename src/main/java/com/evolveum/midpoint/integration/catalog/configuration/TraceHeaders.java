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
 * Writes the trace context of the current request (the {@code traceparent} header, or whatever
 * {@code management.tracing.propagation.type} selects) onto a response or an outgoing call, so one
 * request can be followed through the logs of the catalog and of the systems it calls. The calls
 * made with {@link java.net.http.HttpClient} are not instrumented by Spring, hence this by hand.
 * Outside a request - the retry job - there is no trace context and nothing is written.
 */
@Component
public class TraceHeaders {

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
            propagator.inject(context, response, (carrier, name, value) -> carrier.setHeader(name, value));
        }
    }
}
