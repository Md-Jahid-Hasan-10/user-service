package com.banking.user.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

/** The trace id of the request that is being handled right now, or null when there is none. */
@Component
public class CurrentTrace {

    private final Tracer tracer;

    public CurrentTrace(Tracer tracer) {
        this.tracer = tracer;
    }

    public String traceId() {
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
