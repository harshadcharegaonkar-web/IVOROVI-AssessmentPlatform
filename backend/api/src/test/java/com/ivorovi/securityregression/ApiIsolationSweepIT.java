package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.ivorovi.support.TestOrg;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-module tenant-isolation and authorization sweep (demo programme, D-18 acceptance criteria): every operation of the
 * committed OpenAPI document (docs/api/openapi.json), so a new endpoint is covered without writing a test for it.
 * Organization A is the {@link DeliveryJourneySupport} fixture (bank, questions, published assessment, portal, schedule,
 * enrolled candidates, an attempt and its approvals); every path parameter is filled with a real id of organization A of the
 * matching kind (or a random id where no record of that kind exists).
 * <ul>
 *   <li><b>Isolation:</b> an administrator of organization B never gets a 2xx on organization A's records, and no list it reads
 *       contains an id of organization A.</li>
 *   <li><b>Authorization:</b> accounts of organization A with no role, or only the STUDENT role, change nothing (no 2xx on a
 *       mutating operation addressed at a record) and read no record of another person, except their own self-service
 *       endpoints.</li>
 *   <li>No operation answers 500 to any of them.</li>
 * </ul>
 * Limits: mutations are sent with an empty JSON body, so a request refused by validation (400) proves only that nothing
 * changed, not the order of the checks; collection reads are compared by id only. Each refused call is a finding, listed.
 */
@Tag("security-regression")
class ApiIsolationSweepIT extends DeliveryJourneySupport {

    private static final Pattern PARAM = Pattern.compile("/([a-z\\-]+)/\\{(\\w+)\\}");
    /** Path segment before a parameter → table of that record kind (the rest fall back to the singular, snake-case name). */
    private static final Map<String, String> TABLES = Map.ofEntries(
            Map.entry("schedules", "exam_schedule"), Map.entry("results", "exam_schedule"), Map.entry("portals", "exam_portal"),
            Map.entry("sessions", "readiness_session"), Map.entry("codes", "session_code"), Map.entry("incidents", "exam_candidate_incident"),
            Map.entry("candidates", "exam_enrolment"), Map.entry("enrolments", "exam_enrolment"), Map.entry("users", "user_account"),
            Map.entry("approvals", "approval_request"), Map.entry("question-banks", "question_bank"), Map.entry("banks", "question_bank"),
            Map.entry("assessments", "assessment"), Map.entry("assessment-papers", "assessment_paper"), Map.entry("questions", "question"),
            Map.entry("question-assets", "question_asset"), Map.entry("question-imports", "question_import"), Map.entry("org-units", "org_unit"),
            Map.entry("org-assignments", "org_assignment"), Map.entry("role-assignments", "role_assignment"), Map.entry("roles", "role"),
            Map.entry("staff", "staff_profile"), Map.entry("students", "student"), Map.entry("documents", "student_document"),
            Map.entry("guardians", "guardian"), Map.entry("student-imports", "student_import_job"), Map.entry("mappings", "student_import_mapping"),
            Map.entry("programs", "program"), Map.entry("courses", "course"), Map.entry("subjects", "subject"), Map.entry("batches", "batch"),
            Map.entry("academic-years", "academic_year"), Map.entry("academic-terms", "academic_term"), Map.entry("subject-offerings", "subject_offering"),
            Map.entry("faculty", "faculty_assignment"), Map.entry("curriculum", "curriculum_entry"), Map.entry("admissions", "admission_record"),
            Map.entry("api-clients", "api_client"), Map.entry("service-identities", "service_identity"), Map.entry("webhooks", "webhook_endpoint"),
            Map.entry("deliveries", "webhook_delivery"), Map.entry("integrations", "integration"), Map.entry("runs", "integration_run"),
            Map.entry("notifications", "notification"), Map.entry("invitations", "user_invitation"), Map.entry("keys", "api_client_secret"));
    /** Endpoints every signed-in staff account may use for itself (no other person's data). */
    private static final Set<String> SELF_SERVICE = Set.of("/api/v1/me", "/api/v1/notifications", "/api/v1/notifications/{id}/read",
            "/api/v1/notifications/read-all", "/api/v1/notifications/unread-count", "/api/v1/features", "/api/v1/tenant", "/api/v1/permissions",
            "/api/v1/question-types");

    record Op(String method, String path) {}

    record Call(Op op, String uri, int status, String body) {}

    private List<Op> operations() throws Exception {
        JsonNode doc = new ObjectMapper().readTree(Files.readString(Path.of(System.getProperty("ivorovi.openapi.export"))));
        List<Op> ops = new ArrayList<>();
        for (Map.Entry<String, JsonNode> p : doc.path("paths").properties()) {
            if (p.getKey().startsWith("/api/v1/public") || p.getKey().startsWith("/api/v1/delivery")) continue;
            for (String m : List.of("get", "post", "put", "patch", "delete")) {
                if (p.getValue().has(m)) ops.add(new Op(m.toUpperCase(), p.getKey()));
            }
        }
        assertThat(ops).as("operations in the OpenAPI document").hasSizeGreaterThan(250);
        return ops;
    }

    private final Map<String, String> idCache = new HashMap<>();
    private final Set<String> realKinds = new java.util.TreeSet<>();

