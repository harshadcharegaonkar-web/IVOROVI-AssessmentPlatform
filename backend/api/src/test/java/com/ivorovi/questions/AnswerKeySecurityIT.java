package com.ivorovi.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * Answer-key security (demo item 9): the role × state access matrix through the API, other institutions and
 * organizations, candidates, API clients and anonymous callers; and leak checks — question and search responses, full-text
 * search, database plaintext, Valkey, logs, error responses, caching headers and audit details.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnswerKeySecurityIT extends QuestionTestSupport {

    /** Distinctive values that must only ever appear in the answer-key response. */
    private static final String SECRET_VALUE = "73.519";
    private static final String SECRET_EXPLANATION = "KEYLEAK-explanation-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired StringRedisTemplate redis;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private UUID bank;
    private TestOrg.Account bankManagerOnly;

    @BeforeEach
    void setUp() {
        setUpOrg("qkey");
        // a custom role with bank management (and read) only: never any answer key, in any state
        UUID role = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO role (id, tenant_id, code, name, permissions, system_role, status) VALUES (?, ?, 'BANKMGR', 'Bank manager', "
                + "ARRAY['question.read','question.bank.manage'], false, 'ACTIVE')").params(role, org.tenant).update();
        bankManagerOnly = org.account("STUDENT", college);
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, 'ORG_UNIT', ?)")
                .params(UUID.randomUUID(), org.tenant, bankManagerOnly.id(), role, college).update();
    }

    private String secretDraft(String stem) {
        return """
                {"type":"NUMERIC","content":%s,"body":{"precision":3},"answerKey":{"value":"%s","tolerance":{"type":"ABSOLUTE","value":"0"}},
                 "explanation":"%s","marks":3}""".formatted(content(stem), SECRET_VALUE, SECRET_EXPLANATION);
    }

    private int keyStatus(UUID q, int no, TestOrg.Account who) throws Exception {
        return perform(get("/api/v1/questions/" + q + "/versions/" + no + "/answer-key"), who).andReturn().getResponse().getStatus();
    }

    private Map<String, Integer> matrix(UUID q, Map<String, TestOrg.Account> who) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : who.entrySet()) out.put(e.getKey(), keyStatus(q, 1, e.getValue()));
        return out;
    }

    private Map<String, TestOrg.Account> staff() {
        Map<String, TestOrg.Account> m = new LinkedHashMap<>();
        m.put("author(faculty)", faculty);
        m.put("other faculty", faculty2);
        m.put("designer(reviewer)", designer);
        m.put("controller(approver,key.read)", controller);
        m.put("org admin(bank.manage)", admin);
        m.put("institution admin(bank.manage)", instAdmin);
        m.put("other-institution faculty", otherFaculty);
        m.put("other-institution controller", otherController);
        m.put("student", student);
        m.put("bank manager only", bankManagerOnly);
        return m;
    }

    @Test
    void answerKeyAccessMatrixFollowsRoleAndVersionState() throws Exception {
        bank = standardBank();
        UUID q = create(bank, faculty, secretDraft("Matrix question"));
        Map<String, Integer> draft = matrix(q, staff());
        assertThat(draft).as("DRAFT: only the author").containsEntry("author(faculty)", 200).containsEntry("other faculty", 403)
                .containsEntry("designer(reviewer)", 403).containsEntry("controller(approver,key.read)", 403).containsEntry("org admin(bank.manage)", 403)
                .containsEntry("institution admin(bank.manage)", 403).containsEntry("other-institution faculty", 404)
                .containsEntry("other-institution controller", 404).containsEntry("student", 403).containsEntry("bank manager only", 403);

        submit(q, faculty);
        Map<String, Integer> inReview = matrix(q, staff());
        assertThat(inReview).containsEntry("author(faculty)", 200).containsEntry("designer(reviewer)", 200).containsEntry("controller(approver,key.read)", 200)
                .containsEntry("other faculty", 403)
                // administrators hold question.review (P2-08), which shows keys of versions in review only
                .containsEntry("org admin(bank.manage)", 200).containsEntry("institution admin(bank.manage)", 200)
                .containsEntry("other-institution controller", 404).containsEntry("student", 403).containsEntry("bank manager only", 403);

        review(q, designer, "REVIEWED", null).andExpect(status().isOk());
        approve(q, controller).andExpect(status().isOk());
        Map<String, Integer> approvedM = matrix(q, staff());
        assertThat(approvedM).as("APPROVED: key.read or approve only").containsEntry("controller(approver,key.read)", 200)
                .containsEntry("author(faculty)", 403).containsEntry("designer(reviewer)", 403).containsEntry("other faculty", 403)
                .containsEntry("org admin(bank.manage)", 403).containsEntry("institution admin(bank.manage)", 403)
                .containsEntry("other-institution controller", 404).containsEntry("other-institution faculty", 404).containsEntry("student", 403)
                .containsEntry("bank manager only", 403);

        // the key panel flag agrees with the matrix
        assertThat(detail(q, controller).path("current").path("keyVisible").asBoolean()).isTrue();
        assertThat(detail(q, faculty).path("current").path("keyVisible").asBoolean()).isFalse();
        assertThat(detail(q, admin).path("current").path("keyVisible").asBoolean()).isFalse();

        // other organization, candidate, anonymous
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "qkey-other");
        TestOrg.Account foreignController = other.account("EXAM_CONTROLLER", null);
        assertThat(keyStatus(q, 1, foreignController)).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/questions/" + q + "/versions/1/answer-key")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.candidate("cand-" + UUID.randomUUID(), "c@x.test"))).andReturn().getResponse().getStatus())
                .as("candidate tokens are not accepted by the staff API").isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/questions/" + q + "/versions/1/answer-key")).andReturn().getResponse().getStatus()).isEqualTo(401);

        // every view and every refusal is audited
        assertThat(audits("question.answer_key.viewed")).isGreaterThanOrEqualTo(5);
        assertThat(audits("question.answer_key.view_denied")).isGreaterThanOrEqualTo(10);
    }

    @Test
    void marksPreviewNeedsKeyAccessToo() throws Exception {
        bank = standardBank();
        UUID q = approved(bank, faculty, designer, controller, secretDraft("Preview question"));
        String body = "{\"responses\":[\"" + SECRET_VALUE + "\",\"1\"]}";
        perform(post("/api/v1/questions/" + q + "/versions/1/marking-preview").content(body), faculty).andExpect(status().isForbidden());
        perform(post("/api/v1/questions/" + q + "/versions/1/marking-preview").content(body), admin).andExpect(status().isForbidden());
        JsonNode r = call(post("/api/v1/questions/" + q + "/versions/1/marking-preview").content(body), controller, status().isOk());
        assertThat(r.get(0).path("outcome").asString()).isEqualTo("CORRECT");
        assertThat(r.get(1).path("outcome").asString()).isEqualTo("INCORRECT");
    }

    @Test
    void theKeyNeverLeaksThroughOtherResponsesSearchStorageCachesLogsOrErrors(CapturedOutput output) throws Exception {
        bank = standardBank();
        UUID q = approved(bank, faculty, designer, controller, secretDraft("Leak check question about kinematics"));

        // the dedicated endpoint returns it, uncached
        MvcResult key = perform(get("/api/v1/questions/" + q + "/versions/1/answer-key"), controller).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        assertThat(key.getResponse().getContentAsString()).contains(SECRET_VALUE).contains(SECRET_EXPLANATION);

        // no other response contains it, for any caller (including the one allowed to see it)
        List<String> responses = new ArrayList<>();
        for (TestOrg.Account who : List.of(controller, faculty, designer, admin)) {
            responses.add(perform(get("/api/v1/questions/" + q), who).andReturn().getResponse().getContentAsString());
            responses.add(perform(get("/api/v1/questions/" + q + "/versions/1"), who).andReturn().getResponse().getContentAsString());
            responses.add(perform(get("/api/v1/question-banks/" + bank + "/questions"), who).andReturn().getResponse().getContentAsString());
            responses.add(perform(get("/api/v1/question-banks/" + bank + "/questions?q=kinematics"), who).andReturn().getResponse().getContentAsString());
            responses.add(perform(get("/api/v1/questions/" + q + "/history"), who).andReturn().getResponse().getContentAsString());
            responses.add(perform(get("/api/v1/questions/" + q + "/transitions"), who).andReturn().getResponse().getContentAsString());
            responses.add(perform(get("/api/v1/question-review-queue?stage=review"), who).andReturn().getResponse().getContentAsString());
            responses.add(perform(get("/api/v1/questions/" + q + "/versions/1/answer-key"), who.equals(controller) ? faculty : who)
                    .andReturn().getResponse().getContentAsString());
        }
        responses.add(call(get("/api/v1/audit-events?size=100"), admin, status().isOk()).toString());
        for (String r : responses) assertThat(r).doesNotContain(SECRET_VALUE).doesNotContain(SECRET_EXPLANATION);

        // full-text search does not index keys or explanations
        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?q=" + SECRET_VALUE), controller, status().isOk()).path("total").asLong()).isZero();
        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?q=" + SECRET_EXPLANATION), controller, status().isOk()).path("total").asLong()).isZero();

        // database: ciphertext only; no plaintext in versions, transitions, reviews, audit or outbox
        String stored = ownerJdbc().sql("SELECT key_enc || coalesce(explanation_enc, '') FROM question_answer_key WHERE version_id = ?").param(versionId(q, 1))
                .query(String.class).single();
        assertThat(stored).doesNotContain(SECRET_VALUE).doesNotContain(SECRET_EXPLANATION);
        for (String sql : List.of(
                "SELECT coalesce(string_agg(row_to_json(v)::text, ''), '') FROM question_version v JOIN question q ON q.id = v.question_id JOIN question_bank b ON b.id = q.bank_id WHERE b.tenant_id = ?",
                "SELECT coalesce(string_agg(row_to_json(t)::text, ''), '') FROM question_transition t WHERE t.tenant_id = ?",
                "SELECT coalesce(string_agg(row_to_json(r)::text, ''), '') FROM question_review r WHERE r.tenant_id = ?",
                "SELECT coalesce(string_agg(details::text, ''), '') FROM audit_event WHERE tenant_id = ?",
                "SELECT coalesce(string_agg(payload::text, ''), '') FROM outbox_event WHERE tenant_id = ?")) {
            assertThat(ownerJdbc().sql(sql).param(org.tenant).query(String.class).single()).as(sql).doesNotContain(SECRET_VALUE).doesNotContain(SECRET_EXPLANATION);
        }

        // Valkey (principal, rate-limit and idempotency caches)
        Set<String> keys = redis.keys("*");
        for (String k : keys) {
            String type = redis.type(k).code();
            String value = switch (type) {
                case "string" -> String.valueOf(redis.opsForValue().get(k));
                case "hash" -> String.valueOf(redis.opsForHash().entries(k));
                case "list" -> String.valueOf(redis.opsForList().range(k, 0, -1));
                case "set" -> String.valueOf(redis.opsForSet().members(k));
                case "zset" -> String.valueOf(redis.opsForZSet().range(k, 0, -1));
                default -> "";
            };
            assertThat(k + value).as("valkey " + k).doesNotContain(SECRET_VALUE).doesNotContain(SECRET_EXPLANATION);
        }

        // error responses never echo key material
        String bad = perform(post("/api/v1/question-banks/" + bank + "/questions").content("""
                {"type":"NUMERIC","content":%s,"body":{},"answerKey":{"value":"KEYLEAK-not-a-number"}}""".formatted(content("Bad key"))), faculty)
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(bad).doesNotContain("KEYLEAK");

        // logs
        assertThat(output.getAll()).doesNotContain(SECRET_EXPLANATION).doesNotContain("KEYLEAK-not-a-number").doesNotContain("\"value\":\"" + SECRET_VALUE);
    }

    @Test
    void apiClientsCanNeverHoldQuestionScopes() throws Exception {
        bank = standardBank();
        UUID q = approved(bank, faculty, designer, controller, secretDraft("API client question"));
        JsonNode scopes = call(get("/api/v1/api-clients/scopes"), admin, status().isOk());
        assertThat(scopes.toString()).doesNotContain("question.");
        perform(post("/api/v1/api-clients").content("{\"name\":\"Q client\",\"scopes\":[\"question.read\"]}"), admin).andExpect(status().is4xxClientError());
        perform(post("/api/v1/api-clients").content("{\"name\":\"K client\",\"scopes\":[\"question.key.read\"]}"), admin).andExpect(status().is4xxClientError());
        String apiKey = call(post("/api/v1/api-clients").content("{\"name\":\"S client\",\"scopes\":[\"org_unit.read\"]}"), admin, status().isCreated())
                .path("apiKey").asString();
        for (String path : List.of("/api/v1/question-banks", "/api/v1/questions/" + q, "/api/v1/questions/" + q + "/versions/1/answer-key",
                "/api/v1/question-banks/" + bank + "/questions")) {
            assertThat(mvc.perform(get(path).header("X-Api-Key", apiKey)).andReturn().getResponse().getStatus()).as(path).isIn(401, 403);
        }
    }

    @Test
    void noCandidateFacingEndpointServesQuestionsOrKeys() {
        // the delivery API (candidate realm) has no handler from the question module in 2.1; candidate projections arrive with 2.5
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            boolean questionHandler = e.getValue().getBeanType().getPackageName().startsWith("com.ivorovi.questions");
            boolean delivery = e.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/v1/delivery"));
            assertThat(questionHandler && delivery).as(e.getKey().toString()).isFalse();
        }
    }

    @Test
    void onlyTheAnswerKeyStoreReadsTheAnswerKeyTable() throws Exception {
        Path main = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("AnswerKeyStore.java")) continue;
                if (Files.readString(p).contains("question_answer_key")) offenders.add(p.toString());
            }
        }
        assertThat(offenders).isEmpty();
    }
}
