package com.ivorovi.platform.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Buffers the request body (bounded) so it can be hashed before the handler reads it. */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readNBytes(MAX_BYTES + 1);
    }

    byte[] body() { return body; }

    boolean tooLarge() { return body.length > MAX_BYTES; }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
