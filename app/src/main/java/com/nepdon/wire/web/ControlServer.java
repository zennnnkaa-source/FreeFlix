package com.nepdon.wire.web;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.nepdon.wire.config.plugin.Feature;
import com.nepdon.wire.config.plugin.Features;
import com.nepdon.wire.config.plugin.Setting;
import com.nepdon.wire.dev.FileWatchLog;
import com.nepdon.wire.mod.ModConfig;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Localhost control page for every switch in the app. Bound to 127.0.0.1 only.
 * Each request must carry the token the app shows, which stops a web page open
 * in the same browser from driving it, and the Host check rejects rebinding.
 * Lives inside the VPN service process, so it is up exactly while the tunnel is.
 * Changes run on the main thread, because Feature callbacks are UI-thread only.
 */
public final class ControlServer {

    public static final int PORT = 8765;
    private static final int MAX_BODY = 4096;
    private static final String JSON = "application/json; charset=utf-8";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ServerSocket server;
    private static volatile String token = "";
    private static volatile Context appCtx;

    private interface Op {
        String run();
    }

    private ControlServer() { }

    public static String token() {
        return server == null ? "" : token;
    }

    public static synchronized void start(Context ctx) {
        if (server != null) return;
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            appCtx = ctx.getApplicationContext();
            token = hex.toString();
            server = ss;
        } catch (IOException e) {
            FileWatchLog.line("WEB", "bind failed: " + e);
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "web-ctl");
        t.setDaemon(true);
        t.start();
        FileWatchLog.line("WEB", "listening 127.0.0.1:" + PORT);
    }

    public static synchronized void stop() {
        ServerSocket ss = server;
        server = null;
        token = "";
        if (ss == null) return;
        try {
            ss.close();
        } catch (IOException ignored) {
        }
    }

    private static void acceptLoop() {
        while (true) {
            ServerSocket ss = server;
            if (ss == null) return;
            try {
                handle(ss.accept());
            } catch (IOException e) {
                if (server == null) return;
            }
        }
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String first = in.readLine();
            if (first == null) return;
            String[] rl = first.split(" ");
            if (rl.length < 2) {
                reply(s, 400, TEXT, "bad request");
                return;
            }
            Map<String, String> hd = new HashMap<String, String>();
            String line;
            while ((line = in.readLine()) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) hd.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            String host = hd.get("host");
            if (host == null || !(host.equals("127.0.0.1:" + PORT) || host.equals("localhost:" + PORT))) {
                reply(s, 403, TEXT, "forbidden");
                return;
            }
            String method = rl[0];
            String target = rl[1];
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            Map<String, String> p = parseForm(query);
            if ("POST".equals(method)) {
                String cl = hd.get("content-length");
                int len = cl == null ? 0 : Math.max(0, Math.min(MAX_BODY, Integer.parseInt(cl)));
                char[] buf = new char[len];
                int got = 0;
                while (got < len) {
                    int n = in.read(buf, got, len - got);
                    if (n < 0) break;
                    got += n;
                }
                p.putAll(parseForm(new String(buf, 0, got)));
            }
            String given = "/".equals(path) ? p.get("t") : hd.get("x-token");
            if (!sameToken(given)) {
                reply(s, 403, TEXT, "forbidden");
                return;
            }
            if ("GET".equals(method) && "/".equals(path)) {
                reply(s, 200, HTML, readAsset("control.html"));
            } else if ("GET".equals(method) && "/api/state".equals(path)) {
                reply(s, 200, JSON, stateJson());
            } else if ("POST".equals(method) && "/api/feature".equals(path)) {
                reply(s, 200, JSON, setFeature(p));
            } else if ("POST".equals(method) && "/api/setting".equals(path)) {
                reply(s, 200, JSON, setSetting(p));
            } else if ("POST".equals(method) && "/api/mod".equals(path)) {
                reply(s, 200, JSON, setMod(p));
            } else {
                reply(s, 404, TEXT, "not found");
            }
        } catch (Exception e) {
            FileWatchLog.line("WEB", "request failed: " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean sameToken(String given) {
        String want = token;
        if (given == null || want.length() == 0 || given.length() != want.length()) return false;
        int d = 0;
        for (int i = 0; i < want.length(); i++) d |= given.charAt(i) ^ want.charAt(i);
        return d == 0;
    }

    private static void reply(Socket s, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes("US-ASCII"));
        o.write(b);
        o.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            default: return "Error";
        }
    }

    private static String readAsset(String name) throws IOException {
        InputStream in = appCtx.getAssets().open(name);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static Map<String, String> parseForm(String s) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        if (s == null || s.length() == 0) return m;
        for (String pair : s.split("&")) {
            int e = pair.indexOf('=');
            String k = e >= 0 ? pair.substring(0, e) : pair;
            String v = e >= 0 ? pair.substring(e + 1) : "";
            m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
        }
        return m;
    }

    private static boolean isTrue(String v) {
        return "1".equals(v) || "true".equals(v) || "on".equals(v);
    }

    private static int clampPort(int p) {
        return p <= 0 ? 0 : Math.max(1024, Math.min(65535, p));
    }

    private static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String result(String err) {
        return err == null ? "{\"ok\":true}" : "{\"ok\":false,\"error\":" + js(err) + "}";
    }

    private static String stateJson() {
        StringBuilder b = new StringBuilder("{\"features\":[");
        Feature[] fs = Features.all();
        for (int i = 0; i < fs.length; i++) {
            Feature f = fs[i];
            Setting.Bool en = f.enabledSetting();
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(js(f.id()))
                    .append(",\"title\":").append(js(f.title()))
                    .append(",\"summary\":").append(js(f.summary()))
                    .append(",\"toggleable\":").append(en != null)
                    .append(",\"enabled\":").append(Features.isEnabled(f))
                    .append(",\"settings\":[");
            boolean first = true;
            for (Setting s : Features.settingsOf(f)) {
                if (en != null && en.key.equals(s.key)) continue;
                if (!first) b.append(',');
                first = false;
                b.append(settingJson(s));
            }
            b.append("]}");
        }
        b.append("],\"mods\":{")
                .append("\"auto_freeze\":").append(ModConfig.AUTO_FREEZE)
                .append(",\"freeze_on_fire\":").append(ModConfig.FREEZE_ON_FIRE)
                .append(",\"freeze_on_scope\":").append(ModConfig.FREEZE_ON_SCOPE)
                .append(",\"firewall\":").append(ModConfig.FIREWALL)
                .append(",\"domain\":").append(ModConfig.DOMAIN)
                .append(",\"relay_port\":").append(ModConfig.RELAY_PORT)
                .append("}}");
        return b.toString();
    }

    private static String settingJson(Setting s) {
        StringBuilder b = new StringBuilder("{\"key\":").append(js(s.key))
                .append(",\"title\":").append(js(s.title));
        if (s instanceof Setting.Bool) {
            b.append(",\"type\":\"bool\",\"value\":").append(((Setting.Bool) s).get());
        } else if (s instanceof Setting.Num) {
            Setting.Num n = (Setting.Num) s;
            b.append(",\"type\":\"num\",\"value\":").append(n.get())
                    .append(",\"min\":").append(n.min)
                    .append(",\"max\":").append(n.max)
                    .append(",\"step\":").append(n.step);
        } else if (s instanceof Setting.Int) {
            Setting.Int n = (Setting.Int) s;
            b.append(",\"type\":\"int\",\"value\":").append(n.get())
                    .append(",\"min\":").append(n.min)
                    .append(",\"max\":").append(n.max)
                    .append(",\"step\":").append(n.step);
        } else if (s instanceof Setting.Choice) {
            Setting.Choice c = (Setting.Choice) s;
            b.append(",\"type\":\"choice\",\"value\":").append(c.get()).append(",\"labels\":[");
            for (int i = 0; i < c.labels.length; i++) {
                if (i > 0) b.append(',');
                b.append(js(c.labels[i]));
            }
            b.append(']');
        } else {
            b.append(",\"type\":\"unknown\"");
        }
        return b.append('}').toString();
    }

    private static Setting findSetting(String key) {
        for (Setting s : Features.settings()) {
            if (s.key.equals(key)) return s;
        }
        return null;
    }

    private static Feature ownerOf(Setting s) {
        for (Feature f : Features.all()) {
            if (f.title().equals(s.section)) return f;
        }
        return null;
    }

    private static String setFeature(final Map<String, String> p) {
        final String id = p.get("id");
        final boolean on = isTrue(p.get("on"));
        if (id == null) return result("missing id");
        return result(onMain(new Op() {
            public String run() {
                Feature f = Features.byId(id);
                if (f == null) return "unknown feature";
                if (f.enabledSetting() == null) return "not toggleable";
                Features.setEnabled(f, on);
                return null;
            }
        }));
    }

    private static String setSetting(final Map<String, String> p) {
        final String key = p.get("key");
        final String raw = p.get("value");
        if (key == null || raw == null) return result("missing key or value");
        return result(onMain(new Op() {
            public String run() {
                Setting target = findSetting(key);
                if (target == null) return "unknown setting";
                if (target instanceof Setting.Bool) {
                    ((Setting.Bool) target).set(isTrue(raw));
                } else if (target instanceof Setting.Num) {
                    Setting.Num n = (Setting.Num) target;
                    n.ref.set(n.clamp(Float.parseFloat(raw)));
                } else if (target instanceof Setting.Int) {
                    Setting.Int n = (Setting.Int) target;
                    n.ref.set(n.clamp(Integer.parseInt(raw)));
                } else if (target instanceof Setting.Choice) {
                    Setting.Choice c = (Setting.Choice) target;
                    c.ref.set(c.clamp(Integer.parseInt(raw)));
                } else {
                    return "unsupported setting";
                }
                return Features.commit(ownerOf(target), target);
            }
        }));
    }

    private static String setMod(final Map<String, String> p) {
        final String name = p.get("name");
        final String raw = p.get("value");
        if (name == null || raw == null) return result("missing name or value");
        final Context ctx = appCtx;
        final boolean on = isTrue(raw);
        return result(onMain(new Op() {
            public String run() {
                if ("auto_freeze".equals(name)) ModConfig.setAutoFreeze(ctx, on);
                else if ("freeze_on_fire".equals(name)) ModConfig.setFreezeOnFire(ctx, on);
                else if ("freeze_on_scope".equals(name)) ModConfig.setFreezeOnScope(ctx, on);
                else if ("firewall".equals(name)) ModConfig.setFirewall(ctx, on);
                else if ("domain".equals(name)) ModConfig.setDomain(ctx, on);
                else if ("relay_port".equals(name)) ModConfig.setRelayPort(ctx, clampPort(Integer.parseInt(raw)));
                else return "unknown switch";
                return null;
            }
        }));
    }

    private static String onMain(final Op op) {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] out = new String[1];
        MAIN.post(new Runnable() {
            public void run() {
                try {
                    out[0] = op.run();
                } catch (Throwable t) {
                    out[0] = "error: " + t;
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            if (!done.await(3, TimeUnit.SECONDS)) return "timeout";
        } catch (InterruptedException e) {
            return "interrupted";
        }
        return out[0];
    }
}
