package com.ivorovi.platform.scanning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * The ClamAV adapter against a real clamd (D-19: failed, unavailable or infected scans never count as clean), with the
 * same clamd settings as the compose {@code clamav} service ({@link #SETTINGS}; image pinned by digest as in the SBOM).
 * Complements {@code ClamAvScannerTest} (protocol edge cases against a fake clamd). The image ships its signature
 * database, so no download is needed.
 *
 * <p>The alert settings matter: with ClamAV's defaults an encrypted archive is reported OK, because clamd cannot look inside
 * it (verified with the same image). With {@code AlertEncrypted*} it is reported as found ({@code Heuristics.Encrypted.*}),
 * i.e. INFECTED, never CLEAN. {@code AlertExceedsMax} does not cover an archive entry larger than MaxFileSize (QA-DP-1).
 */
@Tag("clamav")
class ClamAvRealScannerIT {

    static final String IMAGE = "clamav/clamav:1.4@sha256:4cc4350d3fded380caf1799e76c0d1c93f3fa40af99c0cb2862ec0c9b117718a";

    /** Keep identical to deploy/compose/compose.scan.yaml (CLAMD_CONF_*). */
    static final String[][] SETTINGS = {
            {"AlertEncrypted", "yes"}, {"AlertEncryptedArchive", "yes"}, {"AlertEncryptedDoc", "yes"}, {"AlertExceedsMax", "yes"},
            {"AlertBrokenExecutables", "yes"}, {"AlertOLE2Macros", "yes"},
            {"StreamMaxLength", "25M"}, {"MaxFileSize", "20M"}, {"MaxScanSize", "100M"}};

    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    /**
     * A QA test signature loaded into this container only (body signature, any offset), because EICAR is only detected at the
     * start of a file and therefore cannot show whether content deep inside a file is scanned.
     */
    static final String MARKER = "IVOROVI-QA-SCAN-DEPTH-MARKER-7f3c";

    static GenericContainer<?> clamd;
    static ClamAvScanner scanner;

    @BeforeAll
    @SuppressWarnings("resource")
    static void start() {
        clamd = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(3310).withEnv("CLAMAV_NO_FRESHCLAMD", "true")
                .withEnv("CLAMAV_NO_MILTERD", "true").waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)));
        for (String[] s : SETTINGS) clamd.withEnv("CLAMD_CONF_" + s[0], s[1]);
        clamd.withCopyToContainer(Transferable.of("Ivorovi.QA.ScanDepthMarker:0:*:" + java.util.HexFormat.of().formatHex(MARKER.getBytes(StandardCharsets.US_ASCII)) + "\n"),
                "/var/lib/clamav/ivorovi-qa.ndb");
        clamd.start();
        scanner = new ClamAvScanner(clamd.getHost(), clamd.getMappedPort(3310), Duration.ofSeconds(60));
    }

    @AfterAll
    static void stop() {
        if (clamd != null) clamd.stop();
    }

    private static byte[] zip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buf)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(content);
            z.closeEntry();
        }
        return buf.toByteArray();
    }

    @Test
    void cleanDocumentsAreClean() {
        assertThat(scanner.scan("Transfer certificate of a synthetic student, issued 2026-11-16.".getBytes(StandardCharsets.UTF_8)).verdict())
                .isEqualTo(MalwareScanner.Verdict.CLEAN);
        assertThat(scanner.scan(new byte[0]).verdict()).isEqualTo(MalwareScanner.Verdict.CLEAN);
    }

    @Test
    void theEicarTestFileIsInfectedAlsoInsideAnArchive() throws Exception {
        MalwareScanner.Result plain = scanner.scan(EICAR.getBytes(StandardCharsets.US_ASCII));
        assertThat(plain.verdict()).isEqualTo(MalwareScanner.Verdict.INFECTED);
        assertThat(plain.detail()).containsIgnoringCase("eicar");
        assertThat(scanner.scan(zip("marksheet.txt", EICAR.getBytes(StandardCharsets.US_ASCII))).verdict()).isEqualTo(MalwareScanner.Verdict.INFECTED);
    }

    @Test
    void anEncryptedArchiveIsNeverClean() throws Exception {
        MalwareScanner.Result r = scanner.scan(ZipCrypto.storedEncrypted("certificate.txt", EICAR.getBytes(StandardCharsets.US_ASCII), "synthetic-pass"));
        assertThat(r.verdict()).as("an archive clamd cannot open (%s)", r.detail()).isEqualTo(MalwareScanner.Verdict.INFECTED);
        assertThat(r.detail()).containsIgnoringCase("encrypted");
    }

    private static byte[] padded(int zeros) {
        byte[] m = MARKER.getBytes(StandardCharsets.US_ASCII);
        byte[] b = new byte[zeros + m.length];
        System.arraycopy(m, 0, b, zeros, m.length);
        return b;
    }

    @Test
    void contentDeepInsideACompressedArchiveWithinTheLimitsIsFound() throws Exception {
        assertThat(scanner.scan(MARKER.getBytes(StandardCharsets.US_ASCII)).verdict()).as("the QA signature is loaded").isEqualTo(MalwareScanner.Verdict.INFECTED);
        byte[] archive = zip("report.bin", padded(10 * 1024 * 1024));    // 10 MB of zeros, then the marker; a few KB deflated
        assertThat(archive.length).isLessThan(100 * 1024);
        assertThat(scanner.scan(archive).verdict()).isEqualTo(MalwareScanner.Verdict.INFECTED);
    }

    /**
     * QA-DP-1 (Medium): clamd truncates an archive entry larger than MaxFileSize, scans only its first MaxFileSize bytes and
     * answers OK, also with AlertExceedsMax (verified with clamd and clamscan 1.4.6, debug: "trimming output size to
     * maxfilesize"). Content after the limit is never scanned, yet the adapter reports CLEAN: a partial scan counts as clean,
     * contrary to D-19. A 5 MB upload can hold such an entry (21 MB of padding deflates to about 21 KB); XLSX imports are zip
     * archives. Verified failing 2026-11-16.
     */
    @org.junit.jupiter.api.Disabled("QA-DP-1: fails until a partially scanned file can no longer be reported clean — enable with the fix")
    @Test
    void contentBeyondTheScanLimitIsNeverReportedClean() throws Exception {
        byte[] archive = zip("report.bin", padded(21 * 1024 * 1024));    // marker after 21 MB; MaxFileSize 20M
        assertThat(archive.length).isLessThan(100 * 1024);
        MalwareScanner.Result r = scanner.scan(archive);
        assertThat(r.verdict()).as("an archive entry scanned only in part (%s)", r.detail()).isNotEqualTo(MalwareScanner.Verdict.CLEAN);
    }

    @Test
    void aStreamLongerThanTheLimitIsAnErrorNotClean() {
        byte[] big = new byte[26 * 1024 * 1024];
        new SecureRandom().nextBytes(big);
        assertThat(scanner.scan(big).verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
    }

    @Test
    void anUnavailableScannerIsAnErrorNotClean() {
        ClamAvScanner gone = new ClamAvScanner(clamd.getHost(), 1, Duration.ofSeconds(2));
        assertThat(gone.scan(EICAR.getBytes(StandardCharsets.US_ASCII)).verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
        assertThat(gone.scan("clean".getBytes(StandardCharsets.UTF_8)).verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
    }

    /** Traditional PKWARE (ZipCrypto) encryption of one stored entry: enough to build an encrypted archive without a library. */
    static final class ZipCrypto {
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
            ZipCrypto z = new ZipCrypto();
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
    }
}
