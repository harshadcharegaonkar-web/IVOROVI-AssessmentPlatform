package com.ivorovi.evaluation.internal;

import com.ivorovi.evaluation.EvaluationTasks;
import com.ivorovi.exams.ItemResults;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.security.PrincipalDirectory;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.EvaluationMaterial;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual evaluation (M-G, contract C-2). Scripts are anonymous: evaluator responses never carry a candidate's identity or
 * the attempt id. SINGLE marking takes the evaluator's mark; DOUBLE marking takes the mean when the two marks agree within
 * the tolerance, otherwise a manager who marked neither moderates. Final marks go to exams through ItemResults.
 */
@Service
public class EvaluationService implements EvaluationTasks {

    public static final String WRITE = "evaluation.write";
    public static final String MANAGE = "evaluation.manage";
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Schema(name = "EvaluatorOption")
    public record Evaluator(UUID userId, String displayName) {}

    @Schema(name = "EvaluationSetup")
    public record Setup(UUID scheduleId, String mode, BigDecimal tolerancePercent, List<Evaluator> evaluators, List<Evaluator> eligible, boolean locked,
                        boolean configured) {}

    @Schema(name = "EvaluatorLoad")
    public record Load(UUID evaluatorId, String displayName, int assigned, int submitted) {}

    @Schema(name = "EvaluationProgress")
    public record Progress(UUID scheduleId, int scripts, int itemsOpen, int itemsInModeration, int itemsFinal, int awaitingScripts, List<Load> evaluators) {}

    @Schema(name = "EvaluationTaskSummary")
    public record TaskSummary(UUID taskId, String scriptCode, int itemNo, BigDecimal marks, String status, Instant assignedAt, Instant submittedAt) {}

    @Schema(name = "EvaluationCriterionMark")
    public record CriterionMark(String criterionId, String title, BigDecimal max, BigDecimal awarded) {}

    @Schema(name = "EvaluationTaskDetail")
    public record TaskDetail(UUID taskId, String scriptCode, int itemNo, String status, BigDecimal marks, Map<String, Object> statement, int maxWords,
                             List<EvaluationMaterial.Criterion> rubric, String modelAnswer, String responseText, List<CriterionMark> criterionMarks,
                             BigDecimal total, String comment) {}

    @Schema(name = "EvaluationSubmission")
    public record Submission(Map<String, BigDecimal> criteria, BigDecimal holistic, String comment) {}

    @Schema(name = "ModerationMark")
    public record ModerationMark(String label, BigDecimal total, List<CriterionMark> criterionMarks, String comment) {}

    @Schema(name = "ModerationItem")
    public record ModerationItem(UUID itemId, String scriptCode, int itemNo, BigDecimal marks, BigDecimal tolerance, List<ModerationMark> marksGiven, boolean mayModerate) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final JsonMapper json;
    private final ItemResults items;
    private final EvaluationMaterial material;
    private final PrincipalDirectory directory;

