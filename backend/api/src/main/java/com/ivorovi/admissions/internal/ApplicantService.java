package com.ivorovi.admissions.internal;

import static com.ivorovi.admissions.internal.AdmissionAccess.tenant;
import static com.ivorovi.admissions.internal.AdmissionAccess.text;

import com.ivorovi.exams.ExamCandidates;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The applicant portal (candidate realm): the signed-in candidate of the cycle's exam portal keeps one applicant profile
 * (personal data encrypted) and applies to offerings of open cycles of that portal. Applicants see only their own
 * applications; screening detail and staff notes are not shown to them.
 */
@Service
public class ApplicantService {

    static final String DOB = "applicant.date_of_birth", MOBILE = "applicant.mobile", ADDRESS = "applicant.address", CATEGORY = "applicant.category",
            EMAIL = "applicant.email";
    static final Set<String> APPLICANT_EDITABLE = Set.of("DRAFT");
    static final Set<String> WITHDRAWABLE = Set.of("DRAFT", "SUBMITTED", "ELIGIBLE", "INELIGIBLE", "SHORTLISTED", "TESTED", "RANKED");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 -]{5,18}[0-9]$");

    @Schema(name = "ApplicantProfile")
    public record Profile(String firstName, String middleName, String lastName, LocalDate dateOfBirth, String gender, String nationality, String mobile,
                          String address, String category, long version) {}

    public record ProfileCommand(String firstName, String middleName, String lastName, LocalDate dateOfBirth, String gender, String nationality, String mobile,
                                 String address, String category, Long version) {}

    @Schema(name = "OpenOffering")
    public record OpenOffering(UUID offeringId, UUID cycleId, String cycleName, String institutionName, String programName, String courseName, int seats,
                               Instant closesAt, List<String> requiredDocuments, List<String> rules) {}

    @Schema(name = "MyApplication")
    public record MyApplication(UUID id, String applicationNo, UUID offeringId, String cycleName, String courseName, String status, BigDecimal qualifyingPercent,
                                String qualifyingBoard, Instant submittedAt, List<AdmissionDocuments.Document> documents, List<String> missingDocuments,
                                UUID offerId, String offerStatus, Instant offerExpiresAt, long version) {}

    @Schema(name = "ApplicantPortal")
    public record Portal(Profile profile, List<OpenOffering> open, List<MyApplication> applications) {}

    public record ApplicationCommand(UUID offeringId, BigDecimal qualifyingPercent, String qualifyingBoard, Long version) {}

    private final JdbcClient jdbc;
    private final ExamCandidates candidates;
    private final FieldCipher cipher;
    private final AdmissionAccess access;
    private final AdmissionDocuments documents;
    private final CycleService cycles;

    ApplicantService(JdbcClient jdbc, ExamCandidates candidates, FieldCipher cipher, AdmissionAccess access, AdmissionDocuments documents, CycleService cycles) {
        this.jdbc = jdbc;
        this.candidates = candidates;
        this.cipher = cipher;
        this.access = access;
        this.documents = documents;
        this.cycles = cycles;
    }

    record Me(UUID candidateId, UUID portalId, String email, UUID applicantId) {}

    private Me me(String portalKey) {
        ExamCandidates.Candidate c = candidates.current(portalKey);
        UUID applicant = jdbc.sql("SELECT id FROM applicant WHERE candidate_id = ?").param(c.candidateId()).query(UUID.class).optional().orElse(null);
        return new Me(c.candidateId(), c.portalId(), c.email(), applicant);
    }

    private String dec(String v, String field) {
        return v == null ? null : cipher.decrypt(v, field, tenant());
    }

    private String enc(String v, String field) {
        return v == null ? null : cipher.encrypt(v, field, tenant());
    }

    @Transactional(readOnly = true)
    public Portal portal(String portalKey) {
        Me me = me(portalKey);
        Profile profile = me.applicantId() == null ? null : jdbc.sql("""
                SELECT first_name, middle_name, last_name, date_of_birth_enc, gender, nationality, mobile_enc, address_enc, category_enc, version
                FROM applicant WHERE id = ?""").param(me.applicantId())
                .query((rs, n) -> {
                    String dob = dec(rs.getString(4), DOB);
                    return new Profile(rs.getString(1), rs.getString(2), rs.getString(3), dob == null ? null : LocalDate.parse(dob), rs.getString(5), rs.getString(6),
                            dec(rs.getString(7), MOBILE), dec(rs.getString(8), ADDRESS), dec(rs.getString(9), CATEGORY), rs.getLong(10));
                }).single();
        List<OpenOffering> open = jdbc.sql("""
                SELECT o.id, c.id, c.name, u.name, p.name, co.name, o.seats, c.closes_at, o.required_documents
                FROM admission_offering o JOIN admission_cycle c ON c.id = o.cycle_id JOIN org_unit u ON u.id = c.institution_id
                JOIN program p ON p.id = o.program_id JOIN course co ON co.id = o.course_id
                WHERE c.portal_id = ? AND c.status = 'OPEN' AND o.status = 'ACTIVE' AND now() BETWEEN c.opens_at AND c.closes_at
                ORDER BY c.closes_at, co.name""").param(me.portalId())
                .query((rs, n) -> new OpenOffering(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7), rs.getTimestamp(8).toInstant(), List.of((String[]) rs.getArray(9).getArray()),
                        cycles.rules(rs.getObject(1, UUID.class)).stream().map(CycleService.Rule::description).toList())).list();
        List<MyApplication> mine = me.applicantId() == null ? List.of() : jdbc.sql("""
                SELECT a.id FROM admission_application a WHERE a.applicant_id = ? ORDER BY a.created_at DESC""").param(me.applicantId())
                .query(UUID.class).list().stream().map(this::view).toList();
        return new Portal(profile, open, mine);
    }

    private MyApplication view(UUID id) {
        return jdbc.sql("""
                SELECT a.id, a.application_no, a.offering_id, c.name, co.name, a.status, a.qualifying_percent, a.qualifying_board, a.submitted_at, a.version,
                       o.required_documents, f.id AS offer_id, f.status AS offer_status, f.expires_at
                FROM admission_application a JOIN admission_cycle c ON c.id = a.cycle_id JOIN admission_offering o ON o.id = a.offering_id
                JOIN course co ON co.id = o.course_id
                LEFT JOIN admission_offer f ON f.application_id = a.id AND f.status IN ('ISSUED', 'ACCEPTED', 'DECLINED', 'EXPIRED')
                WHERE a.id = ? ORDER BY f.created_at DESC NULLS LAST LIMIT 1""").param(id)
                .query((rs, n) -> {
                    List<AdmissionDocuments.Document> docs = documents.list(id);
                    List<String> required = List.of((String[]) rs.getArray("required_documents").getArray());
                    List<String> missing = required.stream().filter(r -> docs.stream().noneMatch(d -> d.category().equals(r))).toList();
                    java.sql.Timestamp exp = rs.getTimestamp("expires_at");
                    java.sql.Timestamp sub = rs.getTimestamp(9);
                    return new MyApplication(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getBigDecimal(7), rs.getString(8), sub == null ? null : sub.toInstant(), docs, missing,
                            rs.getObject("offer_id", UUID.class), rs.getString("offer_status"), exp == null ? null : exp.toInstant(), rs.getLong(10));
                }).single();
    }

    @Transactional
    public Profile saveProfile(String portalKey, ProfileCommand c) {
        Me me = me(portalKey);
        String first = text(c.firstName(), 100, "firstName", true), middle = text(c.middleName(), 100, "middleName", false),
                last = text(c.lastName(), 100, "lastName", false), nationality = text(c.nationality(), 60, "nationality", false),
                address = text(c.address(), 500, "address", false), mobile = text(c.mobile(), 25, "mobile", false), category = text(c.category(), 40, "category", false);
        if (mobile != null && !PHONE.matcher(mobile).matches()) throw ApiException.badRequest("invalid_mobile", "Enter a valid mobile number.");
        if (c.dateOfBirth() != null && (c.dateOfBirth().isAfter(LocalDate.now().minusYears(10)) || c.dateOfBirth().isBefore(LocalDate.of(1930, 1, 1)))) {
            throw ApiException.badRequest("invalid_dateOfBirth", "Enter a realistic date of birth.");
        }
        if (c.gender() != null && !Set.of("FEMALE", "MALE", "OTHER", "UNDISCLOSED").contains(c.gender())) throw ApiException.badRequest("invalid_gender", "Choose a gender.");
        if (category != null && !category.matches("^[A-Z0-9_]{1,40}$")) throw ApiException.badRequest("invalid_category", "Choose a category.");
        String dob = c.dateOfBirth() == null ? null : c.dateOfBirth().toString();
        if (me.applicantId() == null) {
            jdbc.sql("""
                    INSERT INTO applicant (id, tenant_id, candidate_id, email_enc, email_index, first_name, middle_name, last_name, date_of_birth_enc, mobile_enc,
                        address_enc, category_enc, gender, nationality) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), tenant(), me.candidateId(), enc(me.email(), EMAIL), candidates.emailIndex(me.email()), first, middle, last, enc(dob, DOB),
                            enc(mobile, MOBILE), enc(address, ADDRESS), enc(category, CATEGORY), c.gender(), nationality).update();
        } else {
            long v = jdbc.sql("SELECT version FROM applicant WHERE id = ? FOR UPDATE").param(me.applicantId()).query(Long.class).single();
            AdmissionAccess.version(c.version(), v);
            boolean locked = jdbc.sql("SELECT count(*) FROM admission_application WHERE applicant_id = ? AND status NOT IN ('DRAFT', 'WITHDRAWN')")
                    .param(me.applicantId()).query(Long.class).single() > 0;
            if (locked) throw ApiException.conflict("profile_locked", "Your details are part of a submitted application; contact the admissions office to change them.");
            jdbc.sql("""
                    UPDATE applicant SET first_name = ?, middle_name = ?, last_name = ?, date_of_birth_enc = ?, mobile_enc = ?, address_enc = ?, category_enc = ?,
                        gender = ?, nationality = ?, updated_at = now(), version = version + 1 WHERE id = ?""")
                    .params(first, middle, last, enc(dob, DOB), enc(mobile, MOBILE), enc(address, ADDRESS), enc(category, CATEGORY), c.gender(), nationality,
                            me.applicantId()).update();
        }
        return portal(portalKey).profile();
    }

    /** Creates or updates a draft application for an open offering of this portal. */
    @Transactional
    public MyApplication saveApplication(String portalKey, UUID applicationId, ApplicationCommand c) {
        Me me = me(portalKey);
        if (me.applicantId() == null) throw ApiException.conflict("profile_required", "Fill in your details first.");
        BigDecimal pct = c.qualifyingPercent();
        if (pct != null && (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0 || pct.scale() > 2)) {
            throw ApiException.badRequest("invalid_qualifyingPercent", "A percentage from 0 to 100 with at most two decimals.");
        }
        String board = text(c.qualifyingBoard(), 150, "qualifyingBoard", false);
        if (applicationId == null) {
            if (c.offeringId() == null) throw ApiException.badRequest("invalid_offeringId", "Choose the programme you apply for.");
            record O(UUID cycle, String code) {}
            O o = jdbc.sql("""
                    SELECT c.id, c.code FROM admission_offering o JOIN admission_cycle c ON c.id = o.cycle_id
                    WHERE o.id = ? AND c.portal_id = ? AND c.status = 'OPEN' AND o.status = 'ACTIVE' AND now() BETWEEN c.opens_at AND c.closes_at
                    FOR UPDATE OF c""").params(c.offeringId(), me.portalId()).query((rs, n) -> new O(rs.getObject(1, UUID.class), rs.getString(2))).optional()
                    .orElseThrow(() -> ApiException.notFound("Open offering"));
            if (jdbc.sql("SELECT count(*) FROM admission_application WHERE offering_id = ? AND applicant_id = ?").params(c.offeringId(), me.applicantId())
                    .query(Long.class).single() > 0) {
                throw ApiException.conflict("already_applied", "You have already applied for this programme.");
            }
            long seq = jdbc.sql("SELECT count(*) FROM admission_application WHERE cycle_id = ?").param(o.cycle()).query(Long.class).single() + 1;
            UUID id = Ids.newId();
            jdbc.sql("""
                    INSERT INTO admission_application (id, tenant_id, cycle_id, offering_id, applicant_id, application_no, qualifying_percent, qualifying_board)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""").params(id, tenant(), o.cycle(), c.offeringId(), me.applicantId(), o.code() + "-" + String.format("%05d", seq), pct, board)
                    .update();
            access.event(id, null, "DRAFT", "Application started", "APPLICANT");
            return view(id);
        }
        Row r = own(me, applicationId, true);
        if (!APPLICANT_EDITABLE.contains(r.status())) throw ApiException.conflict("application_submitted", "A submitted application cannot be changed.");
        AdmissionAccess.version(c.version(), r.version());
        jdbc.sql("UPDATE admission_application SET qualifying_percent = ?, qualifying_board = ?, updated_at = now(), version = version + 1 WHERE id = ?")
                .params(pct, board, applicationId).update();
        return view(applicationId);
    }

    record Row(UUID id, String status, long version, UUID offering) {}

    /** The applicant's own application (404 for anybody else's). */
    private Row own(Me me, UUID applicationId, boolean lock) {
        if (me.applicantId() == null) throw ApiException.notFound("Application");
        return jdbc.sql("SELECT id, status, version, offering_id FROM admission_application WHERE id = ? AND applicant_id = ?" + (lock ? " FOR UPDATE" : ""))
                .params(applicationId, me.applicantId()).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getObject(4, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Application"));
    }

    @Transactional
    public MyApplication submit(String portalKey, UUID applicationId, Long version) {
        Me me = me(portalKey);
        Row r = own(me, applicationId, true);
        if (!"DRAFT".equals(r.status())) throw ApiException.conflict("application_submitted", "This application was already submitted.");
        AdmissionAccess.version(version, r.version());
        boolean open = jdbc.sql("""
                SELECT count(*) FROM admission_application a JOIN admission_cycle c ON c.id = a.cycle_id
                WHERE a.id = ? AND c.status = 'OPEN' AND now() BETWEEN c.opens_at AND c.closes_at""").param(applicationId).query(Long.class).single() > 0;
        if (!open) throw ApiException.conflict("cycle_closed", "Applications for this cycle are closed.");
        MyApplication v = view(applicationId);
        if (v.qualifyingPercent() == null) throw ApiException.badRequest("invalid_qualifyingPercent", "Enter your qualifying marks.");
        if (!v.missingDocuments().isEmpty()) throw ApiException.conflict("documents_missing", "Upload the required documents first: " + String.join(", ", v.missingDocuments()) + ".");
        jdbc.sql("UPDATE admission_application SET status = 'SUBMITTED', submitted_at = now(), updated_at = now(), version = version + 1 WHERE id = ?")
                .param(applicationId).update();
        access.event(applicationId, "DRAFT", "SUBMITTED", "Submitted by the applicant", "APPLICANT");
        access.audit("admission.application.submitted", "admission_application", applicationId, Map.of("offeringId", r.offering().toString()));
        return view(applicationId);
    }

    @Transactional
    public MyApplication withdraw(String portalKey, UUID applicationId, Long version) {
        Me me = me(portalKey);
        Row r = own(me, applicationId, true);
        AdmissionAccess.version(version, r.version());
        if (!WITHDRAWABLE.contains(r.status())) throw ApiException.conflict("invalid_status_transition", "This application can no longer be withdrawn.");
        jdbc.sql("UPDATE admission_application SET status = 'WITHDRAWN', updated_at = now(), version = version + 1 WHERE id = ?").param(applicationId).update();
        access.event(applicationId, r.status(), "WITHDRAWN", "Withdrawn by the applicant", "APPLICANT");
        access.audit("admission.application.withdrawn", "admission_application", applicationId, Map.of("from", r.status()));
        return view(applicationId);
    }

    @Transactional
    public MyApplication upload(String portalKey, UUID applicationId, String category, String fileName, byte[] content) {
        Me me = me(portalKey);
        Row r = own(me, applicationId, true);
        if (!Set.of("DRAFT", "SUBMITTED").contains(r.status())) throw ApiException.conflict("application_locked", "Documents can be added until screening starts.");
        documents.upload(applicationId, category, fileName, content);
        return view(applicationId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> applicantOf(String portalKey) {
        return Optional.ofNullable(me(portalKey).applicantId());
    }
}
