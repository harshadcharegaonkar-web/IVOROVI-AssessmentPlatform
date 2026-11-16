package com.ivorovi.platform.scanning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
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
 * i.e. INFECTED, never CLEAN. {@code AlertExceedsMax} does not cover an archive entry larger than MaxFileSize (QA-DP-1, fixed in the adapter).
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
        MalwareScanner.Result r = scanner.scan(TestZips.storedEncrypted("certificate.txt", EICAR.getBytes(StandardCharsets.US_ASCII), "synthetic-pass"));
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
     * archives. Found 2026-11-16; fixed by {@link ArchiveLimits}: such a container is ERROR without asking clamd.
     */
    @Test
    void contentBeyondTheScanLimitIsNeverReportedClean() throws Exception {
        byte[] archive = zip("report.bin", padded(21 * 1024 * 1024));    // marker after 21 MB; MaxFileSize 20M
        assertThat(archive.length).isLessThan(100 * 1024);
        MalwareScanner.Result r = scanner.scan(archive);
        assertThat(r.verdict()).as("an archive entry scanned only in part (%s)", r.detail()).isNotEqualTo(MalwareScanner.Verdict.CLEAN);
        assertThat(r.verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
        assertThat(r.detail()).contains("not scanned completely");
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
}