    EvaluationService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, JsonMapper json, ItemResults items, EvaluationMaterial material,
                      PrincipalDirectory directory) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
        this.items = items;
        this.material = material;
        this.directory = directory;
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }

    /** The schedule's institution, for managers (404 when unknown or out of the caller's reach). */
    private UUID manageUnit(UUID scheduleId) {
        UUID unit = items.scheduleUnit(scheduleId).orElseThrow(() -> ApiException.notFound("Schedule"));
        if (!authz.hasInOrgUnit(MANAGE, unit)) {
            if (authz.hasInOrgUnit(WRITE, unit) || authz.hasInOrgUnit("exam.results.read", unit)) throw new AccessDeniedException("Missing permission " + MANAGE);
            throw ApiException.notFound("Schedule");
        }
        return unit;
    }

    // ================================================================================================ setup

    private record SetupRow(String mode, BigDecimal tolerance, UUID[] evaluators) {}

    private Optional<SetupRow> setupRow(UUID scheduleId, boolean lock) {
        return jdbc.sql("SELECT mode, tolerance_pct, evaluator_ids FROM evaluation_setup WHERE schedule_id = ?" + (lock ? " FOR UPDATE" : "")).param(scheduleId)
                .query((rs, n) -> new SetupRow(rs.getString(1), rs.getBigDecimal(2), (UUID[]) rs.getArray(3).getArray())).optional();
    }

    private boolean locked(UUID scheduleId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM evaluation_task t JOIN evaluation_item i ON i.id = t.item_id JOIN evaluation_script s ON s.id = i.script_id
                WHERE s.schedule_id = ? AND t.status = 'SUBMITTED')""").param(scheduleId).query(Boolean.class).single();
    }

    private List<Evaluator> names(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Map<UUID, String> n = new HashMap<>();
        jdbc.sql("SELECT id, display_name FROM user_account WHERE id = ANY (?)").param(ids.toArray(UUID[]::new))
                .query((rs, i) -> n.put(rs.getObject(1, UUID.class), rs.getString(2))).list();
        return ids.stream().map(id -> new Evaluator(id, n.getOrDefault(id, "Unknown user"))).toList();
    }

    /** Active staff of the organization who hold evaluation.write for the unit. */
    private List<Evaluator> eligible(UUID unit) {
        UUID tenant = TenantContext.requireTenantId();
        List<Evaluator> out = new ArrayList<>();
        for (Evaluator e : jdbc.sql("SELECT id, display_name FROM user_account WHERE status = 'ACTIVE' ORDER BY display_name, id LIMIT 1000")
                .query((rs, n) -> new Evaluator(rs.getObject(1, UUID.class), rs.getString(2))).list()) {
            Optional<IvoroviPrincipal> p = directory.activeUser(tenant, e.userId());
            if (p.isPresent() && authz.principalHasInOrgUnit(p.get(), WRITE, unit)) out.add(e);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Setup setup(UUID scheduleId) {
        UUID unit = manageUnit(scheduleId);
        Optional<SetupRow> s = setupRow(scheduleId, false);
        return new Setup(scheduleId, s.map(SetupRow::mode).orElse("SINGLE"), s.map(SetupRow::tolerance).orElse(BigDecimal.TEN),
                s.map(r -> names(List.of(r.evaluators()))).orElse(List.of()), eligible(unit), locked(scheduleId), s.isPresent());
    }

    @Transactional
    public Setup saveSetup(UUID scheduleId, String mode, BigDecimal tolerancePercent, List<UUID> evaluatorIds) {
        UUID unit = manageUnit(scheduleId);
        if (!"SINGLE".equals(mode) && !"DOUBLE".equals(mode)) throw ApiException.badRequest("invalid_mode", "Mode is SINGLE or DOUBLE.");
        BigDecimal tol = tolerancePercent == null ? BigDecimal.TEN : tolerancePercent;
        if (tol.signum() < 0 || tol.compareTo(BigDecimal.valueOf(100)) > 0 || tol.stripTrailingZeros().scale() > 2)
            throw ApiException.badRequest("invalid_tolerance", "Tolerance: 0–100 percent of the question's marks, at most two decimals.");
        List<UUID> pool = new ArrayList<>(new LinkedHashSet<>(evaluatorIds == null ? List.of() : evaluatorIds));
        int needed = "DOUBLE".equals(mode) ? 2 : 1;
        if (pool.size() < needed) throw ApiException.badRequest("too_few_evaluators", ("DOUBLE".equals(mode) ? "Double marking needs at least two evaluators." : "Choose at least one evaluator."));
        UUID tenant = TenantContext.requireTenantId();
        for (UUID id : pool) {
            Optional<IvoroviPrincipal> p = directory.activeUser(tenant, id);
            if (p.isEmpty() || !authz.principalHasInOrgUnit(p.get(), WRITE, unit))
                throw ApiException.badRequest("not_an_evaluator", "Every evaluator must be an active user allowed to evaluate for this institution.");
        }
        Optional<SetupRow> before = setupRow(scheduleId, true);
        if (before.isPresent() && locked(scheduleId) && (!before.get().mode().equals(mode) || before.get().tolerance().compareTo(tol) != 0))
            throw ApiException.conflict("evaluation_started", "Marks have been submitted; the mode and tolerance can no longer change.");
        jdbc.sql("""
                INSERT INTO evaluation_setup (schedule_id, tenant_id, org_unit_id, mode, tolerance_pct, evaluator_ids, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (schedule_id) DO UPDATE SET mode = EXCLUDED.mode, tolerance_pct = EXCLUDED.tolerance_pct, evaluator_ids = EXCLUDED.evaluator_ids,
                  updated_by = EXCLUDED.updated_by, updated_at = now()""")
                .params(scheduleId, tenant, unit, mode, tol, pool.toArray(UUID[]::new), me()).update();
        audit.recordSuccess("evaluation.setup.changed", "exam_schedule", scheduleId.toString(), Map.of("mode", mode, "tolerancePercent", tol.toPlainString(),
                "evaluators", String.valueOf(pool.size())));
        return setup(scheduleId);
    }

    // ================================================================================================ scripts and allocation

    private static String newCode() {
        StringBuilder b = new StringBuilder("S-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) b.append('-');
            b.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return b.toString();
    }

    /** Creates a script (and its items) for every attempt with descriptive answers awaiting evaluation. Idempotent. */
    private int syncScripts(UUID scheduleId) {
        UUID tenant = TenantContext.requireTenantId();
        Set<UUID> known = new java.util.HashSet<>(jdbc.sql("SELECT attempt_id FROM evaluation_script WHERE schedule_id = ?").param(scheduleId).query(UUID.class).list());
        int created = 0;
        for (UUID attempt : items.deferredAttempts(scheduleId, ItemResults.Source.EVALUATION)) {
            if (known.contains(attempt)) continue;
            UUID script = Ids.newId();
            boolean inserted = false;
            for (int i = 0; i < 5 && !inserted; i++) {
                try {
                    jdbc.sql("INSERT INTO evaluation_script (id, tenant_id, schedule_id, attempt_id, code) VALUES (?, ?, ?, ?, ?)")
                            .params(script, tenant, scheduleId, attempt, newCode()).update();
                    inserted = true;
                } catch (DuplicateKeyException e) {
                    if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM evaluation_script WHERE attempt_id = ?)").param(attempt).query(Boolean.class).single()) break;
                }
            }
            if (!inserted) continue;
            for (ItemResults.Item it : items.deferred(attempt)) {
                if (it.source() != ItemResults.Source.EVALUATION) continue;
                jdbc.sql("INSERT INTO evaluation_item (id, tenant_id, script_id, position, question_version_id, marks) VALUES (?, ?, ?, ?, ?, ?)")
                        .params(Ids.newId(), tenant, script, it.position(), it.questionVersionId(), it.marks()).update();
            }
            created++;
        }
        return created;
    }

    /** Allocates every open item to the evaluators with the fewest open tasks; DOUBLE gives each item two distinct evaluators. */
    @Transactional
    public Progress allocate(UUID scheduleId) {
        manageUnit(scheduleId);
        SetupRow s = setupRow(scheduleId, true).orElseThrow(() -> ApiException.conflict("evaluation_not_set_up", "Set up evaluation for this examination first."));
        int scripts = syncScripts(scheduleId);
        UUID tenant = TenantContext.requireTenantId();
        List<UUID> pool = List.of(s.evaluators());
        Map<UUID, Integer> load = new HashMap<>();
        for (UUID e : pool) load.put(e, 0);
        jdbc.sql("SELECT evaluator_id, count(*) FROM evaluation_task WHERE status = 'ASSIGNED' AND evaluator_id = ANY (?) GROUP BY evaluator_id")
                .param(pool.toArray(UUID[]::new)).query((rs, n) -> load.put(rs.getObject(1, UUID.class), rs.getInt(2))).list();
        int rounds = "DOUBLE".equals(s.mode()) ? 2 : 1;
        record Open(UUID itemId, List<UUID> evaluators, int maxRound) {}
        List<Open> open = jdbc.sql("""
                SELECT i.id, coalesce(array_agg(t.evaluator_id) FILTER (WHERE t.id IS NOT NULL), '{}'), coalesce(max(t.round), 0)
                FROM evaluation_item i JOIN evaluation_script sc ON sc.id = i.script_id LEFT JOIN evaluation_task t ON t.item_id = i.id
                WHERE sc.schedule_id = ? AND i.status = 'OPEN' GROUP BY i.id, sc.code, i.position ORDER BY sc.code, i.position""").param(scheduleId)
                .query((rs, n) -> new Open(rs.getObject(1, UUID.class), List.of((UUID[]) rs.getArray(2).getArray()), rs.getInt(3))).list();
        int assigned = 0;
        for (Open o : open) {
            List<UUID> already = new ArrayList<>(o.evaluators());
            for (int round = o.maxRound() + 1; round <= rounds; round++) {
                Optional<UUID> pick = pool.stream().filter(e -> !already.contains(e))
                        .min(Comparator.<UUID>comparingInt(load::get).thenComparing(UUID::toString));
                if (pick.isEmpty()) break;
                jdbc.sql("INSERT INTO evaluation_task (id, tenant_id, item_id, round, evaluator_id) VALUES (?, ?, ?, ?, ?)")
                        .params(Ids.newId(), tenant, o.itemId(), round, pick.get()).update();
                already.add(pick.get());
                load.merge(pick.get(), 1, Integer::sum);
                assigned++;
            }
        }
        audit.recordSuccess("evaluation.allocated", "exam_schedule", scheduleId.toString(), Map.of("newScripts", String.valueOf(scripts), "tasks", String.valueOf(assigned)));
        return progressOf(scheduleId);
    }

    @Transactional(readOnly = true)
    public Progress progress(UUID scheduleId) {
        manageUnit(scheduleId);
        return progressOf(scheduleId);
    }

    private Progress progressOf(UUID scheduleId) {
        int scripts = jdbc.sql("SELECT count(*) FROM evaluation_script WHERE schedule_id = ?").param(scheduleId).query(Integer.class).single();
        Map<String, Integer> st = new HashMap<>();
        jdbc.sql("SELECT i.status, count(*) FROM evaluation_item i JOIN evaluation_script s ON s.id = i.script_id WHERE s.schedule_id = ? GROUP BY i.status")
                .param(scheduleId).query((rs, n) -> st.put(rs.getString(1), rs.getInt(2))).list();
        int awaiting = (int) items.deferredAttempts(scheduleId, ItemResults.Source.EVALUATION).stream().count() - scripts;
        Map<UUID, int[]> per = new LinkedHashMap<>();
        setupRow(scheduleId, false).ifPresent(s -> { for (UUID e : s.evaluators()) per.put(e, new int[2]); });
        jdbc.sql("""
                SELECT t.evaluator_id, t.status, count(*) FROM evaluation_task t JOIN evaluation_item i ON i.id = t.item_id JOIN evaluation_script s ON s.id = i.script_id
                WHERE s.schedule_id = ? GROUP BY t.evaluator_id, t.status""").param(scheduleId)
                .query((rs, n) -> per.computeIfAbsent(rs.getObject(1, UUID.class), k -> new int[2])["SUBMITTED".equals(rs.getString(2)) ? 1 : 0] += rs.getInt(3)).list();
        List<Evaluator> who = names(new ArrayList<>(per.keySet()));
        List<Load> loads = who.stream().map(e -> new Load(e.userId(), e.displayName(), per.get(e.userId())[0], per.get(e.userId())[1])).toList();
        return new Progress(scheduleId, scripts, st.getOrDefault("OPEN", 0), st.getOrDefault("MODERATION", 0), st.getOrDefault("FINAL", 0), Math.max(0, awaiting), loads);
    }

    // ================================================================================================ evaluator

    private record Task(UUID id, UUID itemId, int round, UUID evaluatorId, String status, String scriptCode, int position, UUID versionId, BigDecimal marks,
                        UUID attemptId, UUID orgUnitId, String criterionMarks, BigDecimal total, String comment, String itemStatus) {}

    private Optional<Task> task(UUID taskId, boolean lock) {
        return jdbc.sql("""
                SELECT t.id, t.item_id, t.round, t.evaluator_id, t.status, s.code, i.position, i.question_version_id, i.marks, s.attempt_id, es.org_unit_id,
                       t.criterion_marks::text, t.total, t.comment, i.status
                FROM evaluation_task t JOIN evaluation_item i ON i.id = t.item_id JOIN evaluation_script s ON s.id = i.script_id
                JOIN evaluation_setup es ON es.schedule_id = s.schedule_id WHERE t.id = ?""" + (lock ? " FOR UPDATE OF t, i" : "")).param(taskId)
                .query((rs, n) -> new Task(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getString(6), rs.getInt(7), rs.getObject(8, UUID.class), rs.getBigDecimal(9), rs.getObject(10, UUID.class), rs.getObject(11, UUID.class),
                        rs.getString(12), rs.getBigDecimal(13), rs.getString(14), rs.getString(15))).optional();
    }

    /** The caller's own task, while they may still evaluate for its institution; 404 otherwise (never reveals other tasks). */
    private Task mine(UUID taskId, boolean lock) {
        Task t = task(taskId, lock).orElseThrow(() -> ApiException.notFound("Evaluation task"));
        if (!t.evaluatorId().equals(me()) || !authz.hasInOrgUnit(WRITE, t.orgUnitId())) throw ApiException.notFound("Evaluation task");
        return t;
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @Transactional(readOnly = true)
    public List<TaskSummary> myTasks(String status) {
        if (!authz.hasAnywhere(WRITE)) throw new AccessDeniedException("Missing permission " + WRITE);
        String filter = status == null || status.isBlank() ? "" : " AND t.status = ?";
        var q = jdbc.sql("""
                SELECT t.id, s.code, i.position, i.marks, t.status, t.assigned_at, t.submitted_at
                FROM evaluation_task t JOIN evaluation_item i ON i.id = t.item_id JOIN evaluation_script s ON s.id = i.script_id
                WHERE t.evaluator_id = ?""" + filter + " ORDER BY t.status, t.assigned_at, s.code, i.position LIMIT 500").param(me());
        if (!filter.isEmpty()) q = q.param(status);
        return q.query((rs, n) -> new TaskSummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4), rs.getString(5),
                ts(rs.getTimestamp(6)), ts(rs.getTimestamp(7)))).list();
    }

    @SuppressWarnings("unchecked")
    private List<CriterionMark> criterionMarks(List<EvaluationMaterial.Criterion> rubric, BigDecimal marks, String stored) {
        Map<String, Object> given = stored == null ? Map.of() : json.readValue(stored, Map.class);
        return rubric.stream().map(c -> new CriterionMark(c.id(), c.title(), max(marks, c.weight()),
                given.get(c.id()) == null ? null : new BigDecimal(String.valueOf(given.get(c.id()))))).toList();
    }

    private static BigDecimal max(BigDecimal marks, int weight) {
        return marks.multiply(BigDecimal.valueOf(weight)).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
    }

    private static String text(Object response) {
        return response == null ? null : String.valueOf(response);
    }

    @Transactional
    public TaskDetail detail(UUID taskId) {
        Task t = mine(taskId, false);
        EvaluationMaterial.Material m = material.material(t.versionId());
        String model = material.modelAnswer(t.versionId(), t.id()).orElse(null);
        String response = text(items.response(t.attemptId(), t.position()));
        audit.recordSuccess("evaluation.task.viewed", "evaluation_task", t.id().toString(), Map.of("script", t.scriptCode()));
        return new TaskDetail(t.id(), t.scriptCode(), t.position(), t.status(), t.marks(), m.content(), m.maxWords(), m.rubric(), model, response,
                criterionMarks(m.rubric(), t.marks(), t.criterionMarks()), t.total(), t.comment());
    }

    @Transactional
    public TaskDetail submit(UUID taskId, Submission in) {
        Task t = mine(taskId, true);
        if (!"ASSIGNED".equals(t.status())) throw ApiException.conflict("already_submitted", "This evaluation has been submitted and cannot change.");
        if (!"OPEN".equals(t.itemStatus())) throw ApiException.conflict("item_closed", "This answer no longer needs this evaluation.");
        EvaluationMaterial.Material m = material.material(t.versionId());
        Map<String, BigDecimal> criteria = new LinkedHashMap<>();
        BigDecimal total;
        if (!m.rubric().isEmpty()) {
            Map<String, BigDecimal> given = in == null || in.criteria() == null ? Map.of() : in.criteria();
            Set<String> ids = new LinkedHashSet<>();
            for (EvaluationMaterial.Criterion c : m.rubric()) ids.add(c.id());
            if (!given.keySet().equals(ids)) throw ApiException.badRequest("criteria_required", "Give marks for every rubric criterion (" + String.join(", ", ids) + ").");
            total = BigDecimal.ZERO;
            for (EvaluationMaterial.Criterion c : m.rubric()) {
                BigDecimal v = given.get(c.id());
                check(v, max(t.marks(), c.weight()), c.title());
                criteria.put(c.id(), v.setScale(2, RoundingMode.UNNECESSARY));
                total = total.add(v);
            }
        } else {
            BigDecimal v = in == null ? null : in.holistic();
            check(v, t.marks(), "The mark");
            total = v;
        }
        String comment = in == null || in.comment() == null || in.comment().isBlank() ? null : in.comment().strip();
        if (comment != null && comment.length() > 2000) throw ApiException.badRequest("too_long", "Comment: at most 2,000 characters.");
        jdbc.sql("UPDATE evaluation_task SET status = 'SUBMITTED', submitted_at = now(), criterion_marks = CAST(? AS jsonb), total = ?, comment = ? WHERE id = ?")
                .params(criteria.isEmpty() ? null : json.writeValueAsString(criteria), total.setScale(2, RoundingMode.UNNECESSARY), comment, t.id()).update();
        audit.recordSuccess("evaluation.task.submitted", "evaluation_task", t.id().toString(), Map.of("script", t.scriptCode(), "round", String.valueOf(t.round()),
                "total", total.stripTrailingZeros().toPlainString()));
        reconcile(t.itemId());
        return detail(taskId);
    }

    private static void check(BigDecimal v, BigDecimal max, String what) {
        if (v == null || v.signum() < 0 || v.compareTo(max) > 0 || v.stripTrailingZeros().scale() > 2)
            throw ApiException.badRequest("invalid_marks", what + ": between 0 and " + max.stripTrailingZeros().toPlainString() + ", at most two decimals.");
    }

    // ================================================================================================ reconciliation and moderation

    private record Item(UUID id, UUID scriptId, UUID attemptId, int position, BigDecimal marks, String status, String mode, BigDecimal tolerancePct, String code,
                        UUID orgUnitId, UUID scheduleId) {}

    private Item item(UUID itemId) {
        return jdbc.sql("""
                SELECT i.id, i.script_id, s.attempt_id, i.position, i.marks, i.status, es.mode, es.tolerance_pct, s.code, es.org_unit_id, s.schedule_id
                FROM evaluation_item i JOIN evaluation_script s ON s.id = i.script_id JOIN evaluation_setup es ON es.schedule_id = s.schedule_id WHERE i.id = ?""")
                .param(itemId).query((rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9), rs.getObject(10, UUID.class),
                        rs.getObject(11, UUID.class))).optional().orElseThrow(() -> ApiException.notFound("Evaluation item"));
    }

    private static BigDecimal tolerance(Item i) {
        return i.marks().multiply(i.tolerancePct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private void reconcile(UUID itemId) {
        Item i = item(itemId);
        if (!"OPEN".equals(i.status())) return;
        List<BigDecimal> totals = jdbc.sql("SELECT total FROM evaluation_task WHERE item_id = ? AND status = 'SUBMITTED' ORDER BY round").param(itemId)
                .query(BigDecimal.class).list();
        if ("SINGLE".equals(i.mode())) {
            if (!totals.isEmpty()) finalize(i, totals.get(0), "SINGLE", null);
            return;
        }
        if (totals.size() < 2) return;
        BigDecimal diff = totals.get(0).subtract(totals.get(1)).abs();
        if (diff.compareTo(tolerance(i)) <= 0) {
            finalize(i, totals.get(0).add(totals.get(1)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), "AGREED", null);
        } else {
            jdbc.sql("UPDATE evaluation_item SET status = 'MODERATION' WHERE id = ?").param(itemId).update();
            audit.recordSuccess("evaluation.item.moderation_required", "evaluation_item", itemId.toString(), Map.of("script", i.code(),
                    "difference", diff.stripTrailingZeros().toPlainString(), "tolerance", tolerance(i).stripTrailingZeros().toPlainString()));
            outbox.append("evaluation_item", itemId.toString(), "evaluation.moderation.required", Map.of("scheduleId", i.scheduleId().toString(), "itemId", itemId.toString()));
        }
    }

    private void finalize(Item i, BigDecimal awarded, String source, String reason) {
        jdbc.sql("""
                UPDATE evaluation_item SET status = 'FINAL', final_awarded = ?, final_source = ?, finalized_at = now(),
                  moderated_by = ?, moderation_reason = ? WHERE id = ?""")
                .params(awarded, source, reason == null ? null : me(), reason, i.id()).update();
        audit.recordSuccess("evaluation.item.final", "evaluation_item", i.id().toString(), Map.of("script", i.code(), "source", source,
                "awarded", awarded.stripTrailingZeros().toPlainString()));
        items.record(i.attemptId(), i.position(), awarded, ItemResults.Source.EVALUATION, "evaluation " + source.toLowerCase());
    }

    @Transactional(readOnly = true)
    public List<ModerationItem> moderation(UUID scheduleId) {
        manageUnit(scheduleId);
        UUID me = me();
        List<Item> open = jdbc.sql("""
                SELECT i.id FROM evaluation_item i JOIN evaluation_script s ON s.id = i.script_id WHERE s.schedule_id = ? AND i.status = 'MODERATION'
                ORDER BY s.code, i.position""").param(scheduleId).query(UUID.class).list().stream().map(this::item).toList();
        List<ModerationItem> out = new ArrayList<>();
        for (Item i : open) {
            EvaluationMaterial.Material m = null;
            List<ModerationMark> given = new ArrayList<>();
            boolean evaluatedIt = false;
            record T(UUID evaluator, int round, BigDecimal total, String criteria, String comment, UUID version) {}
            for (T t : jdbc.sql("""
                    SELECT t.evaluator_id, t.round, t.total, t.criterion_marks::text, t.comment, ei.question_version_id FROM evaluation_task t
                    JOIN evaluation_item ei ON ei.id = t.item_id WHERE t.item_id = ? AND t.status = 'SUBMITTED' ORDER BY t.round""").param(i.id())
                    .query((rs, n) -> new T(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class))).list()) {
                if (m == null) m = material.material(t.version());
                if (t.evaluator().equals(me)) evaluatedIt = true;
                given.add(new ModerationMark("Evaluator " + t.round(), t.total(), criterionMarks(m.rubric(), i.marks(), t.criteria()), t.comment()));
            }
            out.add(new ModerationItem(i.id(), i.code(), i.position(), i.marks(), tolerance(i), given, !evaluatedIt));
        }
        return out;
    }

    @Transactional
    public void moderate(UUID itemId, BigDecimal awarded, String reason) {
        Item i = item(itemId);
        if (!authz.hasInOrgUnit(MANAGE, i.orgUnitId())) throw ApiException.notFound("Evaluation item");
        if (!"MODERATION".equals(i.status())) throw ApiException.conflict("not_in_moderation", "This answer does not need moderation.");
        boolean evaluatedIt = jdbc.sql("SELECT EXISTS (SELECT 1 FROM evaluation_task WHERE item_id = ? AND evaluator_id = ?)").params(itemId, me()).query(Boolean.class).single();
        if (evaluatedIt) throw new AccessDeniedException("An evaluator of this answer cannot moderate it");
        check(awarded, i.marks(), "The moderated mark");
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 2000) throw ApiException.badRequest("reason_required", "Give a reason for the moderated mark (at most 2,000 characters).");
        finalize(i, awarded.setScale(2, RoundingMode.UNNECESSARY), "MODERATED", why);
    }

    // ================================================================================================ AI assistance (C-2 §4)

    @Override
    @Transactional
    public Optional<TaskView> forAssistance(UUID taskId) {
        Optional<Task> t = task(taskId, false);
        if (t.isEmpty() || !t.get().evaluatorId().equals(me()) || !authz.hasInOrgUnit(WRITE, t.get().orgUnitId()) || !"ASSIGNED".equals(t.get().status())) return Optional.empty();
        EvaluationMaterial.Material m = material.material(t.get().versionId());
        audit.recordSuccess("evaluation.task.assistance_viewed", "evaluation_task", taskId.toString(), Map.of("script", t.get().scriptCode()));
        return Optional.of(new TaskView(taskId, t.get().scriptCode(), t.get().versionId(), t.get().marks(), m.rubric(), text(items.response(t.get().attemptId(), t.get().position()))));
    }

    @Override
    @Transactional
    public void recordSuggestionUse(UUID taskId, UUID suggestionId, SuggestionUse use) {
        Task t = mine(taskId, false);
        try {
            jdbc.sql("INSERT INTO evaluation_suggestion_use (task_id, tenant_id, suggestion_id, use) VALUES (?, ?, ?, ?)")
                    .params(t.id(), TenantContext.requireTenantId(), suggestionId, use.name()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("already_recorded", "The use of a suggestion is recorded once per evaluation.");
        }
        audit.recordSuccess("evaluation.suggestion.used", "evaluation_task", taskId.toString(), Map.of("use", use.name()));
    }
}
