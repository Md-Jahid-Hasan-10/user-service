package com.banking.user.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Tells the caller the trace id of its request, so a user can quote it and an operator can open it. */
@Component
public class TraceIdResponseFilter extends OncePerRequestFilter {

    private final CurrentTrace currentTrace;

    public TraceIdResponseFilter(CurrentTrace currentTrace) {
        this.currentTrace = currentTrace;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = currentTrace.traceId();
        if (traceId != null) {
            response.setHeader("X-Trace-Id", traceId);
        }
        chain.doFilter(request, response);
    }
}
