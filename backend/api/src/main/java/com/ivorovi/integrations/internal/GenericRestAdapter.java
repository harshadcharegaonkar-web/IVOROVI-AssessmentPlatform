package com.ivorovi.integrations.internal;

import com.ivorovi.platform.web.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Vendor-neutral HTTPS adapter for any SIS/ERP, LMS or institutional application with a REST interface: connection
 * test against a health endpoint and a connectivity sync against a data endpoint, with no, bearer, basic or custom
 * header authentication. In Phase 1 a sync only verifies that the endpoint answers and records the response size;
 * no data is imported or mapped (that needs an approved, provider-specific scope).
 */
@Component
class GenericRestAdapter implements IntegrationAdapter {

    static final String KEY = "generic-rest";

    private final OutboundGuard guard;
    private final IntegrationsProperties props;
    private final HttpClient http;

    GenericRestAdapter(OutboundGuard guard, IntegrationsProperties props) {
        this.guard = guard;
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(props.requestTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Info info() {
        return new Info(KEY, "REST / HTTPS service", "Any system with an HTTPS interface (SIS, ERP, LMS or an institutional application). "
                + "Checks a health endpoint; a sync checks a data endpoint and records the response size. No data is imported in Phase 1.",
                List.of(Kind.SIS_ERP, Kind.LMS, Kind.APPLICATION),
                List.of(new Field("baseUrl", "Base URL", "url", true, List.of(), "For example https://sis.example.edu/api"),
                        new Field("healthPath", "Health check path", "text", false, List.of(), "Appended to the base URL; default /"),
                        new Field("syncPath", "Sync path", "text", false, List.of(), "Endpoint checked by a sync; leave empty to disable sync"),
                        new Field("authType", "Authentication", "select", true, List.of("NONE", "BEARER", "BASIC", "HEADER"), null),
                        new Field("username", "User name", "text", false, List.of(), "Basic authentication only"),
                        new Field("headerName", "Header name", "text", false, List.of(), "Custom header authentication only, e.g. X-Api-Key")),
                "Token, password or header value", false, true, "Checks that the sync endpoint answers; no records are exchanged in Phase 1.");
    }

    @Override
    public List<String> validate(Map<String, String> c, boolean secretPresent) {
        List<String> errors = new ArrayList<>();
        try {
            guard.checkUrl(c.get("baseUrl"), "base URL");
        } catch (ApiException e) {
            errors.add(e.getMessage());
        }
        for (String p : new String[] {"healthPath", "syncPath"}) {
            String v = c.get(p);
            if (v != null && !v.isBlank() && !v.matches("/[A-Za-z0-9/_.~?=&%-]*")) errors.add("The " + p + " must start with / and contain only URL path characters.");
        }
        String auth = c.getOrDefault("authType", "NONE");
        if (!List.of("NONE", "BEARER", "BASIC", "HEADER").contains(auth)) errors.add("Choose an authentication type.");
        if (!auth.equals("NONE") && !secretPresent) errors.add("The selected authentication needs a secret.");
        if (auth.equals("BASIC") && blank(c.get("username"))) errors.add("Basic authentication needs a user name.");
        if (auth.equals("HEADER") && (blank(c.get("headerName")) || !c.get("headerName").matches("[A-Za-z0-9-]{1,60}"))) {
            errors.add("Custom header authentication needs a valid header name.");
        }
        return errors;
    }

    @Override
    public Outcome test(Map<String, String> c, String secret) {
        return call(c, secret, c.getOrDefault("healthPath", "/"), false);
    }

    @Override
    public Outcome sync(Map<String, String> c, String secret) {
        if (blank(c.get("syncPath"))) return new Outcome(false, "No sync path is configured.");
        return call(c, secret, c.get("syncPath"), true);
    }

    private Outcome call(Map<String, String> c, String secret, String path, boolean sync) {
        URI base = guard.checkUrl(c.get("baseUrl"), "base URL");   // re-checked at call time (DNS may have changed)
        String p = blank(path) ? "/" : path;
        URI target = URI.create(base.toString().replaceAll("/+$", "") + p);
        HttpRequest.Builder r = HttpRequest.newBuilder(target).timeout(props.requestTimeout()).GET().header("User-Agent", "Ivorovi-Integration/1.0")
                .header("Accept", "application/json, */*;q=0.5");
        switch (c.getOrDefault("authType", "NONE")) {
            case "BEARER" -> r.header("Authorization", "Bearer " + secret);
            case "BASIC" -> r.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((c.get("username") + ":" + secret).getBytes(StandardCharsets.UTF_8)));
            case "HEADER" -> r.header(c.get("headerName"), secret);
            default -> { }
        }
        try {
            HttpResponse<byte[]> res = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
            int s = res.statusCode();
            if (s >= 200 && s < 300) {
                return new Outcome(true, sync ? "Sync endpoint answered HTTP " + s + " with " + res.body().length + " bytes (connectivity only; no data imported)."
                        : "Connected: HTTP " + s + ".");
            }
            if (s == 401 || s == 403) return new Outcome(false, "The service refused the credentials (HTTP " + s + ").");
            if (s >= 300 && s < 400) return new Outcome(false, "The service answered with a redirect (HTTP " + s + "); redirects are not followed. Use the final URL.");
            return new Outcome(false, "The service answered HTTP " + s + ".");
        } catch (java.net.http.HttpTimeoutException e) {
            return new Outcome(false, "The service did not answer within " + props.requestTimeout().toSeconds() + " s.");
        } catch (java.io.IOException e) {
            return new Outcome(false, "Could not connect: " + e.getClass().getSimpleName() + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, "Interrupted.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
