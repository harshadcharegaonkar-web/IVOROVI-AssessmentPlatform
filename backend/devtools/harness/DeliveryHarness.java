/*
 * Delivery load and failure harness (Increment 2.0-F). JDK only: no library, no build step (Q-11).
 *
 *   java backend/devtools/harness/DeliveryHarness.java --candidates 2000 --duration PT10M [options]
 *
 * What it does, repeatably:
 *  1. Serves its own JWKS (an RSA key generated per run) and signs candidate tokens with the candidate realm's claim shape
 *     (issuer, audience ivorovi-delivery, verified email). The delivery process under test is started with
 *     IVOROVI_DELIVERY_OIDC_ISSUER/JWKS pointing here (configuration only; no code path differs from production).
 *  2. Sets up a portal, a readiness session and one single-use code per candidate through the real staff API
 *     (staff token from the dev realm).
 *  3. Ramps up N virtual candidates (virtual threads). Each signs in to the portal (enrolment), checks in with its own
 *     code, then sends presence heartbeats and acknowledged practice saves until the end.
 *  4. Reports per-endpoint p50/p95/p99/max, throughput, errors by status, lost acknowledgements (saves the server stored
 *     whose acknowledgement the client never received, from the staff check-in view), a 10-second timeline, and resource
 *     usage of the delivery process sampled from /proc (--pid: CPU, resident memory, threads) and, when available,
 *     `docker stats` of the infrastructure containers.
 *
 * Options (defaults in brackets): --delivery [http://localhost:8091] --staff [http://localhost:8080]
 *   --keycloak [http://localhost:8180] --staff-user [alice.admin@aurora.test] (password from DEV_USER_PASSWORD)
 *   --candidates [2000] --duration [PT10M] --ramp [PT60S] --heartbeat [PT15S] --save [PT10S] --jwks-port [8099]
 *   --out [docs/performance/delivery-harness.json] --label [run] --docker [ivorovi-postgres-1,ivorovi-valkey-1]
 */
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeliveryHarness {

    static final String ISSUER_PATH = "/realms/harness-candidates";
    static String delivery, staff, keycloak, staffUser, out, label;
    static int candidates, jwksPort;
    static Duration duration, ramp, heartbeat, save;
    static List<String> dockerContainers;

    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static KeyPair keys;
    static String kid = UUID.randomUUID().toString();

    // ------------------------------------------------------------------------------------------------ metrics

    static final Map<String, ConcurrentLinkedQueue<Long>> latencies = new ConcurrentHashMap<>();
    static final Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
    static final LongAdder savesAcked = new LongAdder();
    static final LongAdder savesFailed = new LongAdder();
    static final LongAdder checkInsOk = new LongAdder();
    static final Map<Long, long[]> timeline = new ConcurrentHashMap<>(); // window → [requests, errors, latencySum]
    static volatile long started;
    static final List<Map<String, Double>> samples = Collections.synchronizedList(new ArrayList<>());
    static final List<String> dockerSamples = Collections.synchronizedList(new ArrayList<>());

    record Res(int status, String body) {}

    static Res call(String endpoint, String method, String url, String token, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (json != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        long t0 = System.nanoTime();
        int status;
        String body;
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            status = r.statusCode();
            body = r.body();
        } catch (IOException e) {
            status = -1;
            body = e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = -2;
            body = "interrupted";
        }
        long micros = (System.nanoTime() - t0) / 1000;
        if (endpoint != null) {
            latencies.computeIfAbsent(endpoint, k -> new ConcurrentLinkedQueue<>()).add(micros);
            statuses.computeIfAbsent(endpoint + " " + (status < 0 ? body : String.valueOf(status)), k -> new LongAdder()).increment();
            long w = (System.currentTimeMillis() - started) / 10_000;
            long[] slot = timeline.computeIfAbsent(w, k -> new long[3]);
            synchronized (slot) {
                slot[0]++;
                if (status < 200 || status >= 300) slot[1]++;
                slot[2] += micros;
            }
        }
        return new Res(status, body);
    }

    // ------------------------------------------------------------------------------------------------ tokens

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String unsigned(BigInteger i) {
        byte[] b = i.toByteArray();
        if (b[0] == 0) b = java.util.Arrays.copyOfRange(b, 1, b.length);
        return b64(b);
    }

    static String candidateToken(String sub, int n) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String claims = "{\"iss\":\"http://localhost:" + jwksPort + ISSUER_PATH + "\",\"aud\":[\"ivorovi-delivery\"],\"sub\":\"" + sub + "\","
                + "\"email\":\"vc" + n + "." + label + "@harness.test\",\"email_verified\":true,\"name\":\"Virtual Candidate " + n + "\","
                + "\"iat\":" + now + ",\"exp\":" + (now + duration.toSeconds() + 3600) + "}";
        String input = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(keys.getPrivate());
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(s.sign());
    }

    static void serveJwks() throws IOException {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + kid + "\",\"n\":\"" + unsigned(pub.getModulus())
                + "\",\"e\":\"" + unsigned(pub.getPublicExponent()) + "\"}]}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", jwksPort), 0);
        server.createContext(ISSUER_PATH + "/protocol/openid-connect/certs", ex -> {
            byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    // ------------------------------------------------------------------------------------------------ setup through the staff API

    static String staffToken() {
        String pw = System.getenv("DEV_USER_PASSWORD");
        if (pw == null) throw new IllegalStateException("DEV_USER_PASSWORD must be set");
        String form = "grant_type=password&client_id=ivorovi-dev-cli&scope=openid&username=" + URLEncoder.encode(staffUser, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(pw, StandardCharsets.UTF_8);
        HttpRequest r = HttpRequest.newBuilder(URI.create(keycloak + "/realms/ivorovi/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            return field(HTTP.send(r, HttpResponse.BodyHandlers.ofString()).body(), "access_token");
        } catch (Exception e) {
            throw new IllegalStateException("staff token: " + e);
        }
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("no " + name + " in " + json.substring(0, Math.min(300, json.length())));
        return m.group(1);
    }

    static Res staffCall(String token, String method, String path, String json) {
        Res r = call(null, method, staff + path, token, json);
        if (r.status() < 200 || r.status() >= 300) throw new IllegalStateException(method + " " + path + " -> " + r.status() + " " + r.body());
        return r;
    }

    // ------------------------------------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        delivery = a.getOrDefault("delivery", "http://localhost:8091");
        staff = a.getOrDefault("staff", "http://localhost:8080");
        keycloak = a.getOrDefault("keycloak", "http://localhost:8180");
        staffUser = a.getOrDefault("staff-user", "alice.admin@aurora.test");
        candidates = Integer.parseInt(a.getOrDefault("candidates", "2000"));
        duration = Duration.parse(a.getOrDefault("duration", "PT10M"));
        ramp = Duration.parse(a.getOrDefault("ramp", "PT60S"));
        heartbeat = Duration.parse(a.getOrDefault("heartbeat", "PT15S"));
        save = Duration.parse(a.getOrDefault("save", "PT10S"));
        jwksPort = Integer.parseInt(a.getOrDefault("jwks-port", "8099"));
        out = a.getOrDefault("out", "docs/performance/delivery-harness.json");
        label = a.getOrDefault("label", "run");
        dockerContainers = List.of(a.getOrDefault("docker", "ivorovi-postgres-1,ivorovi-valkey-1").split(","));
        pid = Long.parseLong(a.getOrDefault("pid", "0"));

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        serveJwks();
        System.out.printf("JWKS ready at http://localhost:%d%s (issuer http://localhost:%d%s)%n", jwksPort, ISSUER_PATH + "/protocol/openid-connect/certs", jwksPort, ISSUER_PATH);
        waitHealthy(Duration.ofMinutes(5));

        // setup: portal (MFA off: the harness measures delivery, not the identity provider), session, one code per candidate
        String token = staffToken();
        String key = "harness-" + label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + "-" + Long.toString(System.currentTimeMillis(), 36);
        key = key.length() > 40 ? key.substring(0, 40) : key;
        String portalId = field(staffCall(token, "POST", "/api/v1/exam/portals", "{\"portalKey\":\"" + key + "\",\"name\":\"Load harness " + label
                + "\",\"mfaPolicy\":\"OFF\",\"selfRegistration\":true}").body(), "id");
        Instant now = Instant.now();
        String sessionId = field(staffCall(token, "POST", "/api/v1/exam/sessions", "{\"portalId\":\"" + portalId + "\",\"name\":\"Harness " + label
                + "\",\"startsAt\":\"" + now.minusSeconds(300) + "\",\"endsAt\":\"" + now.plus(duration).plus(ramp).plusSeconds(1800) + "\"}").body(), "id");
        List<String> codes = new ArrayList<>();
        while (codes.size() < candidates) {
            int n = Math.min(500, candidates - codes.size());
            Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z0-9-]{11})\"").matcher(staffCall(token, "POST", "/api/v1/exam/sessions/" + sessionId + "/codes",
                    "{\"count\":" + n + "}").body());
            while (m.find()) codes.add(m.group(1));
        }
        System.out.printf("Setup: portal %s, session %s, %d codes%n", key, sessionId, codes.size());

        // resource sampling
        ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor();
        started = System.currentTimeMillis();
        long end = started + ramp.toMillis() + duration.toMillis();
        vts.submit(() -> sample(end));

        CountDownLatch done = new CountDownLatch(candidates);
        String base = delivery + "/api/v1/delivery/portals/" + key;
        for (int i = 0; i < candidates; i++) {
            int n = i;
            long startAt = started + (ramp.toMillis() * i) / Math.max(1, candidates);
            vts.submit(() -> {
                try {
                    candidate(n, base, codes.get(n), sessionId, startAt, end);
                } catch (Exception e) {
                    statuses.computeIfAbsent("candidate exception " + e.getClass().getSimpleName(), k -> new LongAdder()).increment();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        long elapsed = System.currentTimeMillis() - started;

        // server-side truth: saves the server stored vs acknowledgements received
        long serverSaves = 0;
        long serverBeats = 0;
        String ci = staffCall(staffToken(), "GET", "/api/v1/exam/sessions/" + sessionId + "/check-ins", null).body();
        Matcher ms = Pattern.compile("\"saves\"\\s*:\\s*(\\d+)").matcher(ci);
        while (ms.find()) serverSaves += Long.parseLong(ms.group(1));
        Matcher mb = Pattern.compile("\"beats\"\\s*:\\s*(\\d+)").matcher(ci);
        while (mb.find()) serverBeats += Long.parseLong(mb.group(1));
        report(elapsed, serverSaves, serverBeats, key);
        vts.shutdownNow();
        System.exit(0);
    }

    static void waitHealthy(Duration max) throws InterruptedException {
        long until = System.currentTimeMillis() + max.toMillis();
        while (System.currentTimeMillis() < until) {
            if (call(null, "GET", delivery + "/actuator/health/readiness", null, null).status() == 200) return;
            Thread.sleep(1000);
        }
        throw new IllegalStateException("delivery process not healthy at " + delivery);
    }

    static void candidate(int n, String base, String code, String sessionId, long startAt, long end) throws Exception {
        sleepUntil(startAt);
        String token = candidateToken(UUID.randomUUID().toString(), n);
        // sign-in (enrol) and check-in, retried while the service is unavailable (as the exam app does)
        while (System.currentTimeMillis() < end && call("GET /me", "GET", base + "/me", token, null).status() != 200) Thread.sleep(2000);
        while (System.currentTimeMillis() < end) {
            Res r = call("POST /check-ins", "POST", base + "/check-ins", token, "{\"code\":\"" + code + "\",\"clockOffsetMs\":0,\"systemCheck\":{\"browser\":\"harness\"}}");
            if (r.status() == 200 || (r.status() == 409 && r.body().contains("already_checked_in"))) { checkInsOk.increment(); break; }
            Thread.sleep(2000);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long nextBeat = System.currentTimeMillis() + rnd.nextLong(heartbeat.toMillis());
        long nextSave = System.currentTimeMillis() + rnd.nextLong(save.toMillis());
        long seq = 0;
        String payload = "x".repeat(200);
        while (true) {
            long t = Math.min(nextBeat, nextSave);
            if (t >= end) break;
            sleepUntil(t);
            if (nextBeat <= nextSave) {
                call("POST /presence", "POST", base + "/presence", token, "{\"sessionId\":\"" + sessionId + "\"}");
                nextBeat += heartbeat.toMillis();
            } else {
                seq++;
                Res r = call("POST /saves", "POST", base + "/saves", token, "{\"sessionId\":\"" + sessionId + "\",\"seq\":" + seq + ",\"payload\":\"" + payload + "\"}");
                if (r.status() == 200 && r.body().contains("\"seq\":" + seq)) savesAcked.increment(); else savesFailed.increment();
                nextSave += save.toMillis();
            }
        }
    }

    static void sleepUntil(long t) throws InterruptedException {
        long d = t - System.currentTimeMillis();
        if (d > 0) Thread.sleep(d);
    }

    // ------------------------------------------------------------------------------------------------ resources

    static long pid;
    static long lastCpuTicks = -1;
    static long lastCpuAt;

    /** Delivery process resources from /proc (CPU of all cores, resident memory, threads); infrastructure via docker stats. */
    static void sample(long end) {
        while (System.currentTimeMillis() < end + 2000) {
            if (pid > 0) {
                try {
                    String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
                    String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                    long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);   // utime + stime
                    long now = System.nanoTime();
                    Map<String, Double> s = new LinkedHashMap<>();
                    s.put("t", (System.currentTimeMillis() - started) / 1000.0);
                    if (lastCpuTicks >= 0) {
                        double seconds = (now - lastCpuAt) / 1e9;
                        double cores = Runtime.getRuntime().availableProcessors();
                        s.put("processCpu", (ticks - lastCpuTicks) / 100.0 / seconds / cores);
                    }
                    lastCpuTicks = ticks;
                    lastCpuAt = now;
                    for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
                        if (line.startsWith("VmRSS:")) s.put("heapMb", Double.parseDouble(line.replaceAll("[^0-9]", "")) / 1024);
                        if (line.startsWith("Threads:")) s.put("threads", Double.parseDouble(line.replaceAll("[^0-9]", "")));
                    }
                    if (s.containsKey("processCpu")) samples.add(s);
                } catch (Exception ignored) {
                    // process gone (failure test) or not local
                }
            }
            try {
                List<String> cmd = new ArrayList<>(List.of("docker", "stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}} {{.MemUsage}}"));
                cmd.addAll(dockerContainers);
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (p.waitFor() == 0) dockerSamples.add(String.format("%.0fs %s", (System.currentTimeMillis() - started) / 1000.0, text.replace('\n', '|')));
            } catch (Exception ignored) {
                // docker not available
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ report

    static long pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }

    static void report(long elapsedMs, long serverSaves, long serverBeats, String portal) throws IOException {
        StringBuilder json = new StringBuilder("{\n");
        json.append(String.format("  \"label\": \"%s\", \"date\": \"%s\", \"candidates\": %d, \"durationSeconds\": %d, \"rampSeconds\": %d, \"heartbeatSeconds\": %d, \"saveSeconds\": %d,%n",
                label, Instant.now(), candidates, duration.toSeconds(), ramp.toSeconds(), heartbeat.toSeconds(), save.toSeconds()));
        json.append(String.format("  \"portal\": \"%s\", \"elapsedSeconds\": %.1f, \"checkedIn\": %d,%n", portal, elapsedMs / 1000.0, checkInsOk.sum()));
        long total = 0, errors = 0;
        System.out.printf("%n%-18s %9s %8s %8s %8s %8s %8s%n", "endpoint", "requests", "p50 ms", "p95 ms", "p99 ms", "max ms", "req/s");
        json.append("  \"endpoints\": {\n");
        List<String> eps = new ArrayList<>(new TreeMap<>(latencies).keySet());
        for (int i = 0; i < eps.size(); i++) {
            String e = eps.get(i);
            List<Long> l = new ArrayList<>(latencies.get(e));
            Collections.sort(l);
            total += l.size();
            double rps = l.size() / (elapsedMs / 1000.0);
            System.out.printf("%-18s %9d %8.1f %8.1f %8.1f %8.1f %8.1f%n", e, l.size(), pct(l, .5) / 1000.0, pct(l, .95) / 1000.0, pct(l, .99) / 1000.0,
                    l.isEmpty() ? 0 : l.get(l.size() - 1) / 1000.0, rps);
            json.append(String.format(Locale.ROOT, "    \"%s\": {\"requests\": %d, \"p50Ms\": %.1f, \"p95Ms\": %.1f, \"p99Ms\": %.1f, \"maxMs\": %.1f, \"perSecond\": %.1f}%s%n", e, l.size(),
                    pct(l, .5) / 1000.0, pct(l, .95) / 1000.0, pct(l, .99) / 1000.0, l.isEmpty() ? 0 : l.get(l.size() - 1) / 1000.0, rps, i < eps.size() - 1 ? "," : ""));
        }
        json.append("  },\n  \"statuses\": {");
        List<String> st = new ArrayList<>(new TreeMap<>(statuses).keySet());
        for (int i = 0; i < st.size(); i++) {
            String s = st.get(i);
            long c = statuses.get(s).sum();
            if (!s.matches(".* 2\\d\\d$")) errors += c;
            json.append(String.format("%s\"%s\": %d", i == 0 ? "" : ", ", s, c));
        }
        long lostAcks = Math.max(0, serverSaves - savesAcked.sum());
        System.out.printf("%ntotal %d requests, %.1f req/s, errors %d (%.3f%%)%n", total, total / (elapsedMs / 1000.0), errors, total == 0 ? 0 : 100.0 * errors / total);
        System.out.printf("checked in %d/%d; saves acknowledged %d, failed %d, stored by server %d, lost acknowledgements %d; server heartbeats %d%n",
                checkInsOk.sum(), candidates, savesAcked.sum(), savesFailed.sum(), serverSaves, lostAcks, serverBeats);
        json.append(String.format(Locale.ROOT, "},%n  \"totalRequests\": %d, \"requestsPerSecond\": %.1f, \"errors\": %d, \"errorRatePercent\": %.4f,%n", total,
                total / (elapsedMs / 1000.0), errors, total == 0 ? 0 : 100.0 * errors / total));
        json.append(String.format("  \"saves\": {\"acknowledged\": %d, \"failed\": %d, \"storedByServer\": %d, \"lostAcknowledgements\": %d}, \"serverHeartbeats\": %d,%n",
                savesAcked.sum(), savesFailed.sum(), serverSaves, lostAcks, serverBeats));
        // resources
        double cpuMax = 0, cpuSum = 0, heapMax = 0, thrMax = 0, dbMax = 0, dbPendMax = 0;
        for (Map<String, Double> s : samples) {
            double c = s.getOrDefault("processCpu", 0.0);
            cpuMax = Math.max(cpuMax, c);
            cpuSum += c;
            heapMax = Math.max(heapMax, s.getOrDefault("heapMb", 0.0));
            thrMax = Math.max(thrMax, s.getOrDefault("threads", 0.0));
            dbMax = Math.max(dbMax, s.getOrDefault("dbActive", 0.0));
            dbPendMax = Math.max(dbPendMax, s.getOrDefault("dbPending", 0.0));
        }
        System.out.printf(Locale.ROOT, "delivery process: CPU avg %.0f%% max %.0f%% (of all cores), resident memory max %.0f MB, threads max %.0f%n",
                samples.isEmpty() ? 0 : 100 * cpuSum / samples.size(), 100 * cpuMax, heapMax, thrMax);
        json.append(String.format(Locale.ROOT, "  \"deliveryProcess\": {\"samples\": %d, \"cores\": %d, \"cpuAvgPercent\": %.1f, \"cpuMaxPercent\": %.1f, \"rssMaxMb\": %.0f, \"threadsMax\": %.0f},%n",
                samples.size(), Runtime.getRuntime().availableProcessors(), samples.isEmpty() ? 0 : 100 * cpuSum / samples.size(), 100 * cpuMax, heapMax, thrMax));
        json.append("  \"docker\": [");
        synchronized (dockerSamples) {
            for (int i = 0; i < dockerSamples.size(); i += Math.max(1, dockerSamples.size() / 12)) {
                json.append(i == 0 ? "" : ", ").append('"').append(dockerSamples.get(i).replace("\"", "'")).append('"');
            }
        }
        json.append("],\n  \"timeline\": [");
        List<Long> ws = new ArrayList<>(new TreeMap<>(timeline).keySet());
        System.out.println("\ntimeline (10 s windows): start s, requests, errors, mean ms");
        for (int i = 0; i < ws.size(); i++) {
            long[] v = timeline.get(ws.get(i));
            double mean = v[0] == 0 ? 0 : v[2] / 1000.0 / v[0];
            System.out.printf("  %4d %7d %6d %8.1f%n", ws.get(i) * 10, v[0], v[1], mean);
            json.append(String.format(Locale.ROOT, "%s{\"t\": %d, \"requests\": %d, \"errors\": %d, \"meanMs\": %.1f}", i == 0 ? "" : ", ", ws.get(i) * 10, v[0], v[1], mean));
        }
        json.append("]\n}\n");
        Path p = Path.of(out);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, json.toString());
        System.out.println("\nwritten " + p.toAbsolutePath());
    }
}
