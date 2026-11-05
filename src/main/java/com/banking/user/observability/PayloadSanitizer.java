package com.banking.user.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns requests, responses and messages into short JSON text that is safe to log:
 * secrets are replaced by ***, emails are shortened, and long text is cut.
 * It never throws. To hide another field, add its name (lower case, without - or _) to SECRET_FIELDS.
 */
@Component
public class PayloadSanitizer {

    public static final String MASK = "***";
    private static final int DEFAULT_MAX_LENGTH = 2048;
    private static final Pattern TRANSFER_ID = Pattern.compile("TXN-[A-Za-z0-9]{1,40}");

    private static final Set<String> SECRET_FIELDS = Set.of(
            "password", "passwordhash", "secret", "token", "accesstoken", "refreshtoken",
            "authorization", "otp", "encryptedcode", "encryptedtoken", "cryptokey", "apikey");
    private static final Set<String> EMAIL_FIELDS = Set.of("email", "useremail");

    private final ObjectMapper mapper;
    private final ObservabilityProperties properties;

    public PayloadSanitizer(ObjectMapper mapper, ObservabilityProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** One value (a response body, a return value) as sanitized JSON text. */
    public String describe(Object value) {
        if (!properties.logBodies()) {
            return "bodies not logged";
        }
        if (value == null) {
            return "null";
        }
        try {
            JsonNode tree = value instanceof JsonNode json ? json : mapper.valueToTree(value);
            return cut(mask(tree).toString());
        } catch (RuntimeException ex) {
            return "<unreadable " + value.getClass().getSimpleName() + ">";
        }
    }

    /** The arguments of a method call as one sanitized JSON object, keyed by parameter name. */
    public String describeArguments(String[] names, Object[] args) {
        if (!properties.logBodies()) {
            return "bodies not logged";
        }
        try {
            ObjectNode node = mapper.createObjectNode();
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null || isFrameworkType(arg)) {
                    continue;
                }
                String name = names != null && i < names.length && names[i] != null ? names[i] : "arg" + i;
                node.set(name, mapper.valueToTree(arg));
            }
            return cut(mask(node).toString());
        } catch (RuntimeException ex) {
            return "<unreadable arguments>";
        }
    }

    /** The transfer id inside a body, but only when it looks like one. */
    public String transferIdOf(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode node = value instanceof JsonNode json ? json : mapper.valueToTree(value);
            JsonNode id = node.get("transferId");
            return id != null && id.isTextual() && isTransferId(id.asText()) ? id.asText() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static boolean isTransferId(String text) {
        return text != null && TRANSFER_ID.matcher(text).matches();
    }

    public String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        return at <= 0 ? MASK : email.charAt(0) + MASK + email.substring(at);
    }

    private JsonNode mask(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = normalize(entry.getKey());
                JsonNode child = entry.getValue();
                if (SECRET_FIELDS.contains(name) && !child.isNull()) {
                    copy.put(entry.getKey(), MASK);
                } else if (EMAIL_FIELDS.contains(name) && child.isTextual()) {
                    copy.put(entry.getKey(), maskEmail(child.asText()));
                } else {
                    copy.set(entry.getKey(), mask(child));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(child -> copy.add(mask(child)));
            return copy;
        }
        return node;
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
    }

    private boolean isFrameworkType(Object arg) {
        if (arg instanceof byte[]) {
            return true;
        }
        String type = arg.getClass().getName();
        return type.startsWith("jakarta.servlet.")
                || type.startsWith("org.springframework.mock.web.")
                || type.startsWith("org.springframework.security.")
                || type.contains("BindingResult");
    }

    private String cut(String text) {
        int max = properties.maxBodyLength() > 0 ? properties.maxBodyLength() : DEFAULT_MAX_LENGTH;
        return text.length() <= max
                ? text
                : text.substring(0, max) + "...(" + (text.length() - max) + " more characters)";
    }
}