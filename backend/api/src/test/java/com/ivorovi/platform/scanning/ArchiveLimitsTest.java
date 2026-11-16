package com.ivorovi.platform.scanning;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** QA-DP-1: a zip container that clamd could scan only in part is refused before clamd is asked (never reported clean). */
class ArchiveLimitsTest {

    static final long MB = 1024 * 1024;
    final ArchiveLimits limits = new ArchiveLimits(2 * MB, 5 * MB);

    static byte[] zip(Object... nameAndContent) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buf)) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) nameAndContent[i]));
                z.write((byte[]) nameAndContent[i + 1]);
                z.closeEntry();
            }
        }
        return buf.toByteArray();
    }

    @Test
    void contentThatIsNotAZipContainerIsNotInspected() {
        assertThatCode(() -> limits.check("%PDF-1.4 a synthetic document".getBytes(StandardCharsets.US_ASCII))).doesNotThrowAnyException();
        assertThatCode(() -> limits.check(new byte[0])).doesNotThrowAnyException();
        assertThatCode(() -> limits.check(new byte[20 * (int) MB])).doesNotThrowAnyException();   // clamd's StreamMaxLength handles size
    }

    @Test
    void anOrdinaryArchiveWithinTheLimitsPasses() throws Exception {
        byte[] xlsxLike = zip("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8), "xl/worksheets/sheet1.xml", new byte[(int) MB],
                "xl/sharedStrings.xml", "<sst/>".getBytes(StandardCharsets.UTF_8), "empty/", new byte[0]);
        assertThatCode(() -> limits.check(xlsxLike)).doesNotThrowAnyException();
        assertThatCode(() -> limits.check(zip("inner.zip", zip("a.txt", new byte[(int) MB])))).doesNotThrowAnyException();
    }

    @Test
    void workbooksWrittenByThePlatformItselfPass() throws Exception {
        // the platform's own XLSX writer (templates, error reports, exports): what institutions edit and upload again
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.ivorovi.platform.tabular.TabularWriter w = com.ivorovi.platform.tabular.TabularWriter.of(com.ivorovi.platform.tabular.TabularFile.Format.XLSX, out);
        w.sheet("Students", java.util.List.of(20, 30));
        w.header(java.util.List.of("Student ID", "Name"));
        for (int r = 0; r < 5000; r++) w.row(java.util.List.of("MIE2026" + r, "Synthetic student " + r));
        w.close();
        byte[] xlsx = out.toByteArray();
        org.assertj.core.api.Assertions.assertThat(ArchiveLimits.isZip(xlsx)).isTrue();
        assertThatCode(() -> new ArchiveLimits(ClamAvScanner.DEFAULT_MAX_FILE_SIZE, ClamAvScanner.DEFAULT_MAX_SCAN_SIZE).check(xlsx)).doesNotThrowAnyException();
    }

    @Test
    void anEntryLargerThanTheFileLimitIsRefused() throws Exception {
        byte[] bomb = zip("expanded.bin", new byte[(int) (2 * MB + 1)]);
        assertThatThrownBy(() -> limits.check(bomb)).isInstanceOf(ArchiveLimits.Exceeded.class).hasMessageContaining("larger than the scan limit");
    }

    @Test
    void contentLargerInTotalThanTheScanLimitIsRefusedAlsoAcrossNestedArchives() throws Exception {
        byte[] part = new byte[(int) (1.5 * MB)];
        assertThatThrownBy(() -> limits.check(zip("1", part, "2", part, "3", part, "4", part))).isInstanceOf(ArchiveLimits.Exceeded.class)
                .hasMessageContaining("larger than the scan limit");
        byte[] nested = zip("a.zip", zip("1", part, "2", part), "b.zip", zip("3", part, "4", part));
        assertThatThrownBy(() -> limits.check(nested)).isInstanceOf(ArchiveLimits.Exceeded.class);
        assertThatThrownBy(() -> limits.check(zip("outer.zip", zip("inner.bin", new byte[(int) (2 * MB + 1)])))).isInstanceOf(ArchiveLimits.Exceeded.class);
    }

    @Test
    void archivesNestedTooDeeplyAreRefused() throws Exception {
        byte[] z = zip("core.txt", "x".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < ArchiveLimits.MAX_DEPTH; i++) z = zip("level" + i + ".zip", z);
        byte[] ok = z;
        assertThatCode(() -> limits.check(ok)).doesNotThrowAnyException();
        byte[] deep = zip("one-more.zip", z);
        assertThatThrownBy(() -> limits.check(deep)).isInstanceOf(ArchiveLimits.Exceeded.class).hasMessageContaining("nested");
    }

    @Test
    void anEntryWhoseHeaderUnderstatesItsSizeIsRefused() throws Exception {
        byte[] data = new byte[100];
        assertThatThrownBy(() -> limits.check(TestZips.stored("small.txt", data, 10))).isInstanceOf(ArchiveLimits.Exceeded.class)
                .hasMessageContaining("declared size");
        // a deflated entry whose central directory claims 1 KB for 3 MB of content (would pass the size check if trusted)
        byte[] z = zip("report.bin", new byte[(int) (3 * MB)]);
        patchCentralUncompressedSize(z, 1024);
        assertThatThrownBy(() -> limits.check(z)).isInstanceOf(ArchiveLimits.Exceeded.class).hasMessageContaining("declared size");
    }

    @Test
    void malformedArchivesAreRefused() throws Exception {
        byte[] good = zip("a.txt", "hello".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> limits.check(Arrays.copyOf(good, good.length - 30))).isInstanceOf(ArchiveLimits.Exceeded.class);
        byte[] headerOnly = Arrays.copyOf(good, 10);
        assertThatThrownBy(() -> limits.check(headerOnly)).isInstanceOf(ArchiveLimits.Exceeded.class);
        byte[] corrupt = good.clone();
        for (int i = 40; i < 50 && i < corrupt.length; i++) corrupt[i] ^= 0x5a;   // inside the compressed data
        assertThatThrownBy(() -> limits.check(corrupt)).isInstanceOf(ArchiveLimits.Exceeded.class);
    }

    @Test
    void encryptedEntriesAreCheckedByTheirDeclaredSizeAndLeftToClamd() {
        byte[] small = TestZips.storedEncrypted("c.txt", "secret".getBytes(StandardCharsets.US_ASCII), "synthetic-pass");
        assertThatCode(() -> limits.check(small)).doesNotThrowAnyException();   // clamd reports it as found (AlertEncrypted)
    }

    @Test
    void theAdapterReportsALimitViolationAsAnErrorWithoutContactingClamd() throws Exception {
        ClamAvScanner nowhere = new ClamAvScanner("127.0.0.1", 1, java.time.Duration.ofSeconds(1), 2 * MB, 5 * MB);
        MalwareScanner.Result r = nowhere.scan(zip("expanded.bin", new byte[(int) (3 * MB)]));
        org.assertj.core.api.Assertions.assertThat(r.verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
        org.assertj.core.api.Assertions.assertThat(r.detail()).startsWith("clamav: not scanned completely");
    }

    /** Overwrites the uncompressed size of the first central directory entry. */
    static void patchCentralUncompressedSize(byte[] zip, int size) {
        for (int i = zip.length - 22; i >= 0; i--) {
            if (ByteBuffer.wrap(zip, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == 0x02014b50) {
                ByteBuffer.wrap(zip, i + 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(size);
                return;
            }
        }
        throw new AssertionError("no central directory entry");
    }
}
