package com.ivorovi.platform.scanning;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.zip.CRC32;

/** Zip test archives without a library: a ZipCrypto (traditional PKWARE) encrypted entry, and a stored entry with lying sizes. */
final class TestZips {
    private int k0 = 0x12345678, k1 = 0x23456789, k2 = 0x34567890;
    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            CRC_TABLE[n] = c;
        }
    }

    private static int crc(int crc, int b) {
        return (crc >>> 8) ^ CRC_TABLE[(crc ^ b) & 0xff];
    }

    private void update(int b) {
        k0 = crc(k0, b);
        k1 = (k1 + (k0 & 0xff)) * 134775813 + 1;
        k2 = crc(k2, k1 >>> 24);
    }

    private int encrypt(int b) {
        int t = (k2 | 2) & 0xffff;
        int c = b ^ (((t * (t ^ 1)) >>> 8) & 0xff);
        update(b);
        return c;
    }

    static byte[] storedEncrypted(String name, byte[] data, String password) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        long crc = crc32.getValue();
        TestZips z = new TestZips();
        for (byte b : password.getBytes(StandardCharsets.UTF_8)) z.update(b & 0xff);
        byte[] header = new byte[12];
        new SecureRandom().nextBytes(header);
        header[11] = (byte) (crc >>> 24);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (byte b : header) payload.write(z.encrypt(b & 0xff));
        for (byte b : data) payload.write(z.encrypt(b & 0xff));
        byte[] enc = payload.toByteArray();
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // local file header: version 20, flag 1 (encrypted), method 0 (stored)
        le(out, 0x04034b50, 4); le(out, 20, 2); le(out, 1, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0, 2);
        le(out, crc, 4); le(out, enc.length, 4); le(out, data.length, 4); le(out, n.length, 2); le(out, 0, 2);
        out.writeBytes(n);
        out.writeBytes(enc);
        int central = out.size();
        le(out, 0x02014b50, 4); le(out, 20, 2); le(out, 20, 2); le(out, 1, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0, 2);
        le(out, crc, 4); le(out, enc.length, 4); le(out, data.length, 4); le(out, n.length, 2); le(out, 0, 2); le(out, 0, 2);
        le(out, 0, 2); le(out, 0, 2); le(out, 0, 4); le(out, 0, 4);
        out.writeBytes(n);
        int centralSize = out.size() - central;
        le(out, 0x06054b50, 4); le(out, 0, 2); le(out, 0, 2); le(out, 1, 2); le(out, 1, 2); le(out, centralSize, 4); le(out, central, 4); le(out, 0, 2);
        return out.toByteArray();
    }

    private static void le(ByteArrayOutputStream out, long v, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int) (v >>> (8 * i)) & 0xff);
    }

    /** One stored (uncompressed) entry whose headers declare {@code declaredSize}, whatever the data's real length. */
    static byte[] stored(String name, byte[] data, long declaredSize) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        long crc = crc32.getValue();
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le(out, 0x04034b50, 4); le(out, 20, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0, 2);
        le(out, crc, 4); le(out, data.length, 4); le(out, declaredSize, 4); le(out, n.length, 2); le(out, 0, 2);
        out.writeBytes(n);
        out.writeBytes(data);
        int central = out.size();
        le(out, 0x02014b50, 4); le(out, 20, 2); le(out, 20, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0, 2);
        le(out, crc, 4); le(out, data.length, 4); le(out, declaredSize, 4); le(out, n.length, 2); le(out, 0, 2); le(out, 0, 2);
        le(out, 0, 2); le(out, 0, 2); le(out, 0, 4); le(out, 0, 4);
        out.writeBytes(n);
        int centralSize = out.size() - central;
        le(out, 0x06054b50, 4); le(out, 0, 2); le(out, 0, 2); le(out, 1, 2); le(out, 1, 2); le(out, centralSize, 4); le(out, central, 4); le(out, 0, 2);
        return out.toByteArray();
    }
}
