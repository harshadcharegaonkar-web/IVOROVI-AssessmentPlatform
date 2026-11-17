package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.TestOrg;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M-G manual evaluation (contract C-2) over HTTP: setup validation, balanced allocation of anonymous scripts, rubric
 * marking within bounds, double marking that agrees within the tolerance or goes to moderation by a manager who marked
 * neither, permanence of submissions, isolation, and the final score reaching the results (R-1 unchanged).
 */
@Tag("security-regression")
class EvaluationWorkflowIT extends DeliveryJourneySupport {

    private static final String E = "/api/v1/evaluation";
    private static final String MODEL = "Stacks are LIFO; queues are FIFO.";

    @Override
    protected List<String> questionDrafts(UUID bank) {
        return List.of(mcq("Evaluation suite objective " + rnd(), "A"), """
                {"type":"DESCRIPTIVE","content":%s,"body":{"maxWords":40},"answerKey":{"modelAnswer":"%s"},
                 "marking":{"rubric":[{"title":"Correctness","weight":70},{"title":"Clarity","weight":30}]},"marks":10,"negativeMarks":0}"""
                .formatted(content("Compare a stack and a queue " + rnd()), MODEL));
    }

    private int descriptivePosition(UUID attempt, Candidate c) throws Exception {
        for (JsonNode i : delivery(get(portal("/attempts/" + attempt)), c, 200).path("items"))
            if ("DESCRIPTIVE".equals(i.path("type").asString())) return i.path("position").asInt();
        throw new AssertionError("no descriptive item");
    }

