package com.ivorovi.assessments;

import com.ivorovi.assessments.internal.PaperService;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module API for scheduling and delivery (Increment 2.2 interface contract, owned by the assessments module):
 * published assessment versions only, never drafts; no question content; no answer information.
 * <ul>
 *   <li>{@link #publishedVersions} / {@link #version}: what can be scheduled, checked against assessment.read on the
 *       assessment's institution (404 otherwise);</li>
 *   <li>{@link #reference}: a permanent reference that keeps the version alive for a schedule, attempt or result;</li>
 *   <li>{@link #paperForAttempt}: the candidate's paper for one attempt, idempotent per attempt reference.</li>
 * </ul>
 * A schedule always stores the version id it was created for: a later publication never changes it.
 */
@Component
public class PublishedAssessments {

    @Schema(name = "PublishedAssessmentVersion")
    public record Published(UUID assessmentId, UUID versionId, int versionNo, String code, String title, String purpose, String governance,
                            UUID orgUnitId, int durationMinutes, BigDecimal totalMarks, String state, Instant publishedAt) {}

    /** One question of a candidate's paper: candidate projection (options in drawn order) and marks. Nothing else. */
    @Schema(name = "CandidatePaperItem")
    public record CandidateItem(int position, UUID questionVersionId, String type, Map<String, Object> content, Map<String, Object> projection,
                                BigDecimal marks, BigDecimal negativeMarks) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final PaperService papers;

    PublishedAssessments(JdbcClient jdbc, Authz authz, AuditLog audit, PaperService papers) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.papers = papers;
    }

    private static final String SELECT = """
            SELECT a.id, v.id, v.version_no, a.code, v.title, a.purpose, v.governance, a.org_unit_id, v.duration_minutes, v.total_marks, v.state, v.published_at
            FROM assessment_version v JOIN assessment a ON a.id = v.assessment_id""";

    private static Published row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Published(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getObject(8, UUID.class), rs.getInt(9), rs.getBigDecimal(10), rs.getString(11),
                rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant());
    }

    /** Currently published versions the caller may read (one per assessment), newest publication first. */
    @Transactional(readOnly = true)
    public List<Published> publishedVersions() {
        return jdbc.sql(SELECT + " WHERE v.state = 'PUBLISHED' AND a.status = 'ACTIVE' ORDER BY v.published_at DESC LIMIT 500")
                .query((rs, n) -> row(rs)).list().stream().filter(p -> authz.hasInOrgUnit("assessment.read", p.orgUnitId())).toList();
    }

    /** A version that is or was published (PUBLISHED, SUPERSEDED, WITHDRAWN); 404 for drafts or when the caller may not read it. */
    @Transactional(readOnly = true)
    public Published version(UUID versionId) {
        Published p = jdbc.sql(SELECT + " WHERE v.id = ? AND v.state IN ('PUBLISHED', 'SUPERSEDED', 'WITHDRAWN')").param(versionId)
                .query((rs, n) -> row(rs)).optional().orElseThrow(() -> ApiException.notFound("Published assessment"));
        if (!authz.hasInOrgUnit("assessment.read", p.orgUnitId())) throw ApiException.notFound("Published assessment");
        return p;
    }

    /** Permanent reference (idempotent). Only published versions can be referenced; references are never removed. */
    @Transactional
    public void reference(UUID versionId, String referrerType, String referrerId) {
        try {
            int n = jdbc.sql("""
                    INSERT INTO assessment_version_reference (id, tenant_id, version_id, referrer_type, referrer_id) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, version_id, referrer_type, referrer_id) DO NOTHING""")
                    .params(Ids.newId(), TenantContext.requireTenantId(), versionId, referrerType, referrerId).update();
            if (n > 0) audit.recordSuccess("assessment.version_referenced", "assessment_version", versionId.toString(), Map.of("by", referrerType + ":" + referrerId));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("version_not_referenceable", "Only published assessment versions can be scheduled.");
        }
    }

    /** The candidate's paper (delivery only, for the paper bound to the candidate's own attempt). */
    @Transactional(readOnly = true)
    public List<CandidateItem> candidatePaper(UUID paperId) {
        return papers.candidateItems(paperId).stream().map(i -> new CandidateItem(i.position(), i.questionVersionId(), i.type(), i.content(), i.projection(),
                i.marks(), i.negativeMarks())).toList();
    }

    /** The paper of one candidate attempt from the published version (delivery, Increment 2.5); same reference → same paper. */
    @Transactional
    public UUID paperForAttempt(UUID versionId, String attemptRef) {
        return papers.generateForAttempt(versionId, attemptRef);
    }
}
