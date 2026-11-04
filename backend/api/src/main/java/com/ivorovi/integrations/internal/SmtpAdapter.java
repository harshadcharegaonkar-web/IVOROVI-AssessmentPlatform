package com.ivorovi.integrations.internal;

import com.ivorovi.platform.web.ApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

/**
 * Vendor-neutral email-provider adapter: checks that an SMTP relay answers (greeting, EHLO and, for STARTTLS, that it
 * offers encryption). It does not send mail and does not replace the platform mail configuration; Phase 1 uses it to
 * verify a provider before an administrator switches to it.
 */
@Component
class SmtpAdapter implements IntegrationAdapter {

    static final String KEY = "smtp";

    private final OutboundGuard guard;
    private final IntegrationsProperties props;

    SmtpAdapter(OutboundGuard guard, IntegrationsProperties props) {
        this.guard = guard;
        this.props = props;
    }

    @Override
    public Info info() {
        return new Info(KEY, "SMTP email provider", "Checks that an SMTP relay answers and offers the selected encryption. No email is sent.",
                List.of(Kind.EMAIL),
                List.of(new Field("host", "Host", "text", true, List.of(), "For example smtp.example.edu"),
                        new Field("port", "Port", "number", true, List.of(), "587 (STARTTLS), 465 (TLS) or 25"),
                        new Field("security", "Encryption", "select", true, List.of("STARTTLS", "TLS", "NONE"), null),
                        new Field("username", "User name", "text", false, List.of(), "Stored for the provider; not verified by the check")),
                "Password", false, false, null);
    }

    @Override
    public List<String> validate(Map<String, String> c, boolean secretPresent) {
        List<String> errors = new ArrayList<>();
        String host = c.get("host");
        if (host == null || !host.matches("[A-Za-z0-9.-]{1,253}")) errors.add("Enter a valid host name.");
        else {
            try {
                guard.checkHost(host, "host");
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
        }
        try {
            int port = Integer.parseInt(c.getOrDefault("port", ""));
            if (port < 1 || port > 65535) errors.add("Enter a port between 1 and 65535.");
        } catch (NumberFormatException e) {
            errors.add("Enter a port between 1 and 65535.");
        }
        if (!List.of("STARTTLS", "TLS", "NONE").contains(c.getOrDefault("security", ""))) errors.add("Choose the encryption.");
        return errors;
    }

    @Override
    public Outcome test(Map<String, String> c, String secret) {
        String host = c.get("host");
        guard.checkHost(host, "host");
        int port = Integer.parseInt(c.get("port"));
        int timeout = (int) props.requestTimeout().toMillis();
        String security = c.get("security");
        try (Socket socket = security.equals("TLS") ? SSLSocketFactory.getDefault().createSocket() : new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = socket.getOutputStream();
            List<String> greeting = reply(in);
            if (!greeting.getLast().startsWith("220")) return new Outcome(false, "Unexpected greeting from the server.");
            out.write("EHLO ivorovi.check\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            List<String> ehlo = reply(in);
            if (!ehlo.getLast().startsWith("250")) return new Outcome(false, "The server rejected EHLO.");
            boolean starttls = ehlo.stream().anyMatch(l -> l.length() > 4 && l.substring(4).trim().equalsIgnoreCase("STARTTLS"));
            out.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (security.equals("STARTTLS") && !starttls) return new Outcome(false, "The server does not offer STARTTLS.");
            return new Outcome(true, "SMTP server answered" + (security.equals("NONE") ? " (unencrypted)" : security.equals("TLS") ? " over TLS" : "; STARTTLS offered") + ".");
        } catch (java.net.SocketTimeoutException e) {
            return new Outcome(false, "The server did not answer within " + props.requestTimeout().toSeconds() + " s.");
        } catch (IOException e) {
            return new Outcome(false, "Could not connect: " + e.getClass().getSimpleName() + ".");
        }
    }

    private static List<String> reply(BufferedReader in) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            lines.add(line);
            if (line.length() < 4 || line.charAt(3) != '-') break;
            if (lines.size() > 50) break;
        }
        if (lines.isEmpty()) throw new IOException("closed");
        return lines;
    }
}
