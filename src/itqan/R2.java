package itqan;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Keeps uploaded videos and images in Cloudflare R2 (or any S3-compatible storage), so they survive
 * redeploys on hosts whose disk is temporary. Requests are signed with AWS Signature Version 4.
 * Configured from R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY and R2_BUCKET.
 */
public final class R2 {
    private static final String REGION = "auto";
    private static final String SERVICE = "s3";
    private static final String UNSIGNED = "UNSIGNED-PAYLOAD";

    private final String scheme;
    private final String host;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;

    R2(String endpoint, String bucket, String accessKey, String secretKey) {
        URI uri = URI.create(endpoint.trim());
        if (uri.getHost() == null) throw new IllegalArgumentException("R2_ENDPOINT must look like https://<account>.r2.cloudflarestorage.com");
        this.scheme = uri.getScheme() == null ? "https" : uri.getScheme();
        this.host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        // The dashboard sometimes shows the endpoint with the bucket appended; accept that too.
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+|/+$", "");
        this.bucket = bucket == null || bucket.trim().isEmpty() ? path : bucket.trim();
        if (this.bucket.isEmpty()) throw new IllegalArgumentException("R2_BUCKET is missing");
        this.accessKey = accessKey.trim();
        this.secretKey = secretKey.trim();
    }

    /** R2 from the environment, or null when it is not configured (files then stay on the local disk). */
    static R2 fromEnv() {
        String endpoint = env("R2_ENDPOINT");
        String key = env("R2_ACCESS_KEY_ID");
        String secret = env("R2_SECRET_ACCESS_KEY");
        if (endpoint.isEmpty() && key.isEmpty() && secret.isEmpty()) return null;
        if (endpoint.isEmpty() || key.isEmpty() || secret.isEmpty()) {
            throw new IllegalStateException("R2 is partly configured: set R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY and R2_BUCKET");
        }
        return new R2(endpoint, env("R2_BUCKET"), key, secret);
    }

    String describe() { return "Cloudflare R2 bucket " + bucket; }

    /** Uploads a file under the given name. */
    void put(String name, Path file, String contentType) throws IOException {
        long size = Files.size(file);
        HttpURLConnection c = open("PUT", name, contentType);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(size);
        try (OutputStream out = c.getOutputStream()) {
            Files.copy(file, out);
        }
        expectOk(c, "upload " + name);
    }

    /** Deletes a file; a missing file is not an error. */
    void delete(String name) throws IOException {
        expectOk(open("DELETE", name, null), "delete " + name);
    }

    /** A temporary link that lets the browser read the file directly from R2. */
    String presignedGet(String name, int seconds) {
        return presign("GET", host, objectPath(name), seconds, new Date());
    }

    // ------------------------------------------------------------ signing

    private HttpURLConnection open(String method, String name, String contentType) throws IOException {
        String path = objectPath(name);
        String amzDate = stamp(new Date(), "yyyyMMdd'T'HHmmss'Z'");
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", UNSIGNED);
        headers.put("x-amz-date", amzDate);
        if (contentType != null) headers.put("content-type", contentType);
        String authorization = authorization(method, path, "", headers, UNSIGNED, amzDate);

        HttpURLConnection c = (HttpURLConnection) new URL(scheme + "://" + host + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (!h.getKey().equals("host")) c.setRequestProperty(h.getKey(), h.getValue());
        }
        c.setRequestProperty("Authorization", authorization);
        return c;
    }

    private static void expectOk(HttpURLConnection c, String what) throws IOException {
        int status = c.getResponseCode();
        if (status >= 200 && status < 300) {
            drain(c.getInputStream());
            return;
        }
        String body = "";
        InputStream err = c.getErrorStream();
        if (err != null) {
            byte[] b = readAll(err);
            body = new String(b, StandardCharsets.UTF_8);
        }
        throw new IOException("R2 could not " + what + " (HTTP " + status + "): " + body);
    }

    /** The Authorization header for a request signed with Signature Version 4. */
    String authorization(String method, String path, String query, TreeMap<String, String> headers,
                         String payloadHash, String amzDate) {
        return authorization(method, path, query, headers, payloadHash, amzDate, REGION);
    }

    String authorization(String method, String path, String query, TreeMap<String, String> headers,
                         String payloadHash, String amzDate, String region) {
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            canonicalHeaders.append(h.getKey()).append(':').append(h.getValue().trim()).append('\n');
            if (signedHeaders.length() > 0) signedHeaders.append(';');
            signedHeaders.append(h.getKey());
        }
        String canonical = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = amzDate.substring(0, 8) + "/" + region + "/" + SERVICE + "/aws4_request";
        String signature = sign(amzDate, scope, canonical, region);
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    /** A presigned URL (query-string authentication). Region is fixed to R2's "auto" unless given. */
    String presign(String method, String host, String path, int seconds, Date when) {
        return presign(method, host, path, seconds, when, REGION);
    }

    String presign(String method, String host, String path, int seconds, Date when, String region) {
        String amzDate = stamp(when, "yyyyMMdd'T'HHmmss'Z'");
        String scope = amzDate.substring(0, 8) + "/" + region + "/" + SERVICE + "/aws4_request";
        TreeMap<String, String> q = new TreeMap<>();
        q.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        q.put("X-Amz-Credential", accessKey + "/" + scope);
        q.put("X-Amz-Date", amzDate);
        q.put("X-Amz-Expires", String.valueOf(seconds));
        q.put("X-Amz-SignedHeaders", "host");
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(encode(e.getKey(), true)).append('=').append(encode(e.getValue(), true));
        }
        String canonical = method + "\n" + path + "\n" + query + "\nhost:" + host + "\n\nhost\n" + UNSIGNED;
        String signature = sign(amzDate, scope, canonical, region);
        return scheme + "://" + host + path + "?" + query + "&X-Amz-Signature=" + signature;
    }

    private String sign(String amzDate, String scope, String canonical, String region) {
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), amzDate.substring(0, 8));
        k = hmac(k, region);
        k = hmac(k, SERVICE);
        k = hmac(k, "aws4_request");
        return hex(hmac(k, toSign));
    }

    private String objectPath(String name) {
        return "/" + encode(bucket, false) + "/" + encode(name, false);
    }

    /** URI-encodes per SigV4: unreserved characters stay, everything else is %XX; '/' kept in paths. */
    static String encode(String s, boolean encodeSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (b & 0xff);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_' || ch == '.' || ch == '~' || (ch == '/' && !encodeSlash)) {
                out.append(ch);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    private static String stamp(Date when, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(when);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static void drain(InputStream in) throws IOException {
        try (InputStream i = in) { readAll(i); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return buf.toByteArray();
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }
}
