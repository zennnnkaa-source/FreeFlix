package com.nepdon.wire.tunnel;

import com.nepdon.wire.dev.DevLogger;

import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Stateless helpers for building IP/UDP headers, computing checksums, and
 * answering ICMP echo requests. Extracted from the old monolithic service so
 * the byte-level logic lives in one place.
 */
public final class PacketBuilder {

    private static final Random RNG = new Random();

    private PacketBuilder() { }

    /** Build a full IPv4/UDP packet around an inbound UDP payload. */
    public static byte[] buildPkt(DatagramPacket pkt, int sip, int dp, int sp, int tunIP) {
        int plen  = pkt.getLength();
        int total = plen + 0x1c;
        byte[] buf = new byte[total];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.put((byte) 0x45);
        bb.put((byte) 0x00);
        bb.putShort((short) total);
        bb.putShort((short) RNG.nextInt(0xffff));
        bb.putShort((short) 0x4000);
        bb.put((byte) 0x40);
        bb.put((byte) 0x11);
        bb.putShort((short) 0);
        bb.putInt(sip);
        bb.putInt(tunIP);
        short ck = cksum(buf, 0x14);
        buf[10] = (byte) (ck >> 8);
        buf[11] = (byte) (ck & 0xff);
        bb.putShort((short) dp);
        bb.putShort((short) sp);
        bb.putShort((short) (plen + 8));
        bb.putShort((short) 0);
        System.arraycopy(pkt.getData(), pkt.getOffset(), buf, 0x1c, plen);
        return buf;
    }

    /**
     * Answer an ICMP echo request by swapping src/dst, flipping the type to
     * echo-reply, and recomputing both checksums. Returns true if the packet
     * was an echo request and was handled (written or logged as failed).
     */
    public static boolean replyIcmpEchoRequest(byte[] packet, int len, int ihl, int dip,
                                               int sourcePort, int destPort, FileOutputStream out,
                                               DevLogger logger, String routeMode) {
        int totalLen = rs(packet, 2);
        if (totalLen <= 0 || totalLen > len) totalLen = len;
        // A valid IPv4 header is >= 20 bytes; the src/dst swap below writes
        // reply[12..19] and the IP checksum spans [0..ihl). A malformed short
        // header (ihl<20 or totalLen<20) would index past `reply` and throw out
        // of the TUN read loop, killing it (2026-09-18 audit L3). Reject early.
        if (ihl < 20 || totalLen < 20) return false;
        int icmpOff = ihl;
        int icmpLen = totalLen - icmpOff;
        if (icmpLen < 8 || ub(packet, icmpOff) != 8 || ub(packet, icmpOff + 1) != 0) {
            return false;
        }

        byte[] reply = new byte[totalLen];
        System.arraycopy(packet, 0, reply, 0, totalLen);

        for (int i = 0; i < 4; i++) {
            byte src = reply[12 + i];
            reply[12 + i] = reply[16 + i];
            reply[16 + i] = src;
        }

        reply[8] = 64;
        reply[10] = 0;
        reply[11] = 0;
        short ipChecksum = cksum(reply, 0, ihl);
        reply[10] = (byte) (ipChecksum >> 8);
        reply[11] = (byte) (ipChecksum & 0xff);

        reply[icmpOff] = 0;
        reply[icmpOff + 1] = 0;
        reply[icmpOff + 2] = 0;
        reply[icmpOff + 3] = 0;
        short icmpChecksum = cksum(reply, icmpOff, icmpLen);
        reply[icmpOff + 2] = (byte) (icmpChecksum >> 8);
        reply[icmpOff + 3] = (byte) (icmpChecksum & 0xff);

        try {
            synchronized (out) {
                out.write(reply);
            }
            logger.logPacket("REPLY_ICMP_ECHO", "IN", packet, icmpOff, icmpLen,
                    sourcePort, destPort, dip,
                    "icmp=echo route=" + routeMode);
            return true;
        } catch (Exception e) {
            logger.logProtocolDrop("DROP_ICMP_ECHO_REPLY_FAIL", packet, icmpOff, icmpLen,
                    sourcePort, destPort, dip,
                    "icmp=echo_reply_error route=" + routeMode);
            return true;
        }
    }

    public static short cksum(byte[] buf, int len) {
        return cksum(buf, 0, len);
    }

    public static short cksum(byte[] buf, int off, int len) {
        int sum = 0;
        int end = off + len;
        int i = off;
        while (i + 1 < end) {
            sum += ((buf[i] & 0xff) << 8) | (buf[i + 1] & 0xff);
            i += 2;
        }
        if (i < end) {
            sum += (buf[i] & 0xff) << 8;
        }
        while ((sum >> 16) != 0)
            sum = (sum & 0xffff) + (sum >> 16);
        return (short) (~sum);
    }

    /** Read a big-endian 32-bit int. */
    public static int ri(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    /** Read a big-endian 16-bit int. */
    public static int rs(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    /** Convert a 32-bit int IP to a 4-byte array. */
    public static byte[] tb(int ip) {
        return new byte[]{(byte) (ip >> 24), (byte) (ip >> 16), (byte) (ip >> 8), (byte) ip};
    }

    public static byte[] copyPayload(byte[] src, int off, int len) {
        byte[] copy = new byte[len];
        System.arraycopy(src, off, copy, 0, len);
        return copy;
    }

    public static String protoName(int proto) {
        if (proto == 1) return "ICMP(1)";
        if (proto == 6) return "TCP(6)";
        if (proto == 17) return "UDP(17)";
        if (proto == 58) return "ICMPV6(58)";
        return "PROTO(" + proto + ")";
    }

    private static int ub(byte[] data, int index) {
        return data[index] & 0xff;
    }
}
