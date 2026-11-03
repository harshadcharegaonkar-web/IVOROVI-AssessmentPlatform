package com.ivorovi.platform.scanning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** ClamAV adapter against a fake clamd that speaks the INSTREAM protocol. */
class ClamAvScannerTest {

    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    /** Fake clamd: reads the command and length-prefixed chunks, then replies. */
    private static ServerSocket fakeClamd(AtomicReference<byte[]> received, String replyForEicar, boolean hang) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try (Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] command = new byte["zINSTREAM\0".length()];
                in.readFully(command);
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                while (true) {
                    int len = in.readInt();
                    if (len == 0) break;
                    byte[] chunk = new byte[len];
                    in.readFully(chunk);
                    data.write(chunk);
                }
                received.set(data.toByteArray());
                if (hang) { Thread.sleep(3000); return; }
                String body = data.toString(StandardCharsets.ISO_8859_1);
                String reply = body.contains("EICAR") ? replyForEicar : "stream: OK";
                s.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {
                // test server
            }
        });
        return server;
    }

    @Test
    void cleanContentIsReportedClean() throws Exception {
        AtomicReference<byte[]> got = new AtomicReference<>();
        try (ServerSocket server = fakeClamd(got, "", false)) {
            byte[] content = new byte[200_000];
            MalwareScanner.Result r = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(5)).scan(content);
            assertThat(r.verdict()).isEqualTo(MalwareScanner.Verdict.CLEAN);
            assertThat(got.get()).hasSize(200_000);          // streamed in several chunks
        }
    }

    @Test
    void eicarIsReportedInfectedWithTheSignature() throws Exception {
        try (ServerSocket server = fakeClamd(new AtomicReference<>(), "stream: Eicar-Test-Signature FOUND", false)) {
            MalwareScanner.Result r = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(5)).scan(EICAR.getBytes(StandardCharsets.US_ASCII));
            assertThat(r.verdict()).isEqualTo(MalwareScanner.Verdict.INFECTED);
            assertThat(r.detail()).contains("Eicar-Test-Signature");
        }
    }

    @Test
    void timeoutsAndUnreachableScannersAreErrorsNeverClean() throws Exception {
        try (ServerSocket server = fakeClamd(new AtomicReference<>(), "", true)) {
            MalwareScanner.Result r = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofMillis(300)).scan("x".getBytes());
            assertThat(r.verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
        }
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) { closedPort = s.getLocalPort(); }
        assertThat(new ClamAvScanner("127.0.0.1", closedPort, Duration.ofMillis(300)).scan("x".getBytes()).verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
    }

    @Test
    void unexpectedRepliesAreErrors() throws Exception {
        try (ServerSocket server = fakeClamd(new AtomicReference<>(), "INSTREAM size limit exceeded. ERROR", false)) {
            MalwareScanner.Result r = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(5)).scan(EICAR.getBytes(StandardCharsets.US_ASCII));
            assertThat(r.verdict()).isEqualTo(MalwareScanner.Verdict.ERROR);
        }
    }
}
