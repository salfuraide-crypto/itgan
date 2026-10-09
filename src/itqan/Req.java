package itqan;

import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One API request: path parameters, query, JSON body, the signed-in user and an optional uploaded file. */
public final class Req {
    final HttpExchange exchange;
    String[] params = new String[0];
    Map<String, String> query = new HashMap<>();
    Map<String, Object> body = new LinkedHashMap<>();
    Map<String, Object> user;
    String token;
    /** The visitor's address (the first X-Forwarded-For entry behind a proxy); used only for the security log. */
    String ip = "";
    Upload upload;
    String setCookie;

    Req(HttpExchange exchange) { this.exchange = exchange; }

    String param(int i) { return params[i]; }

    String userId() { return user == null ? "" : String.valueOf(user.get("id")); }

    /** Query parameter, trimmed. */
    String q(String key) {
        String v = query.get(key);
        return v == null ? "" : v.trim();
    }

    /** Body field as trimmed text. */
    String str(String key) { return raw(key).trim(); }

    /** Body field as text, untrimmed (passwords). */
    String raw(String key) {
        Object v = body.get(key);
        return v == null ? "" : v.toString();
    }

    boolean has(String key) { return body.containsKey(key); }

    long lng(String key, long fallback) {
        Object v = body.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (NumberFormatException ignored) { /* fallback */ }
        }
        return fallback;
    }

    double dbl(String key, double fallback) {
        Object v = body.get(key);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? fallback : d;
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    List<Object> list(String key) {
        Object v = body.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String key) {
        Object v = body.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    /** A file streamed to disk before the handler runs; deleted afterwards unless the handler keeps it. */
    static final class Upload {
        final Path file;
        final String name;
        final String mime;
        final long size;
        boolean kept;

        Upload(Path file, String name, String mime, long size) {
            this.file = file;
            this.name = name;
            this.mime = mime;
            this.size = size;
        }

        String storedName() { return file.getFileName().toString(); }
    }
}