    /** The candidate answers the objective question correctly and writes the given text; submitted and scored. */
    private UUID sit(Candidate c, String code, String text) throws Exception {
        UUID attempt = startAttempt(c, code);
        int d = descriptivePosition(attempt, c);
        delivery(put(portal("/attempts/" + attempt + "/answers/" + (d == 1 ? 2 : 1))).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), c, 200);
        delivery(put(portal("/attempts/" + attempt + "/answers/" + d)).content("{\"seq\":1,\"response\":{\"value\":\"" + text + "\"}}"), c, 200);
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), c, 200);
        return attempt;
    }

    private JsonNode setup(String mode, List<TestOrg.Account> pool) throws Exception {
        String ids = String.join(",", pool.stream().map(a -> "\"" + a.id() + "\"").toList());
        return call(put(E + "/schedules/" + schedule + "/setup").content("{\"mode\":\"" + mode + "\",\"tolerancePercent\":10,\"evaluatorIds\":[" + ids + "]}"),
                controller, status().isOk());
    }

    private List<JsonNode> tasks(TestOrg.Account who) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        call(get(E + "/tasks"), who, status().isOk()).forEach(out::add);
        return out;
    }

    private String submit(TestOrg.Account who, String taskId, String c1, String c2, int expect) throws Exception {
        return perform(post(E + "/tasks/" + taskId + "/submission").content("{\"criteria\":{\"C1\":" + c1 + ",\"C2\":" + c2 + "},\"comment\":\"QA\"}"), who)
                .andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
    }

    private UUID attemptOf(String scriptCode) {
        return ownerJdbc().sql("SELECT attempt_id FROM evaluation_script WHERE code = ?").param(scriptCode).query(UUID.class).single();
    }

    private BigDecimal awarded(UUID attempt) {
        return ownerJdbc().sql("SELECT awarded FROM exam_score WHERE attempt_id = ?").param(attempt).query(BigDecimal.class).optional().orElse(null);
    }

    @Test
    void anonymousScriptsAreAllocatedInBalanceAndAgreeingDoubleMarksBecomeTheScore() throws Exception {
        UUID ashas = sit(asha, codes.get(0), "A stack is last in first out and a queue is first in first out");
        UUID bilals = sit(bilal, codes.get(1), "Queues are FIFO");
        scoring.scoreDue();
        TestOrg.Account ev1 = org.account("EVALUATOR", college), ev2 = org.account("EVALUATOR", college), ev3 = org.account("EVALUATOR", college);
        TestOrg.Account elsewhere = org.account("EVALUATOR", otherCollege);

        // who may set up, and with whom
        perform(get(E + "/schedules/" + schedule + "/setup"), ev1).andExpect(status().isForbidden());
        perform(get(E + "/schedules/" + schedule + "/setup"), otherController).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 404));
        JsonNode s = call(get(E + "/schedules/" + schedule + "/setup"), controller, status().isOk());
        assertThat(s.path("configured").asBoolean()).isFalse();
        assertThat(s.path("eligible").findValuesAsString("userId")).contains(ev1.id().toString(), faculty.id().toString())
                .doesNotContain(elsewhere.id().toString(), student.id().toString());
        for (List<TestOrg.Account> bad : List.of(List.of(ev1), List.of(ev1, student), List.of(ev1, elsewhere)))
            perform(put(E + "/schedules/" + schedule + "/setup").content("{\"mode\":\"DOUBLE\",\"evaluatorIds\":[" + String.join(",",
                    bad.stream().map(a -> "\"" + a.id() + "\"").toList()) + "]}"), controller).andExpect(status().isBadRequest());
        perform(post(E + "/schedules/" + schedule + "/allocate"), controller).andExpect(status().isConflict());     // not set up yet
        setup("DOUBLE", List.of(ev1, ev2, ev3));

        JsonNode p = call(post(E + "/schedules/" + schedule + "/allocate"), controller, status().isOk());
        assertThat(p.path("scripts").asInt()).isEqualTo(2);
        assertThat(p.path("itemsOpen").asInt()).isEqualTo(2);
        List<Integer> loads = new ArrayList<>();
        p.path("evaluators").forEach(l -> loads.add(l.path("assigned").asInt()));
        assertThat(loads).hasSize(3);
        assertThat(loads.stream().mapToInt(Integer::intValue).sum()).isEqualTo(4);                          // two evaluators per answer
        assertThat(loads.stream().mapToInt(Integer::intValue).max().getAsInt() - loads.stream().mapToInt(Integer::intValue).min().getAsInt()).isLessThanOrEqualTo(1);
        call(post(E + "/schedules/" + schedule + "/allocate"), controller, status().isOk());                 // idempotent
        assertThat(ownerJdbc().sql("SELECT count(*) FROM evaluation_task t JOIN evaluation_item i ON i.id = t.item_id JOIN evaluation_script sc ON sc.id = i.script_id WHERE sc.schedule_id = ?")
                .param(schedule).query(Integer.class).single()).isEqualTo(4);

        // anonymity: an evaluator's views carry no candidate identity; the model answer read is audited
        Map<String, List<String>> byScript = new HashMap<>();                                                // script code -> task ids
        Map<String, TestOrg.Account> owner = new HashMap<>();
        StringBuilder seen = new StringBuilder();
        for (TestOrg.Account ev : List.of(ev1, ev2, ev3)) {
            for (JsonNode t : tasks(ev)) {
                byScript.computeIfAbsent(t.path("scriptCode").asString(), k -> new ArrayList<>()).add(t.path("taskId").asString());
                owner.put(t.path("taskId").asString(), ev);
                JsonNode d = call(get(E + "/tasks/" + t.path("taskId").asString()), ev, status().isOk());
                assertThat(d.path("modelAnswer").asString()).isEqualTo(MODEL);
                assertThat(d.path("rubric").size()).isEqualTo(2);
                seen.append(t).append(d);
            }
        }
        String text = seen.toString();
        for (String identity : List.of(asha.email(), bilal.email(), "Asha", "Bilal", ashas.toString(), bilals.toString())) assertThat(text).doesNotContain(identity);
        assertThat(text).contains("A stack is last in first out");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'question.answer_key.viewed' AND details->>'purpose' = 'EVALUATION'")
                .param(org.tenant).query(Integer.class).single()).isEqualTo(4);

        // nobody else's task: another evaluator, another organization
        String aTask = byScript.values().iterator().next().get(0);
        TestOrg.Account notOwner = List.of(ev1, ev2, ev3).stream().filter(a -> a != owner.get(aTask)).findFirst().orElseThrow();
        boolean alsoOnIt = byScript.values().stream().anyMatch(l -> l.contains(aTask) && l.stream().anyMatch(x -> owner.get(x) == notOwner && !x.equals(aTask)));
        if (!alsoOnIt) perform(get(E + "/tasks/" + aTask), notOwner).andExpect(status().isNotFound());
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "eval-other");
        perform(get(E + "/tasks/" + aTask), other.account("EVALUATOR", null)).andExpect(status().isNotFound());
        perform(get(E + "/schedules/" + schedule + "/setup"), other.account("EXAM_CONTROLLER", null)).andExpect(status().isNotFound());

        // bounds: C1 ≤ 7 (70 % of 10), C2 ≤ 3, two decimals, every criterion
        String t0 = byScript.get(byScript.keySet().iterator().next()).get(0);
        submit(owner.get(t0), t0, "7.5", "1", 400);
        submit(owner.get(t0), t0, "6.125", "1", 400);
        perform(post(E + "/tasks/" + t0 + "/submission").content("{\"criteria\":{\"C1\":5}}"), owner.get(t0)).andExpect(status().isBadRequest());

        // every script: the two evaluators agree within 1 mark (10 % of 10) → the mean is final
        for (Map.Entry<String, List<String>> e : byScript.entrySet()) {
            List<String> ts = e.getValue();
            submit(owner.get(ts.get(0)), ts.get(0), "6", "2", 200);
            assertThat(awarded(attemptOf(e.getKey()))).isNull();                                            // one of two: not yet
            submit(owner.get(ts.get(1)), ts.get(1), "6", "2.5", 200);
            assertThat(awarded(attemptOf(e.getKey()))).isEqualByComparingTo("10.25");                        // 2 + mean(8, 8.5)
        }
        // submissions are final
        submit(owner.get(t0), t0, "1", "1", 409);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE evaluation_task SET total = 0 WHERE id = ?::uuid").param(t0).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE evaluation_item SET final_awarded = 0 WHERE status = 'FINAL'").update()).isInstanceOf(Exception.class);
        perform(put(E + "/schedules/" + schedule + "/setup").content("{\"mode\":\"SINGLE\",\"evaluatorIds\":[\"" + ev1.id() + "\"]}"), controller)
                .andExpect(status().isConflict());                                                          // marks exist: mode fixed

        JsonNode results = call(get("/api/v1/exam/results/" + schedule), controller, status().isOk());
        assertThat(results.path("awaitingMarks").asInt()).isZero();
        assertThat(results.path("provisional").asInt()).isEqualTo(2);
        assertThat(audits("evaluation.item.final")).isEqualTo(2);
    }

    @Test
    void disagreeingMarksGoToModerationByAManagerWhoMarkedNeither() throws Exception {
        UUID ashas = sit(asha, codes.get(0), "A stack and a queue differ in order");
        scoring.scoreDue();
        TestOrg.Account ev1 = org.account("EVALUATOR", college);
        setup("DOUBLE", List.of(controller, ev1));                                                           // a manager who also evaluates
        call(post(E + "/schedules/" + schedule + "/allocate"), controller, status().isOk());
        String mine = tasks(controller).get(0).path("taskId").asString();
        String theirs = tasks(ev1).get(0).path("taskId").asString();
        submit(controller, mine, "7", "3", 200);
        submit(ev1, theirs, "2", "1", 200);                                                                   // 10 vs 3: beyond 1 mark
        assertThat(awarded(ashas)).isNull();

        JsonNode queue = call(get(E + "/schedules/" + schedule + "/moderation"), controller2, status().isOk());
        assertThat(queue).hasSize(1);
        JsonNode item = queue.get(0);
        assertThat(item.path("marksGiven").findValuesAsString("label")).containsExactly("Evaluator 1", "Evaluator 2");
        assertThat(item.toString()).doesNotContain(asha.email()).doesNotContain(ashas.toString());
        String itemId = item.path("itemId").asString();
        assertThat(call(get(E + "/schedules/" + schedule + "/moderation"), controller, status().isOk()).get(0).path("mayModerate").asBoolean()).isFalse();
        perform(post(E + "/moderation/" + itemId).content("{\"awarded\":6,\"reason\":\"Mine\"}"), controller).andExpect(status().isForbidden());   // marked it
        perform(post(E + "/moderation/" + itemId).content("{\"awarded\":6,\"reason\":\"x\"}"), ev1).andExpect(status().isForbidden());          // not a manager
        perform(post(E + "/moderation/" + itemId).content("{\"awarded\":6,\"reason\":\"  \"}"), controller2).andExpect(status().isBadRequest());
        perform(post(E + "/moderation/" + itemId).content("{\"awarded\":11,\"reason\":\"Too high\"}"), controller2).andExpect(status().isBadRequest());
        perform(post(E + "/moderation/" + itemId).content("{\"awarded\":6.5,\"reason\":\"Both orders named, unclear wording\"}"), controller2)
                .andExpect(status().isNoContent());
        assertThat(awarded(ashas)).isEqualByComparingTo("8.5");                                              // 2 + 6.5
        assertThat(ownerJdbc().sql("SELECT final_source || '/' || moderated_by FROM evaluation_item WHERE id = ?::uuid").param(itemId).query(String.class).single())
                .isEqualTo("MODERATED/" + controller2.id());
        perform(post(E + "/moderation/" + itemId).content("{\"awarded\":7,\"reason\":\"Again\"}"), controller3).andExpect(status().isConflict());
    }
}
