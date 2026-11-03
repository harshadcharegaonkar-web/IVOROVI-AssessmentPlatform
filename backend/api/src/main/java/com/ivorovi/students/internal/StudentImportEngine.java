package com.ivorovi.students.internal;

import com.ivorovi.platform.web.ApiException;
import com.ivorovi.students.internal.AdmissionService.AdmissionStatus;
import com.ivorovi.students.internal.ImportField.Kind;
import com.ivorovi.students.internal.StudentService.Gender;
import com.ivorovi.students.internal.StudentService.OptionKind;
import com.ivorovi.students.internal.StudentService.Status;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Row-level rules of the student import: converts the mapped cells of one row into typed values, resolves academic
 * references inside the import's institution (tenant-isolated by RLS, scope-checked by path), validates them with the
 * same rules as the student services, and classifies duplicates within the file and against existing students.
 * Issue messages never contain personal values (only column names, codes and identifiers).
 */
@Component
public class StudentImportEngine {

    // ================================================================================================ types

    public enum Severity { ERROR, WARNING }

    public enum Category { REQUIRED, FORMAT, INVALID_VALUE, REFERENCE, PERMISSION, DUPLICATE, CONFLICT, POLICY }

    /** One problem of a row. {@code column} is the header of the source column (null for row-level issues). */
    record Issue(Severity severity, Category category, String code, String field, String column, Integer columnNo, String message, String hint) {}

    public enum RowStatus { VALID, WARNING, INVALID, DUPLICATE, NEEDS_DECISION, CONFLICT }

    public enum MatchCategory { EXACT_DUPLICATE, POTENTIAL_DUPLICATE, CONFLICTING_IDENTITY, EXISTING_NEW_ENROLMENT, CONFLICTING_ACADEMIC, IN_FILE_DUPLICATE, IN_FILE_POTENTIAL }

    public enum Decision { CREATE_NEW, ADD_TO_EXISTING, SKIP }

    record Guardian(String name, String relationship, String mobile, String email, String address, boolean emergency) {}

    /** What processing the row would create. */
    public record Plan(boolean student, boolean admission, boolean enrolment, int guardians, boolean activate) {
        static final Plan NONE = new Plan(false, false, false, 0, false);
    }

    /** Typed values of one row (nulls for empty cells). */
    static final class Parsed {
        final int rowNo;
        final List<Issue> issues = new ArrayList<>();
        final Map<ImportField, String> raw = new EnumMap<>(ImportField.class);
        String first, middle, last, nationality, personalEmail, mobile, alternatePhone, currentAddress, permanentAddress;
        LocalDate dateOfBirth;
        Gender gender;
        String studentNo, registrationNo, rollNo, previousRef, institutionalEmail;
        Integer admissionYear;
        boolean hasAdmission, hasEnrolment, hasPersonal;
        String admissionNo, admissionType, admissionCategory, previousInstitution;
        LocalDate admissionDate, enrolmentDate;
        AdmissionStatus admissionStatus;
        Refs.Year entryYear, year;
        Refs.Program program;
        Refs.Course course;
        Refs.Batch batch;
        Integer entryTerm, termNumber;
        Status targetStatus;
        final List<Guardian> guardians = new ArrayList<>();

        Parsed(int rowNo) { this.rowNo = rowNo; }

        boolean hasErrors() { return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR); }

        String displayName() { return StudentService.fullName(first, middle, last); }

        String nameKey() { return first == null ? null : norm(first) + "|" + norm(last == null ? "" : last); }

        /** Canonical text of the mapped values (exact in-file duplicates have equal fingerprints). */
        String fingerprint() {
            StringBuilder sb = new StringBuilder();
            raw.forEach((f, v) -> sb.append(f.key()).append('=').append(v == null ? "" : v.trim().toLowerCase(Locale.ROOT)).append('\u0001'));
            return sb.toString();
        }

