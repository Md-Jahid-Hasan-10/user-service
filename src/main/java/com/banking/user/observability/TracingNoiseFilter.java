package com.banking.user.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/** Keeps the traces useful: nothing for Prometheus scrapes, Eureka heartbeats, or tasks that run every second. */
@Component
public class TracingNoiseFilter implements ObservationPredicate {

    @Override
    public boolean test(String name, Observation.Context context) {
        if ("tasks.scheduled.execution".equals(name)) {
            return false;
        }
        if (context instanceof ServerRequestObservationContext server && server.getCarrier() != null) {
            String path = server.getCarrier().getRequestURI();
            return path == null || !path.startsWith("/actuator");
        }
        if (context instanceof ClientRequestObservationContext client && client.getCarrier() != null) {
            ClientHttpRequest request = client.getCarrier();
            return request.getURI() == null
                    || request.getURI().getPath() == null
                    || !request.getURI().getPath().contains("/eureka/");
        }
        return true;
    }
}