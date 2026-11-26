package net.minetake.mcinsight.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Typed access to request parameters, identical for the loopback HTTP transport and the
 * multiplayer packet transport.
 *
 * <p>Both transports hand over the same string-keyed values, so a single parser decides what is
 * valid. Values are validated strictly: an unparsable number or a non-boolean is an error rather
 * than a silent fallback, because a silently substituted default answers a question the caller did
 * not ask.
 */
public final class Params {

    public static final Params EMPTY = new Params(Map.of());

    private final Map<String, String> values;

    private Params(Map<String, String> values) {
        this.values = values;
    }

    public static Params of(Map<String, String> values) {
        return values == null || values.isEmpty() ? EMPTY : new Params(values);
    }

    private static Params fromJson(JsonObject json) {
        if (json == null || json.isEmpty()) {
            return EMPTY;
        }
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (!value.isJsonPrimitive()) {
                throw new InsightException(
                    InsightError.INVALID_PARAMETER,
                    "Parameter '" + entry.getKey() + "' must be a primitive value."
                );
            }
            map.put(entry.getKey(), value.getAsString());
        }
        return of(map);
    }

    /** Parses an HTTP query string; malformed percent-encoding is rejected rather than ignored. */
    public static Params fromQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return EMPTY;
        }
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        int start = 0;
        while (start < rawQuery.length()) {
            int amp = rawQuery.indexOf('&', start);
            int end = amp >= 0 ? amp : rawQuery.length();
            if (end > start) {
                int eq = rawQuery.indexOf('=', start);
                String key;
                String value;
                if (eq >= 0 && eq < end) {
                    key = rawQuery.substring(start, eq);
                    value = rawQuery.substring(eq + 1, end);
                } else {
                    key = rawQuery.substring(start, end);
                    value = "";
                }
                map.put(decode(key), decode(value));
            }
            start = end + 1;
        }
        return of(map);
    }

    private static String decode(String raw) {
        if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) {
            return raw;
        }
        try {
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InsightException(
                InsightError.INVALID_QUERY,
                "Malformed URL-encoded query component: " + raw
            );
        }
    }

    public static Params fromJsonText(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return EMPTY;
        }
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(jsonText);
            if (!parsed.isJsonObject()) {
                throw new InsightException(InsightError.INVALID_PARAMETER, "Request parameters must be a JSON object.");
            }
            return fromJson(parsed.getAsJsonObject());
        } catch (InsightException e) {
            throw e;
        } catch (Exception e) {
            throw new InsightException(InsightError.INVALID_PARAMETER, "Request parameters are not valid JSON.");
        }
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values.keySet()));
    }

    /** Serializable form used when forwarding a request over the packet bridge. */
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, String> e : values.entrySet()) {
            obj.addProperty(e.getKey(), e.getValue());
        }
        return obj;
    }

    public void rejectUnknownKeys(Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new InsightException(
                    InsightError.INVALID_PARAMETER,
                    "Unknown parameter '" + key + "'. Allowed: " + String.join(", ", allowed) + "."
                );
            }
        }
    }

    private String rawOrNull(String key) {
        String value = values.get(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String string(String key) {
        return rawOrNull(key);
    }

    public String stringOr(String key, String fallback) {
        String value = rawOrNull(key);
        return value != null ? value : fallback;
    }

    public String required(String key, InsightError error) {
        String value = rawOrNull(key);
        if (value == null) {
            throw new InsightException(error, "Parameter '" + key + "' is required.");
        }
        return value;
    }

    public int intOr(String key, int fallback) {
        String value = rawOrNull(key);
        if (value == null) {
            return fallback;
        }
        return parseInt(key, value);
    }

    public int requiredInt(String key) {
        String value = required(key, InsightError.INVALID_PARAMETER);
        return parseInt(key, value);
    }

    /**
     * Reads a paginated limit against the endpoint's declared bounds. Out-of-range values are
     * rejected rather than clamped: clamping hides the mismatch between client expectations and
     * server capability and returns a page the caller did not ask for.
     */
    public int limit(int fallback, int max) {
        String value = rawOrNull("limit");
        int parsed = value == null ? fallback : parseInt("limit", value);
        if (parsed < 1 || parsed > max) {
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter 'limit' must be in 1.." + max + ", got: " + parsed + "."
            );
        }
        return parsed;
    }

    public int offset() {
        String value = rawOrNull("offset");
        int parsed = value == null ? 0 : parseInt("offset", value);
        if (parsed < 0) {
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter 'offset' must be >= 0, got: " + parsed + "."
            );
        }
        return parsed;
    }

    public boolean bool(String key, boolean fallback) {
        String value = rawOrNull(key);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new InsightException(
            InsightError.INVALID_PARAMETER,
            "Parameter '" + key + "' must be 'true' or 'false', got: " + value + "."
        );
    }

    public Double nullableDouble(String key) {
        String value = rawOrNull(key);
        if (value == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                throw new InsightException(
                    InsightError.INVALID_PARAMETER,
                    "Parameter '" + key + "' must be finite, got: " + value + "."
                );
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter '" + key + "' must be a number, got: " + value + "."
            );
        }
    }

    public double doubleOr(String key, double fallback) {
        Double value = nullableDouble(key);
        return value != null ? value : fallback;
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter '" + key + "' must be an integer, got: " + value + "."
            );
        }
    }
}