        Plan plan(boolean createStudent) {
            boolean activate = targetStatus == Status.ACTIVE;
            return new Plan(createStudent, hasAdmission, hasEnrolment, guardians.size(), activate);
        }
    }

    /** Result of duplicate classification. */
    record Classification(RowStatus status, MatchCategory category, UUID studentId, String studentNo, Integer matchRowNo, String reason, Plan plan,
                          List<Issue> extraIssues) {}

    // ================================================================================================ reference data

    /** Academic structure of one institution, loaded once per validation or processing run. */
    static final class Refs {
        record Unit(UUID id, String type, String code, String name, String path) {}
        record Program(UUID id, String code, String name, String ownerPath, String status) {}
        record Course(UUID id, String code, String name, UUID programId, UUID departmentId, String status, int terms) {}
        record Batch(UUID id, String code, String name, UUID courseId, UUID yearId, String status) {}
        record Year(UUID id, String code, String name, String ownerPath, String status) {}
        record Option(String code, String label) {}

        Unit institution;
        final List<Unit> departments = new ArrayList<>();
        final List<Program> programs = new ArrayList<>();
        final List<Course> courses = new ArrayList<>();
        final List<Batch> batches = new ArrayList<>();
        final List<Year> years = new ArrayList<>();
        final Map<OptionKind, List<Option>> options = new EnumMap<>(OptionKind.class);
        StudentNumbering.Mode numberingMode;
        Pattern studentNoPattern;
        String studentNoMask;

        Optional<Program> programById(UUID id) { return programs.stream().filter(p -> p.id().equals(id)).findFirst(); }

        Optional<Course> courseById(UUID id) { return courses.stream().filter(c -> c.id().equals(id)).findFirst(); }

        Optional<Year> yearById(UUID id) { return years.stream().filter(y -> y.id().equals(id)).findFirst(); }

        Optional<Unit> departmentById(UUID id) { return departments.stream().filter(d -> d.id().equals(id)).findFirst(); }
    }

    /** Per-institution matching rules and required-field policy. */
    record Settings(boolean matchPersonalEmail, boolean matchInstitutionalEmail, boolean matchNameDob, boolean matchNameMobile, boolean requireDateOfBirth) {
        static final Settings DEFAULT = new Settings(true, true, true, true, false);
    }

    private final JdbcClient jdbc;
    private final StudentAccess access;
    private final StudentNumbering numbering;
    private final StudentOptions options;

    StudentImportEngine(JdbcClient jdbc, StudentAccess access, StudentNumbering numbering, StudentOptions options) {
        this.jdbc = jdbc;
        this.access = access;
        this.numbering = numbering;
        this.options = options;
    }

    Settings settings(UUID institutionId) {
        return jdbc.sql("SELECT match_personal_email, match_institutional_email, match_name_dob, match_name_mobile, require_date_of_birth "
                        + "FROM student_import_settings WHERE institution_id = ?").param(institutionId)
                .query((rs, n) -> new Settings(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5)))
                .optional().orElse(Settings.DEFAULT);
    }

    Refs loadRefs(UUID institutionId) {
        Refs r = new Refs();
        r.institution = jdbc.sql("SELECT id, unit_type, code, name, path::text FROM org_unit WHERE id = ?").param(institutionId)
                .query((rs, n) -> new Refs.Unit(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .optional().orElseThrow(() -> ApiException.notFound("Institution"));
        String path = r.institution.path();
        r.departments.addAll(jdbc.sql("SELECT id, unit_type, code, name, path::text FROM org_unit WHERE path <@ CAST(? AS ltree) AND unit_type = 'DEPARTMENT' AND status = 'ACTIVE'")
                .param(path).query((rs, n) -> new Refs.Unit(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).list());
        r.programs.addAll(jdbc.sql("SELECT p.id, p.code, p.name, ou.path::text, p.status FROM program p JOIN org_unit ou ON ou.id = p.owner_org_unit_id WHERE ou.path <@ CAST(? AS ltree)")
                .param(path).query((rs, n) -> new Refs.Program(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).list());
        r.courses.addAll(jdbc.sql("""
                SELECT c.id, c.code, c.name, c.program_id, c.department_id, c.status, coalesce(c.total_terms, p.total_terms)
                FROM course c JOIN program p ON p.id = c.program_id JOIN org_unit ou ON ou.id = p.owner_org_unit_id WHERE ou.path <@ CAST(? AS ltree)""")
                .param(path).query((rs, n) -> new Refs.Course(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getInt(7))).list());
        r.batches.addAll(jdbc.sql("""
                SELECT b.id, b.code, b.name, b.course_id, b.academic_year_id, b.status FROM batch b JOIN course c ON c.id = b.course_id
                JOIN program p ON p.id = c.program_id JOIN org_unit ou ON ou.id = p.owner_org_unit_id WHERE ou.path <@ CAST(? AS ltree)""")
                .param(path).query((rs, n) -> new Refs.Batch(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getString(6))).list());
        r.years.addAll(jdbc.sql("SELECT y.id, y.code, y.name, ou.path::text, y.status FROM academic_year y LEFT JOIN org_unit ou ON ou.id = y.owner_org_unit_id")
                .query((rs, n) -> new Refs.Year(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).list());
        for (StudentService.Option o : options.all()) {
            if (o.active()) r.options.computeIfAbsent(o.kind(), k -> new ArrayList<>()).add(new Refs.Option(o.code(), o.label()));
        }
        Optional<StudentNumbering.Rule> rule = numbering.effective(institutionId);
        r.numberingMode = rule.map(StudentNumbering.Rule::mode).orElse(StudentNumbering.Mode.MANUAL);
        if (r.numberingMode == StudentNumbering.Mode.MANUAL) {
            String mask = rule.map(StudentNumbering.Rule::manualPattern).orElse(null);
            r.studentNoMask = mask;
            r.studentNoPattern = mask == null ? StudentNumbering.DEFAULT_NUMBER : StudentNumbering.compileMask(mask);
        }
        return r;
    }

    // ================================================================================================ parsing and validation

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 -]{5,18}[0-9]$");
    private static final Pattern ADMISSION_NO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$");
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT));
    private static final Map<String, String> RELATIONSHIPS = Map.ofEntries(Map.entry("mother", "MOTHER"), Map.entry("father", "FATHER"),
            Map.entry("guardian", "GUARDIAN"), Map.entry("legalguardian", "GUARDIAN"), Map.entry("parent", "GUARDIAN"), Map.entry("grandparent", "GRANDPARENT"),
            Map.entry("grandmother", "GRANDPARENT"), Map.entry("grandfather", "GRANDPARENT"), Map.entry("sibling", "SIBLING"), Map.entry("brother", "SIBLING"),
            Map.entry("sister", "SIBLING"), Map.entry("spouse", "SPOUSE"), Map.entry("husband", "SPOUSE"), Map.entry("wife", "SPOUSE"),
            Map.entry("relative", "RELATIVE"), Map.entry("uncle", "RELATIVE"), Map.entry("aunt", "RELATIVE"), Map.entry("other", "OTHER"));

    /** Context of one validation run: who validates, what is mapped. */
    record Context(UUID institutionId, Refs refs, Settings settings, Map<Integer, ImportField> mapping, List<String> headers, boolean personalAllowed) {
        Map<ImportField, Integer> columns() {
            Map<ImportField, Integer> c = new EnumMap<>(ImportField.class);
            mapping.forEach((col, f) -> c.put(f, col));
            return c;
        }
    }

    Parsed parse(Context ctx, int rowNo, List<String> cells, List<Integer> formulaColumns) {
        Parsed p = new Parsed(rowNo);
        Map<ImportField, Integer> cols = ctx.columns();
        for (Map.Entry<Integer, ImportField> e : ctx.mapping().entrySet()) {
            int col = e.getKey();
            String v = col < cells.size() ? cells.get(col) : null;
            v = v == null ? null : v.strip();
            if (formulaColumns.contains(col)) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "formula_not_allowed", e.getValue(),
                        "The cell contains a formula. Formulas are not evaluated.", "Replace the formula with its value (copy, then paste values only)."));
                v = null;
            }
            if (v != null && v.isEmpty()) v = null;
            if (v != null && v.length() > e.getValue().maxLength) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "too_long", e.getValue(), "Longer than " + e.getValue().maxLength + " characters.",
                        "Shorten the value."));
                v = null;
            }
            p.raw.put(e.getValue(), v);
        }
        Function<ImportField, String> get = p.raw::get;

        // ---- personal information
        p.first = get.apply(ImportField.FIRST_NAME);
        if (p.first == null && !p.issues.stream().anyMatch(i -> ImportField.FIRST_NAME.key().equals(i.field()))) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", ImportField.FIRST_NAME, "First name is required.", "Enter the student's first name."));
        }
        p.middle = get.apply(ImportField.MIDDLE_NAME);
        p.last = get.apply(ImportField.LAST_NAME);
        if (p.last == null && cols.containsKey(ImportField.LAST_NAME)) {
            p.issues.add(issue(ctx, Severity.WARNING, Category.REQUIRED, "last_name_missing", ImportField.LAST_NAME, "Last name is empty.",
                    "Enter the last name unless the student has a single name."));
        }
        for (ImportField f : List.of(ImportField.FIRST_NAME, ImportField.MIDDLE_NAME, ImportField.LAST_NAME)) {
            String v = get.apply(f);
            if (v != null && (v.chars().anyMatch(Character::isDigit) || v.contains("@"))) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_name", f, "Names cannot contain digits or @.", "Check that the column holds a name."));
            }
        }
        p.dateOfBirth = date(ctx, p, ImportField.DATE_OF_BIRTH);
        if (p.dateOfBirth != null && (p.dateOfBirth.isAfter(LocalDate.now().minusYears(10)) || p.dateOfBirth.isBefore(LocalDate.of(1900, 1, 1)))) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_date_of_birth", ImportField.DATE_OF_BIRTH,
                    "The date of birth must be at least 10 years ago and after 1900.", "Check the date and its format (DD/MM/YYYY)."));
            p.dateOfBirth = null;
        }
        if (ctx.settings().requireDateOfBirth() && p.dateOfBirth == null && !hasIssue(p, ImportField.DATE_OF_BIRTH)) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", ImportField.DATE_OF_BIRTH, "Date of birth is required by the institution's import policy.",
                    "Enter the date of birth as YYYY-MM-DD or DD/MM/YYYY."));
        }
        String gender = get.apply(ImportField.GENDER);
        if (gender != null) {
            p.gender = switch (gender.toLowerCase(Locale.ROOT)) {
                case "f", "female", "woman", "girl" -> Gender.FEMALE;
                case "m", "male", "man", "boy" -> Gender.MALE;
                case "o", "other", "transgender", "non-binary", "nonbinary" -> Gender.OTHER;
                case "u", "undisclosed", "prefer not to say", "not specified" -> Gender.UNDISCLOSED;
                default -> null;
            };
            if (p.gender == null) p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_gender", ImportField.GENDER, "Unknown gender value.",
                    "Use Female, Male, Other or Undisclosed."));
        }
        p.nationality = get.apply(ImportField.NATIONALITY);
        p.personalEmail = email(ctx, p, ImportField.PERSONAL_EMAIL);
        p.mobile = phone(ctx, p, ImportField.MOBILE);
        p.alternatePhone = phone(ctx, p, ImportField.ALTERNATE_PHONE);
        p.currentAddress = get.apply(ImportField.CURRENT_ADDRESS);
        p.permanentAddress = get.apply(ImportField.PERMANENT_ADDRESS);
        p.hasPersonal = p.dateOfBirth != null || p.gender != null || p.nationality != null || p.personalEmail != null || p.mobile != null
                || p.alternatePhone != null || p.currentAddress != null || p.permanentAddress != null;

        // ---- identification
        p.studentNo = get.apply(ImportField.STUDENT_NO);
        if (p.studentNo != null && ctx.refs().numberingMode == StudentNumbering.Mode.MANUAL && !ctx.refs().studentNoPattern.matcher(p.studentNo).matches()) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_student_no", ImportField.STUDENT_NO, "The student ID does not match the institution's format"
                    + (ctx.refs().studentNoMask == null ? "." : " (" + ctx.refs().studentNoMask + ")."), "Correct the student ID or the numbering rule in Student settings."));
        }
        p.registrationNo = get.apply(ImportField.REGISTRATION_NO);
        if (p.registrationNo != null) {
            try {
                numbering.validateRegistration(ctx.institutionId(), p.registrationNo);
            } catch (ApiException e) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_registration_no", ImportField.REGISTRATION_NO, e.getMessage(),
                        "Use the registration format configured in Student settings."));
            }
        }
        p.rollNo = get.apply(ImportField.ROLL_NO);
        if (p.rollNo != null && !StudentNumbering.DEFAULT_NUMBER.matcher(p.rollNo).matches() && !p.rollNo.matches("^[0-9]{1,3}$")) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_roll_no", ImportField.ROLL_NO, "Invalid roll number.", "Use letters, digits and / - . _"));
        }
        p.previousRef = get.apply(ImportField.PREVIOUS_INSTITUTION_REF);
        p.institutionalEmail = email(ctx, p, ImportField.INSTITUTIONAL_EMAIL);
        p.admissionYear = integer(ctx, p, ImportField.ADMISSION_YEAR, 1990, 2200);
        String inst = get.apply(ImportField.INSTITUTION);
        if (inst != null && !matches(inst, ctx.refs().institution.code(), ctx.refs().institution.name())) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "wrong_institution", ImportField.INSTITUTION,
                    "The row belongs to another institution than the one chosen for this import (" + ctx.refs().institution.name() + ").",
                    "Upload one file per university or college, or remove the row."));
        }

        // ---- academic references
        parseAcademics(ctx, p);
        // ---- guardians
        for (int n = 1; n <= 2; n++) parseGuardian(ctx, p, n);
        // ---- target status
        String status = get.apply(ImportField.STATUS);
        if (status != null) {
            String s = status.toUpperCase(Locale.ROOT).replace(' ', '_');
            p.targetStatus = switch (s) {
                case "PROSPECTIVE" -> Status.PROSPECTIVE;
                case "ADMITTED" -> Status.ADMITTED;
                case "ENROLLED", "ENROLED" -> Status.ENROLLED;
                case "ACTIVE" -> Status.ACTIVE;
                default -> null;
            };
            if (p.targetStatus == null) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "unsupported_status", ImportField.STATUS,
                        "Imports can set Prospective, Admitted, Enrolled or Active.", "Record suspensions, withdrawals or graduations on the student profile after the import."));
            } else {
                checkStatus(ctx, p);
            }
        }
        // ---- permission for personal data
        if (!ctx.personalAllowed() && (p.hasPersonal || !p.guardians.isEmpty() || p.admissionCategory != null)) {
            p.issues.add(new Issue(Severity.ERROR, Category.PERMISSION, "personal_data_not_permitted", null, null, null,
                    "You need 'View student personal details' to import personal or guardian information.", "Ask an administrator, or remove those columns."));
        }
        return p;
    }

    private void parseAcademics(Context ctx, Parsed p) {
        Refs refs = ctx.refs();
        Map<ImportField, String> raw = p.raw;
        p.admissionNo = raw.get(ImportField.ADMISSION_NO);
        boolean admissionGiven = ImportField.ADMISSION_FIELDS.stream().anyMatch(f -> raw.get(f) != null);
        boolean enrolmentGiven = ImportField.ENROLMENT_FIELDS.stream().anyMatch(f -> raw.get(f) != null);
        p.hasAdmission = admissionGiven || enrolmentGiven;
        p.hasEnrolment = raw.get(ImportField.BATCH) != null;
        if (!p.hasAdmission) return;

        // program → course → batch (codes are unique only within their parent)
        if (raw.get(ImportField.PROGRAM) != null) {
            p.program = resolve(ctx, p, ImportField.PROGRAM, refs.programs, pr -> List.of(pr.code(), pr.name()), "program", null);
        }
        if (raw.get(ImportField.COURSE) != null) {
            List<Refs.Course> pool = p.program == null ? refs.courses : refs.courses.stream().filter(c -> c.programId().equals(p.program.id())).toList();
            p.course = resolve(ctx, p, ImportField.COURSE, pool, c -> List.of(c.code(), c.name()), "course", p.program == null ? "Add the program column to disambiguate." : null);
        }
        if (raw.get(ImportField.BATCH) != null) {
            List<Refs.Batch> pool = p.course == null ? refs.batches : refs.batches.stream().filter(b -> b.courseId().equals(p.course.id())).toList();
            p.batch = resolve(ctx, p, ImportField.BATCH, pool, b -> List.of(b.code(), b.name()), "batch",
                    p.course == null ? "Add the course column to disambiguate." : null);
        } else if (enrolmentGiven) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", ImportField.BATCH, "Batch is required with enrolment details.", "Enter the batch code."));
        }
        if (p.batch != null) {
            Refs.Course bc = refs.courseById(p.batch.courseId()).orElse(null);
            if (p.course != null && bc != null && !p.course.id().equals(bc.id())) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "batch_course_mismatch", ImportField.BATCH, "The batch belongs to another course than the row's course.",
                        "Use a batch of course " + p.course.code() + "."));
            } else if (p.course == null) {
                p.course = bc;
            }
        }
        if (p.course != null) {
            Refs.Program cp = refs.programById(p.course.programId()).orElse(null);
            if (p.program != null && cp != null && !p.program.id().equals(cp.id())) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "course_not_in_program", ImportField.COURSE, "The course is not part of the row's program.",
                        "Check the program and course codes."));
            } else if (p.program == null) {
                p.program = cp;
            }
            if (!"ACTIVE".equals(p.course.status()) || (p.program != null && !"ACTIVE".equals(p.program.status()))) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "course_inactive", ImportField.COURSE, "The course or its program is not active.",
                        "Activate it in Academics or use an active course."));
            }
            if (!access.can(StudentAccess.MANAGE, p.course.departmentId())) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.PERMISSION, "department_not_permitted", ImportField.COURSE,
                        "You cannot manage students of this course's department.", "Ask an administrator of that department to import these rows."));
            }
        } else if (!hasIssue(p, ImportField.COURSE) && !hasIssue(p, ImportField.BATCH) && !hasIssue(p, ImportField.PROGRAM)) {
            p.issues.add(new Issue(Severity.ERROR, Category.REQUIRED, "course_required", ImportField.COURSE.key(), column(ctx, ImportField.COURSE),
                    columnNo(ctx, ImportField.COURSE), "Admission and enrolment details need the course (or a batch).", "Add the course or batch code."));
        }
        if (p.batch != null && !Set.of("ACTIVE", "PLANNED").contains(p.batch.status())) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "batch_not_active", ImportField.BATCH, "The batch is not open for enrolment (" + p.batch.status().toLowerCase(Locale.ROOT) + ").",
                    "Use an active or planned batch."));
        }
        String dept = raw.get(ImportField.DEPARTMENT);
        if (dept != null) {
            Refs.Unit d = resolve(ctx, p, ImportField.DEPARTMENT, refs.departments, u -> List.of(u.code(), u.name()), "department", null);
            if (d != null && p.course != null && !d.id().equals(p.course.departmentId())) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "department_mismatch", ImportField.DEPARTMENT, "The course belongs to another department.",
                        "Use the course's department (" + refs.departmentById(p.course.departmentId()).map(Refs.Unit::code).orElse("?") + ") or leave the column empty."));
            }
        }

        // admission details
        if (p.admissionNo == null) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", ImportField.ADMISSION_NO, "Admission number is required with admission or enrolment details.",
                    "Enter the admission number."));
        } else if (!ADMISSION_NO.matcher(p.admissionNo).matches()) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_admission_no", ImportField.ADMISSION_NO, "Invalid admission number.",
                    "Use up to 40 letters, digits and . _ / -"));
        }
        p.admissionDate = date(ctx, p, ImportField.ADMISSION_DATE);
        if (p.admissionDate == null && !hasIssue(p, ImportField.ADMISSION_DATE)) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", ImportField.ADMISSION_DATE, "Admission date is required.", "Enter the date as YYYY-MM-DD or DD/MM/YYYY."));
        } else if (p.admissionDate != null && (p.admissionDate.isAfter(LocalDate.now().plusYears(1)) || p.admissionDate.isBefore(LocalDate.of(1990, 1, 1)))) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_admission_date", ImportField.ADMISSION_DATE, "The admission date is not realistic.",
                    "Use a date between 1990 and next year."));
        }
        String type = raw.get(ImportField.ADMISSION_TYPE);
        p.admissionType = type == null ? "REGULAR" : option(ctx, p, ImportField.ADMISSION_TYPE, OptionKind.ADMISSION_TYPE, type);
        String category = raw.get(ImportField.ADMISSION_CATEGORY);
        if (category != null) p.admissionCategory = option(ctx, p, ImportField.ADMISSION_CATEGORY, OptionKind.ADMISSION_CATEGORY, category);
        String ast = raw.get(ImportField.ADMISSION_STATUS);
        p.admissionStatus = AdmissionStatus.CONFIRMED;
        if (ast != null) {
            switch (ast.toLowerCase(Locale.ROOT)) {
                case "confirmed", "confirm", "c" -> p.admissionStatus = AdmissionStatus.CONFIRMED;
                case "provisional", "p" -> p.admissionStatus = AdmissionStatus.PROVISIONAL;
                default -> p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_admission_status", ImportField.ADMISSION_STATUS,
                        "Unknown admission status.", "Use Confirmed or Provisional."));
            }
        }
        if (p.hasEnrolment && p.admissionStatus == AdmissionStatus.PROVISIONAL) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.CONFLICT, "admission_not_confirmed", ImportField.ADMISSION_STATUS,
                    "A student can be enrolled only after the admission is confirmed.", "Set the admission status to Confirmed or leave the batch empty."));
        }
        p.entryTerm = integer(ctx, p, ImportField.ENTRY_TERM, 1, 40);
        if (p.entryTerm == null) p.entryTerm = 1;
        if (p.course != null && p.entryTerm > p.course.terms()) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_entry_term", ImportField.ENTRY_TERM,
                    "The course has " + p.course.terms() + " semesters.", "Use a semester from 1 to " + p.course.terms() + "."));
        }
        if ("REGULAR".equals(p.admissionType) && p.entryTerm != 1) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_entry_term", ImportField.ENTRY_TERM, "A regular admission starts in semester 1.",
                    "Use Lateral entry or Transfer as the admission type, or semester 1."));
        }
        p.previousInstitution = raw.get(ImportField.PREVIOUS_INSTITUTION_NAME);
        if ("TRANSFER".equals(p.admissionType) && p.previousInstitution == null) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", ImportField.PREVIOUS_INSTITUTION_NAME, "A transfer admission needs the previous institution.",
                    "Enter the previous institution."));
        }
        // academic years
        if (raw.get(ImportField.ENTRY_ACADEMIC_YEAR) != null) {
            p.entryYear = resolve(ctx, p, ImportField.ENTRY_ACADEMIC_YEAR, refs.years, y -> List.of(y.code(), y.name()), "academic year", null);
        } else if (p.batch != null) {
            p.entryYear = refs.yearById(p.batch.yearId()).orElse(null);
        } else if (!hasIssue(p, ImportField.BATCH)) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", ImportField.ENTRY_ACADEMIC_YEAR, "Entry academic year is required when no batch is given.",
                    "Enter the academic year code (for example 2026-27)."));
        }
        checkYear(ctx, p, p.entryYear, ImportField.ENTRY_ACADEMIC_YEAR);
        if (p.hasEnrolment) {
            if (raw.get(ImportField.ACADEMIC_YEAR) != null) {
                p.year = resolve(ctx, p, ImportField.ACADEMIC_YEAR, refs.years, y -> List.of(y.code(), y.name()), "academic year", null);
            } else if (p.batch != null) {
                p.year = refs.yearById(p.batch.yearId()).orElse(null);
            }
            checkYear(ctx, p, p.year, ImportField.ACADEMIC_YEAR);
            p.termNumber = integer(ctx, p, ImportField.TERM_NUMBER, 1, 40);
            if (p.termNumber == null) p.termNumber = p.entryTerm;
            if (p.course != null && p.termNumber > p.course.terms()) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_term_number", ImportField.TERM_NUMBER,
                        "The course has " + p.course.terms() + " semesters.", "Use a semester from 1 to " + p.course.terms() + "."));
            }
            p.enrolmentDate = date(ctx, p, ImportField.ENROLMENT_DATE);
            if (p.enrolmentDate == null && p.admissionDate != null) p.enrolmentDate = p.admissionDate.isAfter(LocalDate.now()) ? LocalDate.now() : p.admissionDate;
            if (p.enrolmentDate != null && p.admissionDate != null && p.enrolmentDate.isBefore(p.admissionDate)) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.CONFLICT, "enrolment_before_admission", ImportField.ENROLMENT_DATE,
                        "The enrolment date is before the admission date.", "Use an enrolment date on or after the admission date."));
            } else if (p.enrolmentDate != null && p.enrolmentDate.isAfter(LocalDate.now().plusYears(1))) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_enrolment_date", ImportField.ENROLMENT_DATE,
                        "The enrolment date is too far in the future.", "Use a date within the next year."));
            }
        }
    }

    private void checkYear(Context ctx, Parsed p, Refs.Year y, ImportField field) {
        if (y == null || p.program == null) return;
        if (y.ownerPath() != null && !StudentAccess.within(p.program.ownerPath(), y.ownerPath())) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "year_not_applicable", field, y.name() + " does not apply to " + p.program.name() + ".",
                    "Use an academic year of the institution or the organization."));
        } else if ("COMPLETED".equals(y.status())) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "year_completed", field, y.name() + " is completed.", "Use a current or planned academic year."));
        }
    }

    private void parseGuardian(Context ctx, Parsed p, int n) {
        ImportField name = n == 1 ? ImportField.GUARDIAN1_NAME : ImportField.GUARDIAN2_NAME;
        ImportField rel = n == 1 ? ImportField.GUARDIAN1_RELATIONSHIP : ImportField.GUARDIAN2_RELATIONSHIP;
        ImportField mobile = n == 1 ? ImportField.GUARDIAN1_MOBILE : ImportField.GUARDIAN2_MOBILE;
        ImportField email = n == 1 ? ImportField.GUARDIAN1_EMAIL : ImportField.GUARDIAN2_EMAIL;
        ImportField address = n == 1 ? ImportField.GUARDIAN1_ADDRESS : ImportField.GUARDIAN2_ADDRESS;
        ImportField emergency = n == 1 ? ImportField.GUARDIAN1_EMERGENCY : ImportField.GUARDIAN2_EMERGENCY;
        boolean any = List.of(name, rel, mobile, email, address, emergency).stream().anyMatch(f -> p.raw.get(f) != null);
        if (!any) return;
        String nm = p.raw.get(name);
        if (nm == null) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", name, "Guardian " + n + " name is required with other guardian details.", "Enter the guardian's name."));
        }
        String r = p.raw.get(rel);
        String relationship = null;
        if (r == null) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "required", rel, "Guardian " + n + " relationship is required.",
                    "Use Mother, Father, Guardian, Grandparent, Sibling, Spouse, Relative or Other."));
        } else {
            relationship = RELATIONSHIPS.get(r.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""));
            if (relationship == null) {
                p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_relationship", rel, "Unknown relationship.",
                        "Use Mother, Father, Guardian, Grandparent, Sibling, Spouse, Relative or Other."));
            }
        }
        String m = phone(ctx, p, mobile);
        String e = email(ctx, p, email);
        if (p.raw.get(mobile) == null && p.raw.get(email) == null) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REQUIRED, "guardian_contact_required", nm == null ? name : mobile,
                    "Guardian " + n + " needs a mobile number or an email address.", "Add the guardian's mobile number or email."));
        }
        String em = p.raw.get(emergency);
        boolean isEmergency = em != null && Set.of("yes", "y", "true", "1").contains(em.toLowerCase(Locale.ROOT));
        if (em != null && !isEmergency && !Set.of("no", "n", "false", "0").contains(em.toLowerCase(Locale.ROOT))) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "invalid_boolean", emergency, "Use Yes or No.", "Enter Yes or No."));
        }
        if (nm != null && relationship != null) p.guardians.add(new Guardian(nm, relationship, m, e, p.raw.get(address), isEmergency));
    }

    private void checkStatus(Context ctx, Parsed p) {
        boolean confirmed = p.hasAdmission && p.admissionStatus == AdmissionStatus.CONFIRMED;
        String problem = switch (p.targetStatus) {
            case PROSPECTIVE -> confirmed ? "A prospective student cannot have a confirmed admission." : null;
            case ADMITTED -> !confirmed ? "An admitted student needs a confirmed admission." : p.hasEnrolment ? "A student with a batch becomes Enrolled or Active." : null;
            case ENROLLED, ACTIVE -> !p.hasEnrolment ? "Enrolled and active students need a batch." : null;
            default -> null;
        };
        if (problem != null) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.CONFLICT, "status_inconsistent", ImportField.STATUS, problem,
                    "Leave the status empty to derive it from the admission and enrolment details."));
        }
    }

    // ---- value helpers

    private <T> T resolve(Context ctx, Parsed p, ImportField field, List<T> pool, Function<T, List<String>> names, String noun, String ambiguityHint) {
        String v = p.raw.get(field);
        if (v == null) return null;
        List<T> exact = pool.stream().filter(t -> names.apply(t).stream().anyMatch(n -> n != null && n.equalsIgnoreCase(v))).toList();
        if (exact.size() == 1) return exact.getFirst();
        if (exact.isEmpty()) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "unknown_" + field.key(), field, "No " + noun + " with this code or name exists in "
                    + ctx.refs().institution.name() + ".", "Check the " + noun + " code (see the Reference values sheet of the template)."));
        } else {
            p.issues.add(issue(ctx, Severity.ERROR, Category.REFERENCE, "ambiguous_" + field.key(), field, "Several " + noun + "s match this value.",
                    ambiguityHint == null ? "Use the unique code instead of the name." : ambiguityHint));
        }
        return null;
    }

    private String option(Context ctx, Parsed p, ImportField field, OptionKind kind, String v) {
        String key = v.trim();
        for (Refs.Option o : ctx.refs().options.getOrDefault(kind, List.of())) {
            if (o.code().equalsIgnoreCase(key.replace(' ', '_')) || o.label().equalsIgnoreCase(key)
                    || o.label().toLowerCase(Locale.ROOT).startsWith(key.toLowerCase(Locale.ROOT) + " ")) return o.code();
        }
        p.issues.add(issue(ctx, Severity.ERROR, Category.INVALID_VALUE, "unknown_" + field.key(), field, "Unknown " + field.label.toLowerCase(Locale.ROOT) + ".",
                "Use one of: " + String.join(", ", ctx.refs().options.getOrDefault(kind, List.of()).stream().map(Refs.Option::label).toList()) + "."));
        return null;
    }

    private LocalDate date(Context ctx, Parsed p, ImportField field) {
        String v = p.raw.get(field);
        if (v == null) return null;
        String s = v.trim();
        if (s.length() > 10 && s.matches("^\\d{4}-\\d{2}-\\d{2}[T ].*")) s = s.substring(0, 10);
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) { }
        }
        p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_date", field, "Not a valid date.", "Use YYYY-MM-DD or DD/MM/YYYY (day first)."));
        return null;
    }

    private String email(Context ctx, Parsed p, ImportField field) {
        String v = p.raw.get(field);
        if (v == null) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(s).matches()) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_email", field, "Not a valid email address.", "Use the form name@example.com."));
            return null;
        }
        return s;
    }

    private String phone(Context ctx, Parsed p, ImportField field) {
        String v = p.raw.get(field);
        if (v == null) return null;
        String s = v.trim();
        if (!PHONE.matcher(s).matches()) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_phone", field, "Not a valid phone number.",
                    "Use 7–20 digits; spaces, - and a leading + are allowed. Format the column as text in the spreadsheet."));
            return null;
        }
        return s;
    }

    private Integer integer(Context ctx, Parsed p, ImportField field, int min, int max) {
        String v = p.raw.get(field);
        if (v == null) return null;
        try {
            int n = Integer.parseInt(v.trim().replaceFirst("\\.0+$", ""));
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            p.issues.add(issue(ctx, Severity.ERROR, Category.FORMAT, "invalid_number", field, "Must be a whole number from " + min + " to " + max + ".", "Enter a number."));
            return null;
        }
    }

    private static boolean hasIssue(Parsed p, ImportField f) {
        return p.issues.stream().anyMatch(i -> f.key().equals(i.field()));
    }

    private static boolean matches(String v, String... names) {
        for (String n : names) if (n != null && n.equalsIgnoreCase(v.trim())) return true;
        return false;
    }

    static String column(Context ctx, ImportField f) {
        Integer col = ctx.columns().get(f);
        return col == null ? null : col < ctx.headers().size() ? ctx.headers().get(col) : "Column " + (col + 1);
    }

    static Integer columnNo(Context ctx, ImportField f) {
        Integer col = ctx.columns().get(f);
        return col == null ? null : col + 1;
    }

    static Issue issue(Context ctx, Severity s, Category c, String code, ImportField f, String message, String hint) {
        return new Issue(s, c, code, f.key(), column(ctx, f), columnNo(ctx, f), message, hint);
    }

    static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    static String phoneKey(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("[^0-9]", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    // ================================================================================================ existing students

    /** A student that an import row could match. Personal values are decrypted only for matching, never returned. */
    static final class Existing {
        UUID id, institutionId, scopeUnitId, admissionCourseId, currentBatchId;
        String studentNo, registrationNo, institutionalEmail, nameKey, personalEmail, mobileKey, status, admissionNo, admissionStatus, displayName;
        LocalDate dateOfBirth, statusEffectiveOn;
        boolean active;
        int guardians;
    }

    /** Index of existing students used for duplicate detection (identifiers tenant-wide, details for the institution). */
    static final class Index {
        final Map<String, Existing> byStudentNo = new HashMap<>(), byRegistrationNo = new HashMap<>(), byInstitutionalEmail = new HashMap<>(),
                byAdmissionNo = new HashMap<>(), byPersonalEmail = new HashMap<>();
        final Map<String, List<Existing>> byNameDob = new HashMap<>(), byNameMobile = new HashMap<>();
        final Settings settings;

        Index(Settings settings) { this.settings = settings; }

        void add(Existing e, boolean details) {
            if (e.studentNo != null) byStudentNo.put(e.studentNo.toLowerCase(Locale.ROOT), e);
            if (e.registrationNo != null) byRegistrationNo.put(e.registrationNo.toLowerCase(Locale.ROOT), e);
            if (e.institutionalEmail != null) byInstitutionalEmail.put(e.institutionalEmail, e);
            if (!details) return;
            if (e.admissionNo != null) byAdmissionNo.put(e.admissionNo.toLowerCase(Locale.ROOT), e);
            if (e.personalEmail != null && settings.matchPersonalEmail()) byPersonalEmail.put(e.personalEmail, e);
            if (e.nameKey != null && e.dateOfBirth != null && settings.matchNameDob()) byNameDob.computeIfAbsent(e.nameKey + "|" + e.dateOfBirth, k -> new ArrayList<>()).add(e);
            if (e.nameKey != null && e.mobileKey != null && !e.mobileKey.isEmpty() && settings.matchNameMobile()) {
                byNameMobile.computeIfAbsent(e.nameKey + "|" + e.mobileKey, k -> new ArrayList<>()).add(e);
            }
        }
    }

    private static final String EXISTING = """
            SELECT s.id, s.institution_id, s.scope_unit_id, s.student_no, s.registration_no, s.institutional_email, s.first_name, s.middle_name, s.last_name,
                   s.status_effective_on, s.date_of_birth_enc, s.personal_email_enc, s.mobile_enc, s.status, s.active,
                   a.admission_no, a.course_id AS admission_course_id, a.status AS admission_status, e.batch_id AS current_batch_id,
                   (SELECT count(*) FROM guardian g WHERE g.student_id = s.id) AS guardians
            FROM student s
            LEFT JOIN admission_record a ON a.student_id = s.id AND a.status <> 'CANCELLED'
            LEFT JOIN enrolment e ON e.student_id = s.id AND e.is_current""";

    /** Full index for a validation run. */
    Index loadIndex(UUID institutionId, Settings settings) {
        Index idx = new Index(settings);
        boolean decrypt = settings.matchPersonalEmail() || settings.matchNameDob() || settings.matchNameMobile();
        jdbc.sql(EXISTING + " WHERE s.institution_id = ?").param(institutionId).query(rs -> {
            idx.add(existing(rs, decrypt), true);
        });
        jdbc.sql("SELECT id, institution_id, scope_unit_id, student_no, registration_no, institutional_email FROM student WHERE institution_id <> ?").param(institutionId)
                .query(rs -> {
                    Existing e = new Existing();
                    e.id = rs.getObject(1, UUID.class);
                    e.institutionId = rs.getObject(2, UUID.class);
                    e.scopeUnitId = rs.getObject(3, UUID.class);
                    e.studentNo = rs.getString(4);
                    e.registrationNo = rs.getString(5);
                    e.institutionalEmail = rs.getString(6);
                    idx.add(e, false);
                });
        return idx;
    }

    /** Targeted index for one row at processing time (identifiers and name-based matches only, read inside the row's transaction). */
    Index rowIndex(UUID institutionId, Settings settings, Parsed p) {
        Index idx = new Index(settings);
        // candidate ids from the indexed, tenant-restricted lookup (V202611051100), then the students by primary key under RLS
        List<UUID> ids = jdbc.sql("SELECT student_import_candidates(?, ?, ?, ?, ?, ?, ?)")
                .params(institutionId, p.studentNo, p.registrationNo, p.institutionalEmail, p.admissionNo, p.first, p.last)
                .query(UUID.class).list();
        if (ids.isEmpty()) return idx;
        jdbc.sql(EXISTING + " WHERE s.id = ANY (?)").param(ids.toArray(UUID[]::new)).query(rs -> {
            Existing e = existing(rs, true);
            idx.add(e, institutionId.equals(e.institutionId));
        });
        return idx;
    }

    private Existing existing(java.sql.ResultSet rs, boolean decrypt) throws java.sql.SQLException {
        Existing e = new Existing();
        e.id = rs.getObject("id", UUID.class);
        e.institutionId = rs.getObject("institution_id", UUID.class);
        e.scopeUnitId = rs.getObject("scope_unit_id", UUID.class);
        e.studentNo = rs.getString("student_no");
        e.registrationNo = rs.getString("registration_no");
        e.institutionalEmail = rs.getString("institutional_email");
        String first = rs.getString("first_name"), last = rs.getString("last_name");
        e.nameKey = norm(first) + "|" + norm(last == null ? "" : last);
        e.displayName = StudentService.fullName(first, rs.getString("middle_name"), last);
        if (decrypt) {
            String dob = access.open(rs.getString("date_of_birth_enc"), "student.date_of_birth");
            e.dateOfBirth = dob == null ? null : LocalDate.parse(dob);
            e.personalEmail = access.open(rs.getString("personal_email_enc"), "student.personal_email");
            e.mobileKey = phoneKey(access.open(rs.getString("mobile_enc"), "student.mobile"));
        }
        e.status = rs.getString("status");
        e.statusEffectiveOn = rs.getObject("status_effective_on", LocalDate.class);
        e.active = rs.getBoolean("active");
        e.admissionNo = rs.getString("admission_no");
        e.admissionCourseId = rs.getObject("admission_course_id", UUID.class);
        e.admissionStatus = rs.getString("admission_status");
        e.currentBatchId = rs.getObject("current_batch_id", UUID.class);
        e.guardians = rs.getInt("guardians");
        return e;
    }

    // ================================================================================================ classification

    /** Identifiers and potential-match keys of earlier rows of the same file. */
    static final class InFile {
        record Seen(int rowNo, String fingerprint) {}

        final Map<String, Seen> ids = new HashMap<>();
        final Map<String, Integer> potential = new HashMap<>();
    }

    private static List<String> idKeys(Parsed p, Settings s) {
        List<String> keys = new ArrayList<>();
        if (p.studentNo != null) keys.add("sid:" + p.studentNo.toLowerCase(Locale.ROOT));
        if (p.registrationNo != null) keys.add("reg:" + p.registrationNo.toLowerCase(Locale.ROOT));
        if (p.admissionNo != null) keys.add("adm:" + p.admissionNo.toLowerCase(Locale.ROOT));
        if (p.institutionalEmail != null && s.matchInstitutionalEmail()) keys.add("iem:" + p.institutionalEmail);
        if (p.personalEmail != null && s.matchPersonalEmail()) keys.add("pem:" + p.personalEmail);
        return keys;
    }

    private static List<String> potentialKeys(Parsed p, Settings s) {
        List<String> keys = new ArrayList<>();
        if (p.nameKey() == null) return keys;
        if (p.dateOfBirth != null && s.matchNameDob()) keys.add(p.nameKey() + "|" + p.dateOfBirth);
        if (p.mobile != null && s.matchNameMobile()) keys.add(p.nameKey() + "|m" + phoneKey(p.mobile));
        return keys;
    }

    /**
     * Classifies one parsed row. {@code inFile} is updated (pass null at processing time). {@code expectedStudent} is
     * the student a decision referred to (processing re-check), {@code ownStudents} the students this import already
     * created (their matches are expected for in-file potential duplicates).
     */
    Classification classify(Context ctx, Parsed p, Index idx, InFile inFile, Set<UUID> ownStudents) {
        List<Issue> extra = new ArrayList<>();
        Settings s = ctx.settings();
        // ---- within the file (exact copies, same identifier with other details, possible same person)
        Classification inFileExact = null, inFileConflict = null, inFilePotential = null;
        if (inFile != null) {
            String fp = p.fingerprint();
            for (String key : idKeys(p, s)) {
                InFile.Seen seen = inFile.ids.get(key);
                if (seen == null) continue;
                if (seen.fingerprint().equals(fp)) {
                    if (inFileExact == null) inFileExact = new Classification(RowStatus.DUPLICATE, MatchCategory.IN_FILE_DUPLICATE, null, null, seen.rowNo(),
                            "Exact duplicate of row " + seen.rowNo() + "; it will be skipped.", Plan.NONE, extra);
                } else if (inFileConflict == null) {
                    inFileConflict = new Classification(RowStatus.CONFLICT, MatchCategory.IN_FILE_DUPLICATE, null, null, seen.rowNo(),
                            "Row " + seen.rowNo() + " has the same " + keyLabel(key) + " with different details.", Plan.NONE, extra);
                }
            }
            for (String key : idKeys(p, s)) inFile.ids.putIfAbsent(key, new InFile.Seen(p.rowNo, fp));
            for (String key : potentialKeys(p, s)) {
                Integer seen = inFile.potential.get(key);
                if (seen != null && inFilePotential == null) {
                    inFilePotential = new Classification(RowStatus.NEEDS_DECISION, MatchCategory.IN_FILE_POTENTIAL, null, null, seen,
                            "May be the same person as row " + seen + " (" + (key.contains("|m") ? "same name and mobile" : "same name and date of birth") + ").",
                            p.plan(true), extra);
                }
            }
            for (String key : potentialKeys(p, s)) inFile.potential.putIfAbsent(key, p.rowNo);
        }
        if (p.hasErrors()) return new Classification(RowStatus.INVALID, null, null, null, null, null, Plan.NONE, extra);
        if (inFileExact != null) return inFileExact;

        // ---- against existing students: identifiers first (a conflict with a stored student outranks one within the file)
        Map<UUID, Existing> idMatches = new LinkedHashMap<>();
        List<String> matchedBy = new ArrayList<>();
        if (p.studentNo != null) put(idMatches, matchedBy, idx.byStudentNo.get(p.studentNo.toLowerCase(Locale.ROOT)), "student ID");
        if (p.registrationNo != null) put(idMatches, matchedBy, idx.byRegistrationNo.get(p.registrationNo.toLowerCase(Locale.ROOT)), "registration number");
        if (p.admissionNo != null) put(idMatches, matchedBy, idx.byAdmissionNo.get(p.admissionNo.toLowerCase(Locale.ROOT)), "admission number");
        // institutional emails are unique per student, whether or not they are a matching rule
        if (p.institutionalEmail != null) put(idMatches, matchedBy, idx.byInstitutionalEmail.get(p.institutionalEmail), "institutional email");
        Classification db = null;
        if (idMatches.size() > 1) {
            db = new Classification(RowStatus.CONFLICT, MatchCategory.CONFLICTING_IDENTITY, null, null, null,
                    "The row's identifiers (" + String.join(", ", matchedBy) + ") belong to different existing students.", Plan.NONE, extra);
        } else if (idMatches.size() == 1) {
            db = existingMatch(ctx, p, idMatches.values().iterator().next(), String.join(" and ", matchedBy), extra);
        }
        if (db != null && db.status() == RowStatus.CONFLICT) return db;
        if (inFileConflict != null) return inFileConflict;
        if (db != null) return db;

        if (p.studentNo != null && ctx.refs().numberingMode == StudentNumbering.Mode.GENERATED) {
            return new Classification(RowStatus.INVALID, null, null, null, null, null, Plan.NONE, List.of(issue(ctx, Severity.ERROR, Category.POLICY,
                    "student_no_generated", ImportField.STUDENT_NO, "Student IDs are generated for this institution; new students cannot bring their own ID.",
                    "Leave Student ID empty for new students, or switch the numbering rule to entered IDs in Student settings while migrating.")));
        }
        if (p.studentNo == null && ctx.refs().numberingMode == StudentNumbering.Mode.MANUAL) {
            return new Classification(RowStatus.INVALID, null, null, null, null, null, Plan.NONE, List.of(issue(ctx, Severity.ERROR, Category.REQUIRED,
                    "required", ImportField.STUDENT_NO, "Student ID is required: this institution enters student IDs.", "Enter the student ID.")));
        }

        // ---- potential duplicates of stored students (configurable rules), then of earlier rows
        Map<UUID, Existing> potential = new LinkedHashMap<>();
        List<String> why = new ArrayList<>();
        if (p.personalEmail != null && s.matchPersonalEmail()) put(potential, why, idx.byPersonalEmail.get(p.personalEmail), "personal email");
        if (p.nameKey() != null && p.dateOfBirth != null && s.matchNameDob()) {
            for (Existing e : idx.byNameDob.getOrDefault(p.nameKey() + "|" + p.dateOfBirth, List.of())) put(potential, why, e, "name and date of birth");
        }
        if (p.nameKey() != null && p.mobile != null && s.matchNameMobile()) {
            for (Existing e : idx.byNameMobile.getOrDefault(p.nameKey() + "|" + phoneKey(p.mobile), List.of())) put(potential, why, e, "name and mobile");
        }
        potential.keySet().removeAll(ownStudents);
        if (!potential.isEmpty()) {
            Existing e = potential.values().iterator().next();
            String target = canSee(e) ? "student " + e.studentNo : "a student you cannot view";
            return new Classification(RowStatus.NEEDS_DECISION, MatchCategory.POTENTIAL_DUPLICATE, e.id, canSee(e) ? e.studentNo : null, null,
                    "Possible duplicate of " + target + (potential.size() > 1 ? " and " + (potential.size() - 1) + " more" : "") + " (same " + String.join(", ", new LinkedHashSet<>(why)) + ").",
                    p.plan(true), extra);
        }
        if (inFilePotential != null) return inFilePotential;
        return new Classification(p.issues.isEmpty() ? RowStatus.VALID : RowStatus.WARNING, null, null, null, null, null, p.plan(true), extra);
    }

    private Classification existingMatch(Context ctx, Parsed p, Existing e, String by, List<Issue> extra) {
        if (!e.institutionId.equals(ctx.institutionId())) {
            return new Classification(RowStatus.CONFLICT, MatchCategory.CONFLICTING_IDENTITY, null, null, null,
                    "The " + by + " is already used by a student of another institution of your organization.", Plan.NONE, extra);
        }
        if (!canSee(e)) {
            return new Classification(RowStatus.CONFLICT, MatchCategory.CONFLICTING_IDENTITY, null, null, null,
                    "The " + by + " belongs to a student outside your access.", Plan.NONE, extra);
        }
        boolean sameName = e.nameKey.equals(p.nameKey());
        boolean dobConflict = p.dateOfBirth != null && e.dateOfBirth != null && !p.dateOfBirth.equals(e.dateOfBirth);
        if (!sameName || dobConflict) {
            return new Classification(RowStatus.CONFLICT, MatchCategory.CONFLICTING_IDENTITY, e.id, e.studentNo, null,
                    "The " + by + " belongs to student " + e.studentNo + " with a different " + (!sameName ? "name" : "date of birth") + ".", Plan.NONE, extra);
        }
        if (!e.active) {
            return new Classification(RowStatus.CONFLICT, MatchCategory.CONFLICTING_ACADEMIC, e.id, e.studentNo, null,
                    "Student " + e.studentNo + " exists but the profile is deactivated.", Plan.NONE, extra);
        }
        boolean newAdmission = false, newEnrolment = false;
        String conflict = null;
        if (p.hasAdmission) {
            if (e.admissionNo != null) {
                boolean same = e.admissionNo.equalsIgnoreCase(String.valueOf(p.admissionNo)) && p.course != null && p.course.id().equals(e.admissionCourseId);
                if (!same) conflict = "Student " + e.studentNo + " already has admission " + e.admissionNo + " for another course or number.";
            } else if (!Set.of("PROSPECTIVE").contains(e.status)) {
                conflict = "Student " + e.studentNo + " is " + e.status.toLowerCase(Locale.ROOT) + " without an open admission.";
            } else {
                newAdmission = true;
            }
        }
        if (conflict == null && p.hasEnrolment && p.batch != null) {
            if (e.currentBatchId != null) {
                if (!e.currentBatchId.equals(p.batch.id())) conflict = "Student " + e.studentNo + " is currently enrolled in another batch. Use a transfer on the profile.";
            } else if (Set.of("WITHDRAWN", "CANCELLED", "GRADUATED", "ALUMNI", "SUSPENDED").contains(e.status)) {
                conflict = "Student " + e.studentNo + " is " + e.status.toLowerCase(Locale.ROOT) + " and cannot be enrolled by an import.";
            } else {
                newEnrolment = true;
            }
        }
        if (conflict != null) return new Classification(RowStatus.CONFLICT, MatchCategory.CONFLICTING_ACADEMIC, e.id, e.studentNo, null, conflict, Plan.NONE, extra);
        int guardians = e.guardians == 0 ? p.guardians.size() : 0;
        if (e.guardians > 0 && !p.guardians.isEmpty()) {
            extra.add(new Issue(Severity.WARNING, Category.DUPLICATE, "guardians_not_changed", null, null, null,
                    "The existing student already has guardians; the row's guardians will not be added.", "Edit guardians on the student profile."));
        }
        boolean activate = p.targetStatus == Status.ACTIVE && !"ACTIVE".equals(e.status);
        if (newEnrolment && p.enrolmentDate != null && e.statusEffectiveOn != null && p.enrolmentDate.isBefore(e.statusEffectiveOn)) {
            extra.add(new Issue(Severity.WARNING, Category.CONFLICT, "date_adjusted", ImportField.ENROLMENT_DATE.key(), column(ctx, ImportField.ENROLMENT_DATE),
                    columnNo(ctx, ImportField.ENROLMENT_DATE), "The enrolment will take effect on " + e.statusEffectiveOn
                    + " (the existing student's current status date); earlier dates cannot be added to an existing record.", "No action needed."));
        }
        if (!newAdmission && !newEnrolment && guardians == 0 && !activate) {
            return new Classification(RowStatus.DUPLICATE, MatchCategory.EXACT_DUPLICATE, e.id, e.studentNo, null,
                    "Already exists as student " + e.studentNo + " (same " + by + "); nothing new to import.", Plan.NONE, extra);
        }
        extra.add(new Issue(Severity.WARNING, Category.DUPLICATE, "profile_not_updated", null, null, null,
                "Existing profile details are never overwritten by an import.", "Edit the profile to change personal details."));
        List<String> adds = new ArrayList<>();
        if (newAdmission) adds.add("admission");
        if (newEnrolment) adds.add("enrolment");
        if (guardians > 0) adds.add(guardians + " guardian(s)");
        if (activate) adds.add("activation");
        return new Classification(RowStatus.NEEDS_DECISION, MatchCategory.EXISTING_NEW_ENROLMENT, e.id, e.studentNo, null,
                "Student " + e.studentNo + " already exists (same " + by + "); the row would add: " + String.join(", ", adds) + ".",
                new Plan(false, newAdmission, newEnrolment, guardians, activate), extra);
    }

    private boolean canSee(Existing e) {
        return e.scopeUnitId != null && access.can(StudentAccess.READ, e.scopeUnitId);
    }

    private static void put(Map<UUID, Existing> m, List<String> why, Existing e, String label) {
        if (e == null) return;
        m.putIfAbsent(e.id, e);
        if (!why.contains(label)) why.add(label);
    }

    private static String keyLabel(String key) {
        return switch (key.substring(0, 3)) {
            case "sid" -> "student ID";
            case "reg" -> "registration number";
            case "adm" -> "admission number";
            case "iem" -> "institutional email";
            default -> "personal email";
        };
    }

    static boolean sameValues(Object a, Object b) { return Objects.equals(a, b); }
}
