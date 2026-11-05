package com.banking.user.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.event.Level;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Records every REST request and its response: metrics always, one sanitized log line when the level allows. */
@Aspect
@Component
public class RestApiAspect {

    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");
    private static final int MAX_PATH_LENGTH = 200;
    private static final int MAX_ERROR_LENGTH = 300;

    private final PayloadSanitizer sanitizer;
    private final TrafficLog traffic;
    private final MeterRegistry meters;
    private final BoundedTags tags;

    public RestApiAspect(PayloadSanitizer sanitizer, TrafficLog traffic, MeterRegistry meters, BoundedTags tags) {
        this.sanitizer = sanitizer;
        this.traffic = traffic;
        this.meters = meters;
        this.tags = tags;
    }

    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        Object result = null;
        Throwable failure = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            report(pjp, result, failure, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private void report(ProceedingJoinPoint pjp, Object result, Throwable failure, Duration elapsed) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            String controller = signature.getDeclaringType().getSimpleName();
            String operation = signature.getName();

            HttpServletRequest request = currentRequest();
            String httpMethod = request == null ? "UNKNOWN" : request.getMethod();
            boolean read = "GET".equals(httpMethod) || "HEAD".equals(httpMethod);

            String outcome;
            String status = "-";
            Object body = null;
            if (failure != null) {
                outcome = "exception";
            } else if (result instanceof ResponseEntity<?> entity) {
                int code = entity.getStatusCode().value();
                status = String.valueOf(code);
                body = entity.getBody();
                outcome = code >= 500 ? "server_error" : code >= 400 ? "client_error" : "success";
            } else {
                status = "200";
                body = result;
                outcome = "success";
            }
            String exception = failure == null ? "none" : tags.limit("exception", failure.getClass().getSimpleName());

            Timer.builder("api.calls")
                    .tag("controller", controller)
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .tag("exception", exception)
                    .register(meters)
                    .record(elapsed);

            Level level = switch (outcome) {
                case "exception", "server_error" -> Level.WARN;
                case "client_error" -> Level.INFO;
                default -> read ? Level.DEBUG : Level.INFO;
            };
            if (!traffic.enabled(level)) {
                return;
            }

            Object[] args = pjp.getArgs();
            String[] names = signature.getParameterNames();
            boolean sensitive = isSensitive(signature);

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event", "http.request");
            fields.put("op", controller + "." + operation);
            fields.put("httpMethod", httpMethod);
            String path = request == null ? null : request.getRequestURI();
            fields.put("path", path == null ? null : limit(path, MAX_PATH_LENGTH));
            fields.put("httpStatus", status);
            fields.put("outcome", outcome);
            fields.put("durationMs", String.valueOf(elapsed.toMillis()));
            fields.put("userId", userIdOf(request));
            fields.put("transferId", transferIdOf(names, args, body));
            fields.put("request", sensitive ? "hidden (sensitive)" : sanitizer.describeArguments(names, args));
            fields.put("response", sensitive ? "hidden (sensitive)" : failure != null
                    ? "error: " + failure.getClass().getSimpleName() + ": " + limit(failure.getMessage(), MAX_ERROR_LENGTH)
                    : sanitizer.describe(body));

            traffic.log(level, httpMethod + " " + controller + "." + operation + " -> " + status
                    + " in " + elapsed.toMillis() + " ms", fields);
        } catch (RuntimeException ex) {
            // Recording must never change the outcome of a request.
        }
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }

    private boolean isSensitive(MethodSignature signature) {
        return signature.getDeclaringType().isAnnotationPresent(SensitiveBody.class)
                || (signature.getMethod() != null && signature.getMethod().isAnnotationPresent(SensitiveBody.class));
    }

    private String userIdOf(HttpServletRequest request) {
        String userId = request == null ? null : request.getHeader("X-User-Id");
        return userId != null && DIGITS.matcher(userId).matches() ? userId : null;
    }

    private String transferIdOf(String[] names, Object[] args, Object body) {
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                if ("transferId".equals(names[i]) && args[i] instanceof String text && PayloadSanitizer.isTransferId(text)) {
                    return text;
                }
            }
        }
        return sanitizer.transferIdOf(body);
    }

    private String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
