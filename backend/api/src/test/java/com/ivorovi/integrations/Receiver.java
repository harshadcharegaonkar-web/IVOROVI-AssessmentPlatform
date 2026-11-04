package com.ivorovi.integrations;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Local HTTP receiver for adapter and webhook tests (JDK built-in server on 127.0.0.1). */
final class Receiver implements AutoCloseable {

    record Request(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst().map(e -> e.getValue().getFirst()).orElse(null);
        }
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
    private final HttpServer server;

    Receiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(ex.getRequestMethod(), ex.getRequestURI().toString(), Map.copyOf(ex.getRequestHeaders()), body));
            int s = ex.getRequestURI().getPath().startsWith("/fail") ? 500 : status.get();
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(s, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
