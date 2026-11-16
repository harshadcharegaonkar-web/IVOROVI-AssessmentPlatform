package com.ivorovi.platform.scanning;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * ClamAV adapter speaking the clamd {@code INSTREAM} protocol over TCP (clamd runs as a separate service; nothing
 * GPL-licensed is linked). Any protocol error, timeout or unexpected reply is reported as {@code ERROR}, never as clean. A zip
 * container that clamd could scan only in part is {@code ERROR} as well ({@link ArchiveLimits}, QA-DP-1).
 */
public class ClamAvScanner implements MalwareScanner {

    private static final int CHUNK = 64 * 1024;
    private final String host;
    private final int port;
    private final Duration timeout;
    private final ArchiveLimits archives;

    /** Limits equal to clamd's defaults in the compose overlay (MaxFileSize 20M, MaxScanSize 100M). */
    public ClamAvScanner(String host, int port, Duration timeout) {
        this(host, port, timeout, DEFAULT_MAX_FILE_SIZE, DEFAULT_MAX_SCAN_SIZE);
    }

    public static final long DEFAULT_MAX_FILE_SIZE = 20L * 1024 * 1024;
    public static final long DEFAULT_MAX_SCAN_SIZE = 100L * 1024 * 1024;

    /**
     * {@code maxFileSize} and {@code maxScanSize} must equal (or be below) clamd's MaxFileSize and MaxScanSize: a zip container
     * whose content exceeds them is reported ERROR without asking clamd, because clamd would scan it only in part and answer
     * OK (QA-DP-1).
     */
    public ClamAvScanner(String host, int port, Duration timeout, long maxFileSize, long maxScanSize) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
        this.archives = new ArchiveLimits(maxFileSize, maxScanSize);
    }

    @Override
    public Result scan(byte[] content) {
        try {
            archives.check(content);
        } catch (ArchiveLimits.Exceeded e) {
            return new Result(Verdict.ERROR, "clamav: not scanned completely (" + e.getMessage() + ")");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int off = 0; off < content.length; off += CHUNK) {
                int len = Math.min(CHUNK, content.length - off);
                out.write(ByteBuffer.allocate(4).putInt(len).array());
                out.write(content, off, len);
            }
            out.write(new byte[4]);
            out.flush();
            String reply = readReply(socket.getInputStream());
            if (reply.endsWith("OK") && !reply.contains("FOUND")) return new Result(Verdict.CLEAN, "clamav: OK");
            if (reply.endsWith("FOUND")) {
                String signature = reply.substring(reply.indexOf(':') + 1, reply.length() - "FOUND".length()).trim();
                return new Result(Verdict.INFECTED, "clamav: " + signature);
            }
            return new Result(Verdict.ERROR, "clamav: unexpected reply");
        } catch (IOException e) {
            return new Result(Verdict.ERROR, "clamav: " + e.getClass().getSimpleName());
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0 && buf.size() < 1024) buf.write(b);
        return buf.toString(StandardCharsets.US_ASCII).trim();
    }

    @Override
    public String name() {
        return "clamav";
    }
}
