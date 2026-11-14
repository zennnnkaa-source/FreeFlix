package com.nepdon.wire.dev;

import android.content.Context;
import android.os.Environment;

import com.nepdon.wire.net.IpFirewall;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Append-only text log. Tries public storage first, then app external dir.
 * Paths tried:
 *   1) /storage/emulated/0/NepdonWire/watch.txt
 *   2) Context.getExternalFilesDir(null)/NepdonWire/watch.txt
 *   3) Context.getFilesDir()/NepdonWire/watch.txt
 *
 * Verbose helpers: conn / block / allow / event / packet.
 */
public final class FileWatchLog {

    private static final String FOLDER = "NepdonWire";
    private static final String NAME = "watch.txt";
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final AtomicReference<Context> APP = new AtomicReference<Context>();
    private static volatile String lastPath = "";
    private static volatile String lastError = "";

    private FileWatchLog() { }

    /** Call once from Application / Activity / Service with app context. */
    public static void init(Context ctx) {
        if (ctx == null) return;
        APP.compareAndSet(null, ctx.getApplicationContext());
        line("BOOT", "FileWatchLog init");
    }

    public static String lastPath() { return lastPath; }
    public static String lastError() { return lastError; }

    public static void line(String tag, String msg) {
        write(tag, msg);
    }

    /** TCP/UDP attempt: always verbose. */
    public static void conn(String proto, String action, int ipHost, int port,
                            String reason, String extra) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(proto).append(' ').append(action)
          .append(" ip=").append(IpFirewall.formatIp(ipHost))
          .append(':').append(port);
        if (reason != null && !reason.isEmpty()) sb.append(" reason=").append(reason);
        if (extra != null && !extra.isEmpty()) sb.append(' ').append(extra);
        write("CONN", sb.toString());
    }

    public static void block(String proto, int ipHost, int port, String reason) {
        conn(proto, "BLOCK", ipHost, port, reason, "firewall=on");
    }

    public static void allow(String proto, int ipHost, int port) {
        conn(proto, "ALLOW", ipHost, port, null, null);
    }

    public static void event(String tag, String k1, Object v1, String k2, Object v2) {
        StringBuilder sb = new StringBuilder(64);
        if (k1 != null) sb.append(k1).append('=').append(v1);
        if (k2 != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(k2).append('=').append(v2);
        }
        write(tag, sb.toString());
    }

    public static void packet(String tag, String dir, int cmd, int len, int dip, int dp, int sp) {
        write(tag, dir + " cmd=" + cmd + " len=" + len
                + " dip=" + IpFirewall.formatIp(dip) + ":" + dp + " sp=" + sp);
    }

    private static void write(String tag, String msg) {
        String row = FMT.format(new Date()) + " [" + tag + "] " + msg + "\n";
        byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            File f = resolveFile();
            if (f == null) return;
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write(bytes);
                fos.flush();
                fos.close();
                lastPath = f.getAbsolutePath();
                lastError = "";
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
    }

    private static File resolveFile() {
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root != null) {
                File dir = new File(root, FOLDER);
                if (dir.exists() || dir.mkdirs()) {
                    File f = new File(dir, NAME);
                    if (canWrite(f)) return f;
                }
            }
        } catch (Throwable ignored) { }

        Context ctx = APP.get();
        if (ctx != null) {
            try {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) {
                    File dir = new File(ext, FOLDER);
                    if (dir.exists() || dir.mkdirs()) {
                        File f = new File(dir, NAME);
                        if (canWrite(f)) return f;
                    }
                }
            } catch (Throwable ignored) { }
            try {
                File dir = new File(ctx.getFilesDir(), FOLDER);
                if (dir.exists() || dir.mkdirs()) {
                    File f = new File(dir, NAME);
                    if (canWrite(f)) return f;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static boolean canWrite(File f) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.close();
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }
}
