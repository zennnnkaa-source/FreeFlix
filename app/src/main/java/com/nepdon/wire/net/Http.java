package com.nepdon.wire.net;

import com.nepdon.wire.auth.AuthConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.CertificatePinner;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin OkHttp wrapper for the licensing endpoints. Release builds pin the VPS
 * self-signed cert by SPKI; debug builds (cleartext localhost) skip pinning.
 * All calls are synchronous — callers run them off the main thread.
 */
public final class Http {

    /** Simple result: HTTP status + raw body. */
    public static final class Resp {
        public final int code;
        public final String body;
        Resp(int code, String body) { this.code = code; this.body = body; }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    private static final MediaType JSON = MediaType.parse("application/json");
    private static volatile OkHttpClient client;

    private Http() {}

    private static OkHttpClient client() {
        if (client == null) {
            synchronized (Http.class) {
                if (client == null) {
                    OkHttpClient.Builder b = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .callTimeout(20, TimeUnit.SECONDS);
                    if (!AuthConfig.CERT_PIN.isEmpty()) {
                        HttpUrl u = HttpUrl.parse(AuthConfig.BASE_URL);
                        if (u != null) {
                            b.certificatePinner(new CertificatePinner.Builder()
                                    .add(u.host(), AuthConfig.CERT_PIN)
                                    .build());
                        }
                    }
                    client = b.build();
                }
            }
        }
        return client;
    }

    public static Resp postJson(String path, String json, String bearer) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(AuthConfig.BASE_URL + path)
                .post(RequestBody.create(json, JSON));
        if (bearer != null) rb.header("Authorization", "Bearer " + bearer);
        return exec(rb.build());
    }

    public static Resp get(String path) throws IOException {
        return exec(new Request.Builder().url(AuthConfig.BASE_URL + path).get().build());
    }

    /**
     * GET raw bytes with a HARD size ceiling (for the native-lib download).
     * Pinned/timeouts as above. Returns null on a non-2xx response, an
     * advertised Content-Length over {@code maxBytes}, or a body that streams
     * past {@code maxBytes}.
     *
     * <p>The ceiling is the point: the caller verifies a signature + SHA-256
     * over the result, but that happens only after the bytes are in memory, so
     * without a bound a hostile/compromised origin (or a TLS-pin bypass) could
     * answer with a multi-GB body and OOM the loader thread before the hash can
     * reject it. We refuse on Content-Length first, then enforce the cap while
     * streaming in case the header lies.</p>
     */
    public static byte[] getBytes(String path, long maxBytes) throws IOException {
        Request req = new Request.Builder().url(AuthConfig.BASE_URL + path).get().build();
        try (Response r = client().newCall(req).execute()) {
            if (r.code() < 200 || r.code() >= 300) return null;
            ResponseBody rb = r.body();
            if (rb == null) return null;
            long declared = rb.contentLength();            // -1 when unknown
            if (declared > maxBytes) return null;          // reject before buffering
            java.io.InputStream in = rb.byteStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                    declared > 0 && declared <= maxBytes ? (int) declared : 8192);
            byte[] chunk = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > maxBytes) return null;         // header lied; abort
                bos.write(chunk, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static Resp exec(Request req) throws IOException {
        try (Response r = client().newCall(req).execute()) {
            ResponseBody rb = r.body();
            return new Resp(r.code(), rb != null ? rb.string() : "");
        }
    }
}