    private String idOf(String segment) {
        return idCache.computeIfAbsent(segment, s -> {
            String table = TABLES.getOrDefault(s, s.replace('-', '_').replaceAll("s$", ""));
            try {
                var found = ownerJdbc().sql("SELECT id::text FROM " + table + " WHERE tenant_id = ? ORDER BY 1 LIMIT 1").param(org.tenant).query(String.class)
                        .optional();
                found.ifPresent(x -> realKinds.add(s));
                return found.orElse(UUID.randomUUID().toString());
            } catch (RuntimeException e) {
                return UUID.randomUUID().toString();
            }
        });
    }

    /** The path with every parameter filled; null when a parameter is not an id (kind, key, version number). */
    private String fill(String path) {
        Matcher m = PARAM.matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(2);
            boolean isId = name.equals("id") || name.endsWith("Id");
            if (!isId) return null;
            m.appendReplacement(out, Matcher.quoteReplacement("/" + m.group(1) + "/" + idOf(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private Call call(TestOrg.Account who, Op op, String uri) throws Exception {
        MvcResult r = perform(request(HttpMethod.valueOf(op.method()), uri).content("{}"), who).andReturn();
        return new Call(op, uri, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    /** Every id of organization A in the tables the fixture fills (the "must never appear elsewhere" set). */
    private Set<String> organizationAIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (String t : Set.copyOf(TABLES.values())) {
            try {
                ids.addAll(ownerJdbc().sql("SELECT id::text FROM " + t + " WHERE tenant_id = ?").param(org.tenant).query(String.class).list());
            } catch (RuntimeException ignored) {
                // table without an id column of that name
            }
        }
        ids.add(org.tenant.toString());
        return ids;
    }

    private static String describe(List<Call> calls) {
        StringBuilder b = new StringBuilder();
        for (Call c : calls) b.append("\n  ").append(c.op().method()).append(' ').append(c.op().path()).append(" -> ").append(c.status())
                .append(' ').append(c.body().length() > 160 ? c.body().substring(0, 160) : c.body());
        return b.toString();
    }

    @Test
    void anotherOrganizationsAdministratorReachesNoRecordOfThisOrganization() throws Exception {
        startAttempt(asha, codes.get(0));
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "sweep-b");
        TestOrg.Account intruder = other.account("ORG_ADMIN", null);
        Set<String> foreign = organizationAIds();
        List<Call> leaks = new ArrayList<>(), errors = new ArrayList<>(), neutral = new ArrayList<>();
        int addressed = 0, lists = 0;
        // lists first: once B has addressed A's ids, B's own audit trail rightly records those (denied) attempts
        for (Op op : operations()) {
            if (op.path().contains("{") || !op.method().equals("GET")) continue;
            Call c = call(intruder, op, op.path());
            lists++;
            if (c.status() >= 500) errors.add(c);
            if (c.status() >= 200 && c.status() < 300 && foreign.stream().anyMatch(c.body()::contains)) leaks.add(c);
        }
        for (Op op : operations()) {
            if (!op.path().contains("{")) continue;
            String uri = fill(op.path());
            if (uri == null) continue;
            Call c = call(intruder, op, uri);
            addressed++;
            if (c.status() >= 500) errors.add(c);
            if (c.status() >= 200 && c.status() < 300) {
                // a read that answers the same for a random id and returns none of A's ids is a filter, not a leak
                Call random = op.method().equals("GET") ? call(intruder, op, uri.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        UUID.randomUUID().toString())) : null;
                boolean filter = random != null && random.status() == c.status() && random.body().equals(c.body())
                        && foreign.stream().noneMatch(c.body()::contains);
                (filter ? neutral : leaks).add(c);
            }
        }
        if (!neutral.isEmpty()) System.out.println("QA-SWEEP filters answering the same for any id (no data, no existence oracle):" + describe(neutral));
        System.out.println("QA-SWEEP isolation: " + addressed + " operations addressed at organization A's records, " + lists + " lists read; "
                + "real records of A used for " + realKinds.size() + " kinds: " + realKinds);
        assertThat(leaks.isEmpty() && errors.isEmpty()).as("operations that reached organization A from organization B" + describe(leaks)
                + "\noperations answering 5xx to another organization" + describe(errors)).isTrue();
    }

    @Test
    void accountsWithoutStaffRightsChangeNothingAndReadNoOneElsesRecords() throws Exception {
        startAttempt(asha, codes.get(0));
        TestOrg.Account noRole = org.account("STUDENT", college);   // replaced below by a role-less account
        ownerJdbc().sql("DELETE FROM role_assignment WHERE user_account_id = ?").param(noRole.id()).update();
        TestOrg.Account studentRole = student;
        List<Call> allowed = new ArrayList<>(), errors = new ArrayList<>();
        int checked = 0;
        for (TestOrg.Account who : List.of(noRole, studentRole)) {
            for (Op op : operations()) {
                if (SELF_SERVICE.contains(op.path())) continue;
                boolean hasParams = op.path().contains("{");
                String uri = hasParams ? fill(op.path()) : op.path();
                if (uri == null || (!hasParams && op.method().equals("GET"))) continue;   // lists filtered by scope: checked separately
                Call c = call(who, op, uri);
                checked++;
                if (c.status() >= 500) errors.add(c);
                if (c.status() >= 200 && c.status() < 300) allowed.add(c);
            }
        }
        System.out.println("QA-SWEEP authorization: " + checked + " calls by accounts without staff rights");
        assertThat(allowed.isEmpty() && errors.isEmpty()).as("operations allowed to accounts without staff rights" + describe(allowed)
                + "\noperations answering 5xx to accounts without staff rights" + describe(errors)).isTrue();
    }
}
