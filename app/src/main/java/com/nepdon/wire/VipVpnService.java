package com.nepdon.wire;

import com.nepdon.wire.config.VpnActions;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class VipVpnService extends VpnService implements Runnable {

    public static final String ACTION_ACTIVATE   = VpnActions.ACTION_ACTIVATE;
    public static final String ACTION_DEACTIVATE = VpnActions.ACTION_DEACTIVATE;
    public static final String ACTION_FREEZE     = VpnActions.ACTION_FREEZE;
    public static final String ACTION_GHOST      = VpnActions.ACTION_GHOST;
    public static final String ACTION_JITTER     = VpnActions.ACTION_JITTER;
    public static final String ACTION_SET_APP    = VpnActions.ACTION_SET_APP;
    public static final String ACTION_STATS      = VpnActions.ACTION_STATS;
    public static final String ACTION_STOP       = VpnActions.ACTION_STOP;
    
    public static final String ACTION_UNFREEZE      = "com.nepdon.wire.UNFREEZE";
    public static final String ACTION_FREEZE_STATUS = "com.nepdon.wire.FREEZE_STATUS";
    public static final String EXTRA_FREEZE_DURATION = "freeze_duration"; // kept for compat, unused
    
    public static final String EXTRA_BYTES_IN    = "bytes_in";
    public static final String EXTRA_BYTES_OUT   = "bytes_out";
    public static final String EXTRA_FILTER_LOG  = "filter_log";
    public static final String EXTRA_LOSS        = "loss";
    public static final String EXTRA_MODE        = "mode";
    public static final String EXTRA_PING        = "ping";

    private static final int  SO_TO        = 0x7530;
    private static final int  TELE_BUF_MAX = 0x3e8;

    static final class SE {
        final DatagramSocket s;
        volatile long t;
        SE(DatagramSocket socket) { s = socket; t = System.currentTimeMillis(); }
        void touch() { t = System.currentTimeMillis(); }
    }

    static final class _TP {
        final byte[] data; final int dip, dp, sp;
        _TP(byte[] data, int dip, int dp, int sp) {
            this.data=data; this.dip=dip; this.dp=dp; this.sp=sp;
        }
    }

    private volatile boolean isRun    = false;
    private volatile boolean isActive = false;
    private volatile boolean isFreeze = false;
    private volatile boolean isGhost  = false;
    private volatile boolean isJitter = false;

    // no timer — freeze is purely manual on/off

    private final ConcurrentLinkedQueue _teleBuf = new ConcurrentLinkedQueue();
    private volatile boolean _teleReplaying = false;

    private String pkg = "";
    private final ConcurrentHashMap udpMap = new ConcurrentHashMap();
    private final AtomicLong bIn   = new AtomicLong();
    private final AtomicLong bOut  = new AtomicLong();
    private final AtomicLong pSent = new AtomicLong();
    private final AtomicLong pDrop = new AtomicLong();
    private volatile long rtt  = 40L;
    private final Random rng   = new Random();
    private volatile int tunIP = 0xC0A8010A;
    private volatile ParcelFileDescriptor tun;
    private volatile Thread vpnT;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundServiceNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();

        if (ACTION_SET_APP.equals(action)) {
            pkg = intent.getStringExtra(VpnActions.EXTRA_PACKAGE);
            if (pkg == null) pkg = "";
            // NEPDON V1 uses SET_APP as the start command.
            isActive = true;
        } else if (ACTION_STOP.equals(action)) {
            stop(); return START_NOT_STICKY;
        } else if (ACTION_FREEZE.equals(action)) {
            // NEPDON V1 sends one FREEZE action for each overlay tap.
            isFreeze = !isFreeze;
            notifyFreezeStatus(isFreeze);
        } else if (ACTION_UNFREEZE.equals(action)) {
            isFreeze = false;
            notifyFreezeStatus(false);
        } else if (ACTION_GHOST.equals(action)) {
            isGhost = !isGhost;
        } else if (ACTION_JITTER.equals(action)) {
            if (isJitter) {
                _teleReplaying = true;
                isJitter = false;
                _startTeleReplay();
            } else {
                _teleBuf.clear();
                _teleReplaying = false;
                isJitter = true;
            }
        } else if (ACTION_ACTIVATE.equals(action)) {
            isActive = true;
            publishState(true);
        } else if (ACTION_DEACTIVATE.equals(action)) {
            isActive = false;
            publishState(false);
        }

        if (!isRun) {
            isRun = true;
            vpnT = new Thread(this, "VPN");
            vpnT.start();
            startStats();
        }
        return START_STICKY;
    }

    private void notifyFreezeStatus(boolean state) {
        Intent intent = new Intent(ACTION_FREEZE_STATUS).setPackage(getPackageName());
        intent.putExtra("is_freeze", state);
        sendBroadcast(intent);
    }

    private void publishState(boolean running) {
        Intent state = new Intent(VpnActions.ACTION_STATE).setPackage(getPackageName());
        state.putExtra("engine_run", running);
        state.putExtra("engine_active", isActive);
        state.putExtra(VpnActions.EXTRA_MODE, isActive ? "ACTIVE" : "STANDBY");
        sendBroadcast(state);
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        if (intent != null && SERVICE_INTERFACE.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        return null;
    }

    private void startForegroundServiceNotification() {
        try {
            final String channel = "nepdon_vip_vpn";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            channel, "NEPDON WIRE VPN", NotificationManager.IMPORTANCE_LOW));
                }
            }
            Notification n = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, channel)
                            .setContentTitle("NEPDON WIRE")
                            .setContentText("VIP VPN engine active")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .build()
                    : new Notification.Builder(this)
                            .setContentTitle("NEPDON WIRE")
                            .setContentText("VIP VPN engine active")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .build();
            startForeground(2, n);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onDestroy() { stop(); super.onDestroy(); }

    @Override
    public void run() {
        try {
            int mtu = 1500;
            String ip = "10.0.0.2";
            tunIP = 0x0A000002;

            Builder builder = new Builder()
                .setSession("XENOX_LOW_LATENCY")
                .addAddress(ip, 24)
                .setMtu(mtu)
                .setBlocking(true);

            if (pkg != null && !pkg.isEmpty()) {
                try { builder.addAllowedApplication(pkg); } catch (Exception e) {}
            }

            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("1.1.1.1");
            builder.addRoute("0.0.0.0", 0);

            tun = builder.establish();
            if (tun == null) { publishState(false); stop(); return; }
            publishState(true);

            loop();

        } catch (Exception e) {
            e.printStackTrace();
            publishState(false);
        } finally {
            if (isRun) stop();
        }
    }

    private void loop() throws Exception {
        FileInputStream  in  = new FileInputStream(tun.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
        byte[] buf = new byte[0xffff];

        while (isRun) {
            int len = in.read(buf);
            if (len < 20) continue;
            bIn.addAndGet(len);

            if ((buf[0] >> 4 & 0xf) != 4) continue;
            int proto = buf[9] & 0xff;
            int ihl   = (buf[0] & 0xf) * 4;

            if (proto != 17) continue;
            if (len < ihl + 8) continue;

            int dip  = ri(buf, 16);
            int sp   = rs(buf, ihl);
            int dp   = rs(buf, ihl + 2);
            int plen = rs(buf, ihl + 4) - 8;
            int off  = ihl + 8;

            if (plen < 0 || off + plen > len) continue;

            if (isActive) {
                if (isGhost && plen >= 0x37 && plen <= 0xa0) {
                    pDrop.incrementAndGet();
                    continue;
                }

                if ((isJitter || _teleReplaying) && plen >= 0x37 && plen <= 0xa0) {
                    if (isJitter && _teleBuf.size() < TELE_BUF_MAX) {
                        byte[] copy = new byte[plen];
                        System.arraycopy(buf, off, copy, 0, plen);
                        _teleBuf.offer(new _TP(copy, dip, dp, sp));
                    }
                    continue;
                }
            }

            long key = ((long)(sp & 0xffff) << 48)
				| ((long)(dp & 0xffff) << 32)
				| (dip & 0xFFFFFFFFL);
            SE se = getOrCreate(key, sp, dip, dp, out);
            if (se == null) continue;

            try {
                DatagramPacket pkt = new DatagramPacket(
                    buf, off, plen, InetAddress.getByAddress(tb(dip)), dp);
                se.s.send(pkt);
                se.touch();
                pSent.incrementAndGet();
                bOut.addAndGet(plen);
            } catch (Exception e) { }
        }
    }

    private SE getOrCreate(long key, int sp, int dip, int dp, FileOutputStream out) {
        SE existing = (SE) udpMap.get(key);
        if (existing != null) return existing;
        try {
            DatagramSocket sock = new DatagramSocket();
            protect(sock);
            try { sock.setSoTimeout(SO_TO); } catch (Exception e) { }
            SE se = new SE(sock);
            SE prev = (SE) udpMap.putIfAbsent(key, se);
            if (prev != null) { sock.close(); return prev; }
            new Thread(new RecvRunnable(se, sp, dip, dp, out, key), "R").start();
            return se;
        } catch (Exception e) {
            return null;
        }
    }

    private class RecvRunnable implements Runnable {
        private final SE fe;
        private final int sip, dp, sp;
        private final FileOutputStream out;
        private final long key;

        RecvRunnable(SE fe, int origSip, int origDip, int origDp,
                     FileOutputStream out, long key) {
            this.fe  = fe;
            this.sip = origDip;
            this.dp  = origDp;
            this.sp  = origSip;
            this.out = out;
            this.key = key;
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(-8);
            byte[] rbuf = new byte[0x8000];
            while (isRun) {
                try {
                    DatagramPacket pkt = new DatagramPacket(rbuf, rbuf.length);
                    long t0 = System.nanoTime();
                    fe.s.receive(pkt);
                    rtt = (rtt * 3 + (System.nanoTime() - t0) / 1000000L) / 4;
                    int rlen = pkt.getLength();
                    bIn.addAndGet(rlen);
                    fe.touch();

                    if (isActive && isFreeze) {
                        pDrop.incrementAndGet();
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        continue;
                    }

                    byte[] ip = buildPkt(pkt, sip, dp, sp);
                    synchronized (out) {
                        out.write(ip);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    if (!isRun) break;
                    if (System.currentTimeMillis() - fe.t > 30000L) break;
                } catch (Exception e) {
                    break;
                }
            }
            udpMap.remove(key);
            try { fe.s.close(); } catch (Exception e) { }
        }
    }

    private byte[] buildPkt(DatagramPacket pkt, int sip, int dp, int sp) {
        int plen  = pkt.getLength();
        int total = plen + 0x1c;
        byte[] buf = new byte[total];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.put((byte) 0x45);
        bb.put((byte) 0x00);
        bb.putShort((short) total);
        bb.putShort((short) rng.nextInt(0xffff));
        bb.putShort((short) 0x4000);
        bb.put((byte) 0x40);
        bb.put((byte) 0x11);
        bb.putShort((short) 0);
        bb.putInt(sip);
        bb.putInt(tunIP);
        short ck = cksum(buf, 0x14);
        buf[10] = (byte)(ck >> 8);
        buf[11] = (byte)(ck & 0xff);
        bb.putShort((short) dp);
        bb.putShort((short) sp);
        bb.putShort((short)(plen + 8));
        bb.putShort((short) 0);
        System.arraycopy(pkt.getData(), pkt.getOffset(), buf, 0x1c, plen);
        return buf;
    }

    private static short cksum(byte[] buf, int len) {
        int sum = 0;
        for (int i = 0; i < len; i += 2)
            sum += ((buf[i] & 0xff) << 8) | (buf[i+1] & 0xff);
        while ((sum >> 16) != 0)
            sum = (sum & 0xffff) + (sum >> 16);
        return (short)(~sum);
    }

    private static int ri(byte[] b, int o) {
        return ((b[o]&0xff)<<24)|((b[o+1]&0xff)<<16)|((b[o+2]&0xff)<<8)|(b[o+3]&0xff);
    }
    private static int rs(byte[] b, int o) {
        return ((b[o]&0xff)<<8)|(b[o+1]&0xff);
    }
    private static byte[] tb(int ip) {
        return new byte[]{(byte)(ip>>24),(byte)(ip>>16),(byte)(ip>>8),(byte)ip};
    }

    private void _startTeleReplay() {
        if (_teleBuf.isEmpty()) return;
        _teleReplaying = true;
        final List all = new ArrayList();
        _TP tp;
        while ((tp = (_TP) _teleBuf.poll()) != null) all.add(tp);
        new Thread(new TeleReplayRunnable(all)).start();
    }

    private class TeleReplayRunnable implements Runnable {
        private final List all;
        TeleReplayRunnable(List all) { this.all = all; }
        @Override
        public void run() {
            try {
                for (Iterator it = ((Collection)all).iterator(); it.hasNext(); ) {
                    _TP tp = (_TP) it.next();
                    long key = ((long)(tp.sp&0xffff)<<48)
						| ((long)(tp.dp&0xffff)<<32)
						| (tp.dip & 0xFFFFFFFFL);
                    SE se = (SE) udpMap.get(key);
                    if (se != null) {
                        try {
                            DatagramPacket p = new DatagramPacket(
                                tp.data, 0, tp.data.length,
                                InetAddress.getByAddress(tb(tp.dip)), tp.dp);
                            se.s.send(p);
                            se.touch();
                        } catch (Exception e) { }
                    }
                    try { Thread.sleep(23); } catch (Exception e) { }
                }
            } finally {
                _teleReplaying = false;
                _teleBuf.clear();
            }
        }
    }

    private void startStats() {
        new Thread(new Runnable() {
				@Override
				public void run() {
					while (isRun) {
						try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
						long sent  = pSent.get();
						long drop  = pDrop.get();
						long total = sent + drop;
						int  loss  = total > 0 ? (int)(drop * 100L / total) : 0;
						String mode = isActive ? "ACTIVE" : "STANDBY";
						Intent i = new Intent(ACTION_STATS);
						i.putExtra("ping",       (int) rtt);
						i.putExtra("loss",       loss);
						i.putExtra(EXTRA_BYTES_IN,  bIn.get());
						i.putExtra(EXTRA_BYTES_OUT, bOut.get());
						i.putExtra("mode",       mode);
						i.putExtra("filter_log", mode + " f=" + isFreeze
								   + " g=" + isGhost + " udp=" + udpMap.size());
						i.setPackage(getPackageName());
						sendBroadcast(i);
					}
				}
			}, "Stats").start();
    }

    private void stop() {
        isRun = false;
        isActive = false;
        publishState(false);
        try { if (tun != null) tun.close(); } catch (Exception e) { }
        for (Iterator it = udpMap.values().iterator(); it.hasNext(); ) {
            try { ((SE) it.next()).s.close(); } catch (Exception e) { }
        }
        udpMap.clear();
        stopSelf();
    }
}
