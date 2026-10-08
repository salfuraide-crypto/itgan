package itqan;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP plumbing: JSON API routing, file uploads, protected media streaming and the static web app. */
public final class Server implements HttpHandler {
    static final String COOKIE = "itqan_session";
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final Pattern MEDIA_NAME = Pattern.compile("[a-z0-9]{8,40}(\\.[a-z0-9]{1,5})?");
    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("avif", "image/avif");
        MIME.put("ico", "image/x-icon");
        MIME.put("mp4", "video/mp4");
        MIME.put("m4v", "video/mp4");
        MIME.put("webm", "video/webm");
        MIME.put("ogg", "video/ogg");
        MIME.put("ogv", "video/ogg");
        MIME.put("mov", "video/quicktime");
        MIME.put("mkv", "video/x-matroska");
        MIME.put("woff2", "font/woff2");
    }

    public interface Handler { Object handle(Req r) throws Exception; }

    public interface MediaGuard { boolean allowed(Map<String, Object> user, String fileName); }

    private static final class Route {
        final String method;
        final Pattern pattern;
        final String role;
        final boolean upload;
        final Handler handler;

        Route(String method, String path, String role, boolean upload, Handler handler) {
            this.method = method;
            this.pattern = Pattern.compile("^" + path.replace("{id}", "([A-Za-z0-9]+)") + "$");
            this.role = role;
            this.upload = upload;
            this.handler = handler;
        }
    }

    private final Store store;
    private final Path webDir;
    private final Path uploadDir;
    private final List<Route> routes = new ArrayList<>();
    private final R2 r2;
    private MediaGuard mediaGuard = (u, f) -> false;

    /** r2 is null when uploads stay on the local disk. */
    public Server(Store store, Path webDir, Path uploadDir, R2 r2) {
        this.store = store;
        this.webDir = webDir.toAbsolutePath().normalize();
        this.uploadDir = uploadDir.toAbsolutePath().normalize();
        this.r2 = r2;
    }

    /** Registers a JSON endpoint. role: null = public, "any" = signed in, otherwise the required role. */
    public void route(String method, String path, String role, Handler handler) {
        routes.add(new Route(method, path, role, false, handler));
    }

    /** Registers an endpoint whose request body is a raw file (query string carries the other fields). */
    public void upload(String path, String role, Handler handler) {
        routes.add(new Route("POST", path, role, true, handler));
    }

    public void mediaGuard(MediaGuard guard) { this.mediaGuard = guard; }

    public void start(String host, int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/", this);
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
    }

    @Override
    public void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path);
            else if (path.startsWith("/media/")) media(ex, path.substring("/media/".length()));
            else staticFile(ex, path);
        } catch (Throwable t) {
            t.printStackTrace();
            try { json(ex, 500, error("حدث خطأ غير متوقع في الخادم")); } catch (Throwable ignored) { /* response already started */ }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ API

    private void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        Route route = null;
        Matcher matcher = null;
        boolean pathKnown = false;
        for (Route r : routes) {
            Matcher m = r.pattern.matcher(path);
            if (!m.matches()) continue;
            pathKnown = true;
            if (r.method.equals(method)) { route = r; matcher = m; break; }
        }
        if (route == null) {
            json(ex, pathKnown ? 405 : 404, error("المسار غير موجود"));
            return;
        }
        // Custom header forces a CORS preflight, so other sites cannot forge state-changing requests.
        if (!method.equals("GET") && !"itqan".equals(ex.getRequestHeaders().getFirst("X-Requested-With"))) {
            json(ex, 403, error("طلب غير مسموح"));
            return;
        }

        Req req = new Req(ex);
        req.params = new String[matcher.groupCount()];
        for (int i = 0; i < req.params.length; i++) req.params[i] = matcher.group(i + 1);
        req.query = parseQuery(ex.getRequestURI().getRawQuery());
        req.token = cookie(ex, COOKIE);
        synchronized (store) { req.user = sessionUser(req.token); }

        if (route.role != null) {
            if (req.user == null) { json(ex, 401, error("يجب تسجيل الدخول أولًا")); return; }
            if (!route.role.equals("any") && !route.role.equals(req.user.get("role"))) {
                json(ex, 403, error("ليست لديك صلاحية للوصول إلى هذه الصفحة"));
                return;
            }
        }

        boolean inR2 = false;
        try {
            if (route.upload) {
                req.upload = receiveUpload(ex, req.q("name"));
                if (r2 != null && req.upload.size > 0) {
                    // Sent to R2 before the handler runs, so a saved record never points to a missing file.
                    try {
                        r2.put(req.upload.storedName(), req.upload.file, req.upload.mime);
                        inR2 = true;
                    } catch (IOException e) {
                        System.err.println(e.getMessage());
                        json(ex, 502, error("تعذر حفظ الملف في التخزين، حاول مرة أخرى"));
                        return;
                    }
                }
            } else if (!method.equals("GET")) req.body = readJsonBody(ex);

            String out;
            synchronized (store) {
                try {
                    Object result = route.handler.handle(req);
                    out = Json.stringify(result == null ? okBody() : result);
                } finally {
                    if (!method.equals("GET")) store.touch();
                    store.flush();
                }
            }
            if (req.setCookie != null) ex.getResponseHeaders().add("Set-Cookie", req.setCookie);
            json(ex, 200, out);
        } catch (ApiError e) {
            json(ex, e.status, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            json(ex, 400, error("بيانات الطلب غير صالحة"));
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            if (req.upload != null) {
                // With R2 the local copy was only a staging file; without it, the disk copy is the file.
                if (r2 != null || !req.upload.kept) Files.deleteIfExists(req.upload.file);
                if (inR2 && !req.upload.kept) {
                    try { r2.delete(req.upload.storedName()); } catch (IOException e) { System.err.println(e.getMessage()); }
                }
            }
        }
    }

    private Map<String, Object> sessionUser(String token) {
        if (token == null || token.isEmpty()) return null;
        Map<String, Object> session = store.find("sessions", token);
        return session == null ? null : store.find("users", String.valueOf(session.get("userId")));
    }

    private Req.Upload receiveUpload(HttpExchange ex, String originalName) throws IOException {
        Files.createDirectories(uploadDir);
        String ext = extension(originalName);
        Path target = uploadDir.resolve(Store.newId(20) + (ext.isEmpty() ? "" : "." + ext));
        long size;
        try (InputStream in = ex.getRequestBody()) {
            size = Files.copy(in, target);
        }
        String mime = ex.getRequestHeaders().getFirst("Content-Type");
        if (mime == null || mime.isEmpty() || mime.startsWith("application/octet-stream")) {
            mime = MIME.getOrDefault(ext, "application/octet-stream");
        }
        return new Req.Upload(target, originalName, mime.toLowerCase(Locale.ROOT), size);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_JSON_BYTES) throw new ApiError(413, "حجم الطلب كبير جدًا");
            }
        }
        String text = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new LinkedHashMap<>();
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map)) throw new ApiError(400, "بيانات الطلب غير صالحة");
        return (Map<String, Object>) parsed;
    }

    // ---------------------------------------------------------------- media

    private void media(HttpExchange ex, String name) throws IOException {
        if (!MEDIA_NAME.matcher(name).matches()) { plain(ex, 404, "Not found"); return; }
        boolean allowed;
        synchronized (store) {
            Map<String, Object> user = sessionUser(cookie(ex, COOKIE));
            allowed = user != null && mediaGuard.allowed(user, name);
        }
        if (!allowed) { plain(ex, 404, "Not found"); return; }
        Path file = uploadDir.resolve(name);
        if (Files.isRegularFile(file)) {
            sendFile(ex, file, "private, max-age=3600", true);
        } else if (r2 != null) {
            // The browser fetches the file straight from R2 with a short-lived link (seeking in videos works there too).
            ex.getResponseHeaders().set("Location", r2.presignedGet(name, 6 * 3600));
            ex.getResponseHeaders().set("Cache-Control", "private, no-store");
            ex.sendResponseHeaders(302, -1);
        } else {
            plain(ex, 404, "Not found");
        }
    }

    // --------------------------------------------------------------- static

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) { plain(ex, 405, "Method not allowed"); return; }
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        Path file = webDir.resolve(rel).normalize();
        if (!file.startsWith(webDir)) { plain(ex, 404, "Not found"); return; }
        if (!Files.isRegularFile(file)) {
            if (extension(rel).isEmpty()) file = webDir.resolve("index.html");
            else { plain(ex, 404, "Not found"); return; }
        }
        sendFile(ex, file, "no-cache", false);
    }

    private static void sendFile(HttpExchange ex, Path file, String cacheControl, boolean ranges) throws IOException {
        long length = Files.size(file);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", MIME.getOrDefault(extension(file.getFileName().toString()), "application/octet-stream"));
        h.set("Cache-Control", cacheControl);
        h.set("X-Content-Type-Options", "nosniff");
        if (ranges) h.set("Accept-Ranges", "bytes");

        long start = 0;
        long end = length - 1;
        int status = 200;
        String range = ranges ? ex.getRequestHeaders().getFirst("Range") : null;
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            boolean valid = dash >= 0;
            try {
                if (valid && dash == 0) {
                    start = Math.max(0, length - Long.parseLong(spec.substring(1)));
                } else if (valid) {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) end = Math.min(length - 1, Long.parseLong(spec.substring(dash + 1)));
                }
            } catch (NumberFormatException e) {
                valid = false;
            }
            if (!valid || start > end || start >= length) {
                h.set("Content-Range", "bytes */" + length);
                ex.sendResponseHeaders(416, -1);
                return;
            }
            status = 206;
            h.set("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }

        long count = end - start + 1;
        if (ex.getRequestMethod().equals("HEAD") || count <= 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, count);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r"); OutputStream out = ex.getResponseBody()) {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long left = count;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            // The browser closed the connection mid-stream (normal when seeking in a video).
        }
    }

    // -------------------------------------------------------------- helpers

    static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,5}") ? ext : "";
    }

    static String mimeFor(String name) { return MIME.getOrDefault(extension(name), "application/octet-stream"); }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                // skip malformed pair
            }
        }
        return out;
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static Map<String, Object> okBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static String error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return Json.stringify(m);
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static void plain(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}
