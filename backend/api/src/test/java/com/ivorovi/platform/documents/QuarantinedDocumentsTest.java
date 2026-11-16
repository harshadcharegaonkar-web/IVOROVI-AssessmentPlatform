package com.ivorovi.platform.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.config.DeploymentProperties.Environment;
import com.ivorovi.platform.scanning.MalwareScanner;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.support.FakeMalwareScanner;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** The shared quarantine pipeline (ADR-0006, D-19): fail closed, validation, quarantine, never CLEAN unless scanned clean. */
class QuarantinedDocumentsTest {

    static final byte[] PDF = "%PDF-1.4\nsynthetic".getBytes(StandardCharsets.ISO_8859_1);

    static final class MemoryStorage implements ObjectStorage {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();

        @Override
        public void put(String path, InputStream content, long contentLength, String contentType) {
            try {
                files.put(path, content.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Optional<byte[]> get(String path) {
            return Optional.ofNullable(files.get(path));
        }

        @Override
        public URI presignedDownload(String path, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }
    }

    static QuarantinedDocuments pipeline(MemoryStorage storage, MalwareScanner scanner, Environment env) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (scanner != null) beans.registerSingleton("scanner", scanner);
        return new QuarantinedDocuments(storage, beans.getBeanProvider(MalwareScanner.class), new DeploymentProperties(DeploymentProperties.Mode.SAAS, env), true);
    }

    @Test
    void failsClosedWithoutScannerOutsideDevelopmentAndTest() {
        MemoryStorage storage = new MemoryStorage();
        for (Environment env : new Environment[] {Environment.STAGING, Environment.PRODUCTION}) {
            assertThatThrownBy(() -> pipeline(storage, null, env).requireUploadsAllowed())
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("document_scanning_unavailable"));
            pipeline(storage, new FakeMalwareScanner(), env).requireUploadsAllowed();
        }
        pipeline(storage, null, Environment.DEVELOPMENT).requireUploadsAllowed();
        pipeline(storage, null, Environment.TEST).requireUploadsAllowed();
    }

    @Test
    void validationUsesMagicBytesAndLimits() {
        assertThat(QuarantinedDocuments.validate(PDF, "../../x/My Résumé.exe.pdf", QuarantinedDocuments.ALL_TYPES))
                .satisfies(v -> {
                    assertThat(v.contentType()).isEqualTo(QuarantinedDocuments.PDF);
                    assertThat(v.fileName()).isEqualTo("My R_sum_.exe.pdf");
                    assertThat(v.sha256()).hasSize(64);
                });
        assertThatThrownBy(() -> QuarantinedDocuments.validate(new byte[0], "a.pdf", QuarantinedDocuments.ALL_TYPES))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("empty_file"));
        assertThatThrownBy(() -> QuarantinedDocuments.validate("MZ executable".getBytes(StandardCharsets.ISO_8859_1), "a.pdf", QuarantinedDocuments.ALL_TYPES))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("unsupported_file_type"));
        assertThatThrownBy(() -> QuarantinedDocuments.validate(PDF, "a.pdf", QuarantinedDocuments.IMAGE_TYPES))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("unsupported_file_type"));
        byte[] big = new byte[(int) QuarantinedDocuments.MAX_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertThatThrownBy(() -> QuarantinedDocuments.validate(big, "a.pdf", QuarantinedDocuments.ALL_TYPES))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("file_too_large"));
    }

    @Test
    void onlyCleanIsPromotedAndInfectedErrorOrMissingStayInQuarantine() {
        MemoryStorage storage = new MemoryStorage();
        FakeMalwareScanner scanner = new FakeMalwareScanner();
        QuarantinedDocuments p = pipeline(storage, scanner, Environment.PRODUCTION);

        String clean = p.quarantine("admissions/a1", UUID.randomUUID(), PDF, QuarantinedDocuments.PDF);
        assertThat(clean).startsWith("quarantine/admissions/a1/");
        QuarantinedDocuments.Scanned ok = p.scan(clean, "documents/admissions/a1/x", QuarantinedDocuments.PDF).orElseThrow();
        assertThat(ok.clean()).isTrue();
        assertThat(ok.path()).isEqualTo("documents/admissions/a1/x");
        assertThat(storage.files).containsKey("documents/admissions/a1/x").doesNotContainKey(clean);

        byte[] eicar = ("%PDF-1.4\n" + FakeMalwareScanner.EICAR).getBytes(StandardCharsets.ISO_8859_1);
        String infected = p.quarantine("admissions/a1", UUID.randomUUID(), eicar, QuarantinedDocuments.PDF);
        QuarantinedDocuments.Scanned bad = p.scan(infected, "documents/admissions/a1/y", QuarantinedDocuments.PDF).orElseThrow();
        assertThat(bad.verdict()).isEqualTo(MalwareScanner.Verdict.INFECTED);
        assertThat(bad.path()).isEqualTo(infected);
        assertThat(storage.files).containsKey(infected).doesNotContainKey("documents/admissions/a1/y");

        scanner.available = false;
        String outage = p.quarantine("admissions/a1", UUID.randomUUID(), PDF, QuarantinedDocuments.PDF);
        assertThat(p.scan(outage, "documents/admissions/a1/z", QuarantinedDocuments.PDF).orElseThrow().verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
        assertThat(storage.files).doesNotContainKey("documents/admissions/a1/z");

        scanner.available = true;
        assertThat(p.scan("quarantine/admissions/a1/missing", "documents/admissions/a1/m", QuarantinedDocuments.PDF).orElseThrow().verdict())
                .isEqualTo(MalwareScanner.Verdict.ERROR);

        MalwareScanner noVerdict = new MalwareScanner() {
            public Result scan(byte[] content) { return null; }
            public String name() { return "broken"; }
        };
        String broken = p.quarantine("admissions/a1", UUID.randomUUID(), PDF, QuarantinedDocuments.PDF);
        assertThat(pipeline(storage, noVerdict, Environment.PRODUCTION).scan(broken, "documents/admissions/a1/b", QuarantinedDocuments.PDF).orElseThrow().verdict())
                .isEqualTo(MalwareScanner.Verdict.ERROR);
    }

    @Test
    void withoutScannerNothingIsScanned() {
        MemoryStorage storage = new MemoryStorage();
        QuarantinedDocuments p = pipeline(storage, null, Environment.DEVELOPMENT);
        String path = p.quarantine("admissions/a1", UUID.randomUUID(), PDF, QuarantinedDocuments.PDF);
        assertThat(p.scan(path, "documents/admissions/a1/x", QuarantinedDocuments.PDF)).isEmpty();
        assertThat(storage.files).containsOnlyKeys(path);
    }
}
