package com.ivorovi.platform.scanning;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * QA-DP-1 (D-19): clamd scans only the first MaxFileSize bytes of a larger archive entry and still answers OK, so a zip
 * container (ZIP, XLSX, DOCX, …) whose content exceeds clamd's limits would be reported clean after a partial scan. This
 * check runs before clamd and fails closed: it reads the central directory and inflates every entry (java.util.zip only),
 * and refuses the file when
 * <ul>
 *   <li>an entry's declared or actual uncompressed size exceeds {@code maxFileSize}, or the total exceeds {@code maxScanSize};</li>
 *   <li>an entry's actual size differs from its declared size (a lying header), or its method is neither stored nor deflated;</li>
 *   <li>the archive is malformed, uses ZIP64, nests archives deeper than {@code MAX_DEPTH}, or has more than {@code MAX_ENTRIES}
 *       entries.</li>
 * </ul>
 * Nested archives are checked the same way and count towards the total. Encrypted entries cannot be inflated; their declared
 * sizes are checked and clamd reports them as found (AlertEncrypted). Content that is not a zip container is not inspected.
 */
final class ArchiveLimits {

    static final int MAX_DEPTH = 5;
    static final int MAX_ENTRIES = 10_000;
    private static final int LOCAL = 0x04034b50, CENTRAL = 0x02014b50, END = 0x06054b50;

    private final long maxFileSize;
    private final long maxScanSize;

    ArchiveLimits(long maxFileSize, long maxScanSize) {
        if (maxFileSize <= 0 || maxScanSize < maxFileSize) throw new IllegalArgumentException("invalid scan limits");
        this.maxFileSize = maxFileSize;
        this.maxScanSize = maxScanSize;
    }

    /** Thrown when the content must not be reported clean; the message is safe to audit (no content). */
    static final class Exceeded extends Exception {
        Exceeded(String message) {
            super(message);
        }
    }

    static boolean isZip(byte[] b) {
        return b.length >= 4 && le32(b, 0) == LOCAL;
    }

    /** Throws {@link Exceeded} when the content is a zip container that clamd could not scan completely. */
    void check(byte[] content) throws Exceeded {
        if (isZip(content)) inspect(content, 0, new long[] {0});
    }

    private void inspect(byte[] zip, int depth, long[] total) throws Exceeded {
        if (depth > MAX_DEPTH) throw new Exceeded("archives nested deeper than " + MAX_DEPTH + " levels");
        int eocd = findEnd(zip);
        int entries = le16(zip, eocd + 10);
        long cdSize = le32u(zip, eocd + 12), cdOffset = le32u(zip, eocd + 16);
        if (entries == 0xffff || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) throw new Exceeded("ZIP64 archive");
        if (entries > MAX_ENTRIES) throw new Exceeded("archive with more than " + MAX_ENTRIES + " entries");
        if (cdOffset + cdSize > eocd) throw new Exceeded("malformed archive (central directory)");
        int p = (int) cdOffset;
        for (int i = 0; i < entries; i++) {
            need(zip, p, 46);
            if (le32(zip, p) != CENTRAL) throw new Exceeded("malformed archive (central directory entry)");
            int flags = le16(zip, p + 8), method = le16(zip, p + 10);
            long compressed = le32u(zip, p + 20), declared = le32u(zip, p + 24);
            int nameLen = le16(zip, p + 28), extraLen = le16(zip, p + 30), commentLen = le16(zip, p + 32);
            long localOffset = le32u(zip, p + 42);
            if (compressed == 0xffffffffL || declared == 0xffffffffL || localOffset == 0xffffffffL) throw new Exceeded("ZIP64 archive entry");
            if (declared > maxFileSize) throw new Exceeded("an archive entry is larger than the scan limit");
            total[0] += declared;
            if (total[0] > maxScanSize) throw new Exceeded("archive content is larger than the scan limit");
            if ((flags & 1) == 0) {
                byte[] data = entryData(zip, localOffset, compressed, method, declared);
                if (isZip(data)) inspect(data, depth + 1, total);
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
    }

    /** The entry's uncompressed content, verified against its declared size and the per-file limit. */
    private byte[] entryData(byte[] zip, long localOffset, long compressed, int method, long declared) throws Exceeded {
        int h = (int) localOffset;
        need(zip, h, 30);
        if (le32(zip, h) != LOCAL) throw new Exceeded("malformed archive (local header)");
        long start = (long) h + 30 + le16(zip, h + 26) + le16(zip, h + 28);
        if (start + compressed > zip.length) throw new Exceeded("malformed archive (entry data)");
        if (method == 0) {
            if (compressed != declared) throw new Exceeded("an archive entry's declared size does not match its content");
            byte[] out = new byte[(int) compressed];
            System.arraycopy(zip, (int) start, out, 0, out.length);
            return out;
        }
        if (method != 8) throw new Exceeded("an archive entry uses an unsupported compression method");
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(zip, (int) start, (int) compressed);
            ByteBuffer out = ByteBuffer.allocate((int) Math.min(declared, maxFileSize));
            byte[] chunk = new byte[64 * 1024];
            long produced = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) throw new Exceeded("malformed archive (truncated entry)");
                produced += n;
                if (produced > declared) throw new Exceeded("an archive entry's declared size does not match its content");
                out.put(chunk, 0, n);
            }
            if (produced != declared) throw new Exceeded("an archive entry's declared size does not match its content");
            return out.array();
        } catch (DataFormatException e) {
            throw new Exceeded("malformed archive (compressed data)");
        } finally {
            inflater.end();
        }
    }

    private static int findEnd(byte[] zip) throws Exceeded {
        for (int i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
            if (le32(zip, i) == END) return i;
        }
        throw new Exceeded("malformed archive (no end of central directory)");
    }

    private static void need(byte[] b, int at, int n) throws Exceeded {
        if (at < 0 || at + n > b.length) throw new Exceeded("malformed archive (truncated)");
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static int le32(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long le32u(byte[] b, int at) {
        return le32(b, at) & 0xffffffffL;
    }
}
