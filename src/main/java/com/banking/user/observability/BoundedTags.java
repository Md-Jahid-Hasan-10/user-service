package com.banking.user.observability;   // change the package for each service

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/** Keeps the number of different values of a metric label small: odd values become UNKNOWN, too many become OTHER. */
@Component
public class BoundedTags {

    private static final int MAX_VALUES_PER_GROUP = 50;
    private static final Pattern SAFE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,59}");

    private final ConcurrentMap<String, Set<String>> seen = new ConcurrentHashMap<>();

    public String limit(String group, String value) {
        if (value == null || !SAFE.matcher(value).matches()) {
            return "UNKNOWN";
        }
        Set<String> values = seen.computeIfAbsent(group, key -> ConcurrentHashMap.newKeySet());
        if (values.contains(value)) {
            return value;
        }
        if (values.size() >= MAX_VALUES_PER_GROUP) {
            return "OTHER";
        }
        values.add(value);
        return value;
    }
}