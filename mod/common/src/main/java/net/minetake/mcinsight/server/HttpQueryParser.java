package net.minetake.mcinsight.server;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strict, unit-testable HTTP query parameter parser that fails fast on malformed encodings,
 * non-numeric values, NaN/Infinity, and invalid booleans.
 */
public final class HttpQueryParser {

    public static final class QueryParseException extends RuntimeException {
        private final String code;

        public QueryParseException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private HttpQueryParser() {}

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        int len = rawQuery.length();
        int start = 0;
        while (start < len) {
            int amp = rawQuery.indexOf('&', start);
            int end = amp >= 0 ? amp : len;
            if (end > start) {
                int eq = rawQuery.indexOf('=', start);
                String keyRaw;
                String valRaw;
                if (eq >= 0 && eq < end) {
                    keyRaw = rawQuery.substring(start, eq);
                    valRaw = rawQuery.substring(eq + 1, end);
                } else {
                    keyRaw = rawQuery.substring(start, end);
                    valRaw = "";
                }
                map.put(decodeComponent(keyRaw), decodeComponent(valRaw));
            }
            start = end + 1;
        }
        return map;
    }

    public static String decodeComponent(String raw) {
        if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) {
            return raw;
        }
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new QueryParseException("INVALID_QUERY", "Malformed URL-encoded query component: " + raw);
        }
    }

    public static int parseInt(String raw, int fallback, String paramName) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new QueryParseException(
                "INVALID_PARAMETER",
                "Parameter '" + paramName + "' must be an integer, got: " + raw
            );
        }
    }

    public static double parseDouble(String raw, double fallback, String paramName) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return parseFiniteDouble(raw, paramName);
    }

    public static Double parseNullableDouble(String raw, String paramName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseFiniteDouble(raw, paramName);
    }

    private static double parseFiniteDouble(String raw, String paramName) {
        try {
            double v = Double.parseDouble(raw.trim());
            if (!Double.isFinite(v)) {
                throw new QueryParseException(
                    "INVALID_PARAMETER",
                    "Parameter '" + paramName + "' must be finite, got: " + raw
                );
            }
            return v;
        } catch (NumberFormatException e) {
            throw new QueryParseException(
                "INVALID_PARAMETER",
                "Parameter '" + paramName + "' must be a number, got: " + raw
            );
        }
    }

    public static boolean parseBoolean(String raw, boolean fallback, String paramName) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        throw new QueryParseException(
            "INVALID_PARAMETER",
            "Parameter '" + paramName + "' must be 'true' or 'false', got: " + raw
        );
    }
}
