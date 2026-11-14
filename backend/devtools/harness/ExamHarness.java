/*
 * Examination load and resilience harness (Day 4 P4). JDK only, like DeliveryHarness (Q-11): no library, no build step.
 *
 *   java backend/devtools/harness/ExamHarness.java --candidates 300 --minutes 3 [options]
 *
 * Unlike DeliveryHarness (readiness sessions and practice saves), every virtual candidate takes a REAL examination through
 * the real APIs:
 *  1. Setup through the staff API with the dev realm's seeded staff (four-eyes respected): a question bank with N approved
 *     single-choice questions, a published assessment of the given duration, an exam portal, a schedule open now, the
 *     candidates enrolled by CSV upload, one single-use session code per candidate.
 *  2. Candidate tokens are signed by this harness (its own JWKS, the candidate realm's claim shape); the delivery process
 *     under test runs with IVOROVI_DELIVERY_OIDC_ISSUER/JWKS pointing here (configuration only, same code path).
 *  3. All candidates come from ONE network address (this machine): the shared-campus-NAT worst case for check-in limits.
 *     Each signs in (enrolment linked), checks in with its code, starts, answers every question and keeps changing answers,
 *     retrying every save until the server acknowledges it (as the exam app does). It records every acknowledgement.
 *     Behaviours, by candidate number: every 10th goes offline for 20 s mid-exam (saves held, then resent); every 7th sends
 *     two concurrent submissions plus a replay; every 5th never submits (left to automatic submission at the deadline);
 *     the rest submit near the end.
 *  4. Optionally kills the delivery process (SIGKILL) mid-exam and starts it again (--kill-cmd / --start-cmd).
 *  5. After the deadline: waits for automatic submission and scoring, then verifies, per candidate, that every acknowledged
 *     answer is stored with at least the acknowledged sequence (a lost answer is an acknowledged save the server does not
 *     have), that each attempt was submitted exactly once, and races two concurrent result reviews and two concurrent
 *     approvals of the same review (exactly one of each must win; publication only once).
 *  6. Writes a JSON report with per-endpoint p50/p95/p99/max, status counts, and the verification counts.
 *
 * Options [defaults]: --delivery [http://localhost:8091] --staff [http://localhost:8080] --keycloak [http://localhost:8180]
 *   --candidates [300] --questions [10] --minutes [3] --jwks-port [8099] --kill-at [none, e.g. PT60S] --kill-cmd --start-cmd
 *   --out [docs/performance/exam-harness.json] --label [run]   (staff password from DEV_USER_PASSWORD)
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
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExamHarness {

    static final String ISSUER_PATH = "/realms/harness-candidates";
    static final String AUTHOR = "alice.admin@aurora.test", FACULTY = "arun.faculty@aurora.test", HEAD = "ines.instadmin@aurora.test",
            CONTROLLER = "eshan.controller@aurora.test";
    static final String COLLEGE = "0192a000-0000-7000-8000-00000000a101";
    static String delivery, staff, keycloak, out, label, killCmd, startCmd;
    static int candidates, questions, minutes, jwksPort;
    static Duration killAt;

    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static KeyPair keys;
    static final String kid = UUID.randomUUID().toString();

    static final Map<String, ConcurrentLinkedQueue<Long>> latencies = new ConcurrentHashMap<>();
    static final Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
    static final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    static final List<String> problems = Collections.synchronizedList(new ArrayList<>());

    record Res(int status, String body) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    static void count(String k) {
        counters.computeIfAbsent(k, x -> new LongAdder()).increment();
    }

    static Res call(String endpoint, String method, String url, String token, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
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
        if (endpoint != null) {
            latencies.computeIfAbsent(endpoint, k -> new ConcurrentLinkedQueue<>()).add((System.nanoTime() - t0) / 1000);
            statuses.computeIfAbsent(endpoint + " " + (status < 0 ? body : String.valueOf(status)), k -> new LongAdder()).increment();
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

    static String email(int n) {
        return "vc" + n + "." + label + "@harness.test";
    }

    static String candidateToken(String sub, int n) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String claims = "{\"iss\":\"http://localhost:" + jwksPort + ISSUER_PATH + "\",\"aud\":[\"ivorovi-delivery\"],\"sub\":\"" + sub + "\","
                + "\"email\":\"" + email(n) + "\",\"email_verified\":true,\"name\":\"Virtual Candidate " + n + "\","
                + "\"iat\":" + now + ",\"exp\":" + (now + 3 * 3600) + "}";
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

    // ------------------------------------------------------------------------------------------------ staff API

    static final Map<String, String> staffTokens = new ConcurrentHashMap<>();

    static String staffToken(String user) {
        return staffTokens.computeIfAbsent(user, u -> {
            String pw = System.getenv("DEV_USER_PASSWORD");
            if (pw == null) throw new IllegalStateException("DEV_USER_PASSWORD must be set");
            String form = "grant_type=password&client_id=ivorovi-dev-cli&scope=openid&username=" + URLEncoder.encode(u, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(pw, StandardCharsets.UTF_8);
            HttpRequest r = HttpRequest.newBuilder(URI.create(keycloak + "/realms/ivorovi/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
            try {
                return field(HTTP.send(r, HttpResponse.BodyHandlers.ofString()).body(), "access_token");
            } catch (Exception e) {
                throw new IllegalStateException("staff token: " + e);
            }
        });
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("no " + name + " in " + json.substring(0, Math.min(400, json.length())));
        return m.group(1);
    }

    static long number(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("no " + name + " in " + json.substring(0, Math.min(400, json.length())));
        return Long.parseLong(m.group(1));
    }

    static Res staffCall(String user, String method, String path, String json) {
        Res r = call(null, method, staff + path, staffToken(user), json);
        if (!r.ok()) throw new IllegalStateException(method + " " + path + " -> " + r.status() + " " + r.body());
        return r;
    }

    static String content(String text) {
        return "{\"format\":\"ivorovi.content.v1\",\"blocks\":[{\"type\":\"paragraph\",\"runs\":[{\"text\":\"" + text + "\"}]}]}";
    }

    /** Bank, approved questions (correct answer B), published assessment; returns [versionId, portalId, portalKey, scheduleId]. */
    static String[] setup() {
        String bank = field(staffCall(AUTHOR, "POST", "/api/v1/question-banks", "{\"orgUnitId\":\"" + COLLEGE + "\",\"code\":\"H" + label + "\",\"name\":\"Harness "
                + label + "\",\"intendedUses\":[\"ACADEMIC\"],\"policy\":{\"approvalPolicy\":\"STANDARD\",\"requiredReviews\":1,\"approverDistinct\":false}}").body(), "id");
        List<String> versions = new ArrayList<>();
        for (int i = 1; i <= questions; i++) {
            String q = field(staffCall(FACULTY, "POST", "/api/v1/question-banks/" + bank + "/questions", "{\"type\":\"SINGLE_CHOICE\",\"content\":"
                    + content("Harness " + label + " question " + i) + ",\"marks\":1,\"body\":{\"options\":[{\"id\":\"A\",\"content\":" + content("alpha")
                    + "},{\"id\":\"B\",\"content\":" + content("beta") + "},{\"id\":\"C\",\"content\":" + content("gamma") + "}]},\"answerKey\":{\"correct\":\"B\"}}").body(), "id");
            staffCall(FACULTY, "POST", "/api/v1/questions/" + q + "/submit", "{}");
            staffCall(HEAD, "POST", "/api/v1/questions/" + q + "/reviews", "{\"decision\":\"REVIEWED\",\"checklist\":{\"accurate\":true}}");
            String approved = staffCall(CONTROLLER, "POST", "/api/v1/questions/" + q + "/approve", "{\"reason\":\"harness\"}").body();
            Matcher m = Pattern.compile("\"(?:approved|current)\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(approved);
            if (!m.find()) throw new IllegalStateException("approved version: " + approved);
            versions.add(m.group(1));
        }
        String a = staffCall(AUTHOR, "POST", "/api/v1/assessments", "{\"orgUnitId\":\"" + COLLEGE + "\",\"code\":\"HA-" + label + "\",\"purpose\":\"INTERNAL\",\"title\":\"Harness "
                + label + "\",\"durationMinutes\":" + minutes + "}").body();
        String aid = field(a, "id");
        long lock = number(a.substring(a.indexOf("\"current\"")), "version");
        StringBuilder items = new StringBuilder();
        for (String v : versions) items.append(items.length() == 0 ? "" : ",").append("{\"questionVersionId\":\"").append(v).append("\"}");
        staffCall(AUTHOR, "PUT", "/api/v1/assessments/" + aid + "/draft", "{\"title\":\"Harness " + label + "\",\"durationMinutes\":" + minutes
                + ",\"shuffleOptions\":true,\"items\":[" + items + "],\"version\":" + lock + "}");
        staffCall(AUTHOR, "POST", "/api/v1/assessments/" + aid + "/submit", "{}");
        staffCall(HEAD, "POST", "/api/v1/assessments/" + aid + "/reviews", "{\"decision\":\"REVIEWED\"}");
        String p = staffCall(CONTROLLER, "POST", "/api/v1/assessments/" + aid + "/publish", "{\"reason\":\"harness\"}").body();
        String version = field(p.substring(p.indexOf("\"current\"")), "id");
        String key = "h-" + label.toLowerCase(Locale.ROOT);
        String portal = field(staffCall(CONTROLLER, "POST", "/api/v1/exam/portals", "{\"portalKey\":\"" + key + "\",\"name\":\"Harness " + label
                + "\",\"mfaPolicy\":\"OFF\",\"selfRegistration\":true}").body(), "id");
        Instant now = Instant.now();
        String schedule = field(staffCall(CONTROLLER, "POST", "/api/v1/exam/schedules", "{\"versionId\":\"" + version + "\",\"portalId\":\"" + portal
                + "\",\"orgUnitId\":\"" + COLLEGE + "\",\"opensAt\":\"" + now.minusSeconds(60) + "\",\"closesAt\":\"" + now.plus(Duration.ofHours(2)) + "\"}").body(), "id");
        StringBuilder csv = new StringBuilder();
        for (int n = 0; n < candidates; n++) csv.append(email(n)).append(",Virtual Candidate ").append(n).append("\\n");
        String up = staffCall(CONTROLLER, "POST", "/api/v1/exam/schedules/" + schedule + "/enrolments/upload", "{\"csv\":\"" + csv + "\",\"apply\":true}").body();
        if (number(up, "created") != candidates) throw new IllegalStateException("enrolment: " + up);
        return new String[] {version, portal, key, schedule};
    }

    static List<String> codes(String schedule) {
        List<String> codes = new ArrayList<>();
        for (int done = 0; done < candidates; ) {
            int n = Math.min(2000, candidates - done);
            Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(staffCall(CONTROLLER, "POST", "/api/v1/exam/schedules/" + schedule + "/codes",
                    "{\"count\":" + n + "}").body());
            while (m.find()) codes.add(m.group(1));
            done += n;
        }
        return codes;
    }

    // ------------------------------------------------------------------------------------------------ candidates

    /** What the candidate holds: the latest acknowledged sequence and value per question position. */
    static final class Cand {
        final int n;
        final String sub = UUID.randomUUID().toString();
        String token, attempt;
        final Map<Integer, long[]> acked = new ConcurrentHashMap<>();       // position → [seq, valueIndex]
        final Map<Integer, Long> seqs = new HashMap<>();
        volatile String finalStatus;

        Cand(int n) {
            this.n = n;
        }
    }

    static final String[] VALUES = {"A", "B", "C"};

    /** Saves until acknowledged or the attempt is closed (409): the exam app's behaviour. Returns false if closed. */
    static boolean saveUntilAcked(Cand c, String base, int position, int value, long deadlineMs) throws InterruptedException {
        long seq = c.seqs.merge(position, 1L, Long::sum);
        String body = "{\"seq\":" + seq + ",\"response\":{\"value\":\"" + VALUES[value] + "\"}}";
        long backoff = 250;
        while (System.currentTimeMillis() < deadlineMs + 60_000) {
            Res r = call("PUT answers", "PUT", base + "/attempts/" + c.attempt + "/answers/" + position, c.token, body);
            if (r.ok()) {
                c.acked.merge(position, new long[] {seq, value}, (o, x) -> x[0] > o[0] ? x : o);
                count("saves.acknowledged");
                return true;
            }
            if (r.status() == 409) {
                count("saves.refused_closed");
                return false;
            }
            count("saves.retried");
            Thread.sleep(backoff);
            backoff = Math.min(3000, backoff * 2);
        }
        count("saves.gave_up");
        return false;
    }

    static void candidate(Cand c, String key, String schedule, String code, long endMs) {
        String base = delivery + "/api/v1/delivery/portals/" + key;
        try {
            c.token = candidateToken(c.sub, c.n);
            if (!call("GET me", "GET", base + "/me", c.token, null).ok()) { problems.add("vc" + c.n + " me"); return; }
            call("GET exams", "GET", base + "/exams", c.token, null);
            Res ci = call("POST check-in", "POST", base + "/check-ins", c.token, "{\"code\":\"" + code + "\"}");
            if (!ci.ok()) { count("checkin.failed"); problems.add("vc" + c.n + " check-in " + ci.status() + " " + ci.body()); return; }
            count("checkin.ok");
            Res st = call("POST start", "POST", base + "/exams/" + schedule + "/attempt", c.token, null);
            if (!st.ok()) { count("start.failed"); problems.add("vc" + c.n + " start " + st.status() + " " + st.body()); return; }
            count("start.ok");
            c.attempt = field(st.body(), "id");
            long deadline = Instant.parse(field(st.body(), "deadlineAt")).toEpochMilli();
            boolean offline = c.n % 10 == 3, duplicate = c.n % 7 == 1, abandon = c.n % 5 == 2;
            long stopAt = Math.min(endMs, deadline - 15_000);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            // first pass: answer every question; then keep changing answers until near the end
            for (int p = 1; p <= questions && System.currentTimeMillis() < stopAt; p++) {
                if (!saveUntilAcked(c, base, p, rnd.nextInt(3), deadline)) break;
                Thread.sleep(rnd.nextInt(200, 1500));
            }
            boolean wentOffline = false;
            long midMs = (System.currentTimeMillis() + stopAt) / 2;
            while (System.currentTimeMillis() < stopAt) {
                if (offline && !wentOffline && System.currentTimeMillis() > midMs) {
                    wentOffline = true;
                    count("behaviour.offline_20s");
                    Thread.sleep(20_000);                              // held on the device, resent after reconnecting
                }
                if (!saveUntilAcked(c, base, rnd.nextInt(1, questions + 1), rnd.nextInt(3), deadline)) break;
                Thread.sleep(rnd.nextInt(2000, 6000));
            }
            if (abandon) {
                count("behaviour.left_to_time_up");
                return;
            }
            if (duplicate) {
                count("behaviour.duplicate_submit");
                ExecutorService two = Executors.newVirtualThreadPerTaskExecutor();
                var f1 = two.submit(() -> call("POST submit", "POST", base + "/attempts/" + c.attempt + "/submit", c.token, "{\"submitKey\":\"k" + c.n + "\"}"));
                var f2 = two.submit(() -> call("POST submit", "POST", base + "/attempts/" + c.attempt + "/submit", c.token, "{\"submitKey\":\"k" + c.n + "\"}"));
                Res r1 = f1.get(), r2 = f2.get();
                Res r3 = call("POST submit", "POST", base + "/attempts/" + c.attempt + "/submit", c.token, "{\"submitKey\":\"k" + c.n + "\"}");
                two.shutdown();
                if (!(r1.ok() && r2.ok() && r3.ok() && r1.body().equals(r3.body()) && r2.body().equals(r3.body()))) {
                    count("submit.duplicate_inconsistent");
                    problems.add("vc" + c.n + " duplicate submit " + r1.status() + "/" + r2.status() + "/" + r3.status());
                }
            } else {
                Res r;
                long backoff = 500;
                while (!(r = call("POST submit", "POST", base + "/attempts/" + c.attempt + "/submit", c.token, "{\"submitKey\":\"k" + c.n + "\"}")).ok()
                        && r.status() != 409 && System.currentTimeMillis() < deadline + 60_000) {
                    Thread.sleep(backoff);
                    backoff = Math.min(4000, backoff * 2);
                }
                if (!r.ok()) problems.add("vc" + c.n + " submit " + r.status() + " " + r.body());
            }
        } catch (Exception e) {
            problems.add("vc" + c.n + " " + e);
        }
    }

    // ------------------------------------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        delivery = a.getOrDefault("delivery", "http://localhost:8091");
        staff = a.getOrDefault("staff", "http://localhost:8080");
        keycloak = a.getOrDefault("keycloak", "http://localhost:8180");
        candidates = Integer.parseInt(a.getOrDefault("candidates", "300"));
        questions = Integer.parseInt(a.getOrDefault("questions", "10"));
        minutes = Integer.parseInt(a.getOrDefault("minutes", "3"));
        jwksPort = Integer.parseInt(a.getOrDefault("jwks-port", "8099"));
        killAt = a.containsKey("kill-at") ? Duration.parse(a.get("kill-at")) : null;
        killCmd = a.get("kill-cmd");
        startCmd = a.get("start-cmd");
        out = a.getOrDefault("out", "docs/performance/exam-harness.json");
        label = a.getOrDefault("label", Long.toString(System.currentTimeMillis(), 36)).replaceAll("[^A-Za-z0-9]", "");

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        serveJwks();

        System.out.printf("Setting up %d candidates, %d questions, %d minutes (label %s)%n", candidates, questions, minutes, label);
        String[] s = setup();
        String key = s[2], schedule = s[3];
        List<String> codes = codes(schedule);
        System.out.printf("Schedule %s, portal %s, %d codes%n", schedule, key, codes.size());

        List<Cand> cands = new ArrayList<>();
        for (int n = 0; n < candidates; n++) cands.add(new Cand(n));
        long t0 = System.currentTimeMillis();
        long endMs = t0 + Duration.ofMinutes(minutes).toMillis();
        CountDownLatch done = new CountDownLatch(candidates);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        for (Cand c : cands) {
            String code = codes.get(c.n);
            pool.submit(() -> {
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(0, 20_000));   // arrivals spread over 20 s
                    candidate(c, key, schedule, code, endMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        String restart = "not requested";
        if (killAt != null && killCmd != null && startCmd != null) {
            Thread.sleep(Math.max(0, t0 + killAt.toMillis() - System.currentTimeMillis()));
            long k0 = System.currentTimeMillis();
            new ProcessBuilder("bash", "-c", killCmd).inheritIO().start().waitFor();
            System.out.println("Delivery process killed at +" + (k0 - t0) / 1000 + " s");
            new ProcessBuilder("bash", "-c", startCmd).inheritIO().start().waitFor();
            restart = "killed at +" + (k0 - t0) / 1000 + " s, ready again after " + (System.currentTimeMillis() - k0) / 1000 + " s";
            System.out.println("Delivery process " + restart);
        }
        done.await();
        pool.shutdown();
        long examMs = System.currentTimeMillis() - t0;
        System.out.println("Candidates finished after " + examMs / 1000 + " s; waiting for automatic submission and scoring");

        // automatic submission and scoring (staff results view)
        String results = "";
        long waitUntil = System.currentTimeMillis() + Duration.ofMinutes(minutes + 4).toMillis();
        int started = (int) counters.getOrDefault("start.ok", new LongAdder()).sum();
        while (System.currentTimeMillis() < waitUntil) {
            results = staffCall(CONTROLLER, "GET", "/api/v1/exam/results/" + schedule, null).body();
            if (number(results, "inProgress") == 0 && number(results, "awaitingScore") == 0 && number(results, "provisional") == started) break;
            Thread.sleep(5000);
        }
        long inProgress = number(results, "inProgress"), awaiting = number(results, "awaitingScore"), provisional = number(results, "provisional");
        int auto = count(results, "\"attemptStatus\":\"AUTO_SUBMITTED\""), submitted = count(results, "\"attemptStatus\":\"SUBMITTED\"");

        // verification of every acknowledged answer against what the server stored (candidate's own view of the attempt)
        long ackedTotal = 0, lost = 0, storedNewer = 0;
        for (Cand c : cands) {
            if (c.attempt == null) continue;
            Res v = call(null, "GET", delivery + "/api/v1/delivery/portals/" + key + "/attempts/" + c.attempt, c.token, null);
            if (!v.ok()) { problems.add("vc" + c.n + " view " + v.status()); continue; }
            c.finalStatus = field(v.body(), "status");
            Map<Integer, long[]> stored = new HashMap<>();
            Matcher m = Pattern.compile("\"position\"\\s*:\\s*(\\d+)\\s*,\\s*\"response\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([ABC])\"\\s*}\\s*,\\s*\"seq\"\\s*:\\s*(\\d+)")
                    .matcher(v.body());
            while (m.find()) stored.put(Integer.parseInt(m.group(1)), new long[] {Long.parseLong(m.group(3)), "ABC".indexOf(m.group(2))});
            for (var e : c.acked.entrySet()) {
                ackedTotal++;
                long[] s2 = stored.get(e.getKey());
                if (s2 == null || s2[0] < e.getValue()[0] || (s2[0] == e.getValue()[0] && s2[1] != e.getValue()[1])) {
                    lost++;
                    problems.add("LOST vc" + c.n + " position " + e.getKey() + " acked seq " + e.getValue()[0] + " stored " + (s2 == null ? "none" : s2[0]));
                } else if (s2[0] > e.getValue()[0]) storedNewer++;                       // stored but its acknowledgement never arrived
            }
        }

        // concurrent result review (two reviewers) and concurrent approval (one approver, twice at once)
        ExecutorService race = Executors.newVirtualThreadPerTaskExecutor();
        var rv1 = race.submit(() -> call("POST review", "POST", staff + "/api/v1/exam/results/" + schedule + "/reviews", staffToken(HEAD), "{\"comment\":\"harness\"}"));
        var rv2 = race.submit(() -> call("POST review", "POST", staff + "/api/v1/exam/results/" + schedule + "/reviews", staffToken(AUTHOR), "{\"comment\":\"harness\"}"));
        Res r1 = rv1.get(), r2 = rv2.get();
        int reviewsOk = (r1.ok() ? 1 : 0) + (r2.ok() ? 1 : 0);
        String approval = r1.ok() ? field(r1.body(), "approvalId") : r2.ok() ? field(r2.body(), "approvalId") : null;
        int approvalsOk = 0;
        long released = -1;
        if (approval != null) {
            var ap1 = race.submit(() -> call("POST approve", "POST", staff + "/api/v1/approvals/" + approval + "/decision", staffToken(CONTROLLER), "{\"approve\":true,\"reason\":\"harness\"}"));
            var ap2 = race.submit(() -> call("POST approve", "POST", staff + "/api/v1/approvals/" + approval + "/decision", staffToken(CONTROLLER), "{\"approve\":true,\"reason\":\"harness\"}"));
            approvalsOk = (ap1.get().ok() ? 1 : 0) + (ap2.get().ok() ? 1 : 0);
            released = number(staffCall(CONTROLLER, "GET", "/api/v1/exam/results/" + schedule, null).body(), "released");
        }
        race.shutdown();

        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("label", label);
        rep.put("candidates", candidates);
        rep.put("questions", questions);
        rep.put("durationMinutes", minutes);
        rep.put("sourceAddresses", 1);
        rep.put("deliveryRestart", restart);
        rep.put("examPhaseSeconds", examMs / 1000);
        Map<String, Object> c = new TreeMap<>();
        counters.forEach((k, v) -> c.put(k, v.sum()));
        rep.put("counters", c);
        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("attemptsStarted", started);
        verify.put("submittedByCandidate", submitted);
        verify.put("autoSubmitted", auto);
        verify.put("stillInProgress", inProgress);
        verify.put("awaitingScore", awaiting);
        verify.put("provisionalScores", provisional);
        verify.put("acknowledgedAnswersChecked", ackedTotal);
        verify.put("acknowledgedAnswersLost", lost);
        verify.put("storedNewerThanLastAcknowledgement", storedNewer);
        verify.put("concurrentReviewsSucceeded", reviewsOk);
        verify.put("concurrentApprovalsSucceeded", approvalsOk);
        verify.put("resultsPublished", released);
        rep.put("verification", verify);
        Map<String, Object> lat = new TreeMap<>();
        latencies.forEach((k, q) -> {
            List<Long> l = new ArrayList<>(q);
            Collections.sort(l);
            lat.put(k, Map.of("n", l.size(), "p50ms", pct(l, 50) / 1000.0, "p95ms", pct(l, 95) / 1000.0, "p99ms", pct(l, 99) / 1000.0, "maxms", l.get(l.size() - 1) / 1000.0));
        });
        rep.put("latency", lat);
        Map<String, Object> st = new TreeMap<>();
        statuses.forEach((k, v) -> st.put(k, v.sum()));
        rep.put("statuses", st);
        rep.put("problems", problems.size() > 50 ? problems.subList(0, 50) : problems);
        rep.put("problemCount", problems.size());
        String json = toJson(rep, "");
        Files.createDirectories(Path.of(out).toAbsolutePath().getParent());
        Files.writeString(Path.of(out), json + "\n");
        System.out.println(json);
        System.exit(0);
    }

    static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    static long pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    @SuppressWarnings("unchecked")
    static String toJson(Object o, String ind) {
        if (o instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{\n");
            int i = 0;
            for (var e : m.entrySet()) b.append(ind).append("  \"").append(e.getKey()).append("\": ").append(toJson(e.getValue(), ind + "  ")).append(++i < m.size() ? ",\n" : "\n");
            return b.append(ind).append("}").toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) b.append(i == 0 ? "" : ", ").append(toJson(l.get(i), ind));
            return b.append("]").toString();
        }
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        return "\"" + String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
