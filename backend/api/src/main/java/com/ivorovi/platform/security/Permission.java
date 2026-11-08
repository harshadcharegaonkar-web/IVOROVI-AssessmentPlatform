package com.ivorovi.platform.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * Code-defined permission catalogue (increment 1.3). Roles (system and custom) are bundles of these stable codes;
 * administrators can never invent codes. Each entry carries the metadata the role editor shows:
 * <ul>
 *   <li>{@link Domain}: functional grouping;</li>
 *   <li>{@link Level}: read, write or manage;</li>
 *   <li>{@link ScopeRule}: whether the permission works only for the whole organization or also within a unit
 *       (and its descendants);</li>
 *   <li>{@link Availability}: {@code AVAILABLE} permissions can be granted; {@code PLANNED} ones are catalogued for
 *       modules not yet delivered and cannot be granted; {@code PLATFORM} ones are reserved for the platform
 *       operator and never appear in organization roles.</li>
 * </ul>
 * New modules add entries here together with the migration that grants them to system roles.
 */
public enum Permission {
    PLATFORM_TENANT_MANAGE("platform.tenant.manage", "Manage organizations (platform)", "Create, suspend and configure tenants",
            Domain.PLATFORM, Level.MANAGE, ScopeRule.ORGANIZATION, Availability.PLATFORM),

    TENANT_SETTINGS_MANAGE("tenant.settings.manage", "Change organization settings", "Change organization settings such as terminology and time zone",
            Domain.ORGANIZATION, Level.MANAGE, ScopeRule.ORGANIZATION, Availability.AVAILABLE),
    FEATURE_READ("feature.read", "View feature settings", "View which modules and AI capabilities are switched on",
            Domain.ORGANIZATION, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    FEATURE_MANAGE("feature.manage", "Change feature settings", "Switch modules and AI capabilities on or off within their limits",
            Domain.ORGANIZATION, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),

    ORG_UNIT_READ("org_unit.read", "View the organization hierarchy", "View the organization hierarchy (universities, colleges, campuses, departments)",
            Domain.INSTITUTIONS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    ORG_UNIT_MANAGE("org_unit.manage", "Manage universities, colleges, campuses and departments", "Create, edit, move, deactivate and delete organization units",
            Domain.INSTITUTIONS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),

    USER_READ("user.read", "View users", "View user accounts, their assignments and access",
            Domain.USERS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    USER_MANAGE("user.manage", "Manage users", "Invite, modify, suspend and deactivate user accounts",
            Domain.USERS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),

    STAFF_READ("staff.read", "View staff", "View staff profiles",
            Domain.STAFF, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    STAFF_MANAGE("staff.manage", "Manage staff", "Create and edit staff profiles and their organizational assignments",
            Domain.STAFF, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),

    ROLE_READ("role.read", "View roles", "View roles, their permissions and who holds them",
            Domain.ROLES, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    ROLE_ASSIGN("role.assign", "Assign roles", "Assign and revoke roles within your own permissions and scope",
            Domain.ROLES, Level.WRITE, ScopeRule.UNIT, Availability.AVAILABLE),
    ROLE_MANAGE("role.manage", "Create and edit custom roles", "Create, edit, clone, deactivate and archive custom roles within your own permissions",
            Domain.ROLES, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),

    ACADEMICS_READ("academics.read", "View academic structure", "View programs, courses, subjects, the academic calendar, batches and subject offerings",
            Domain.ACADEMICS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    PROGRAM_MANAGE("program.manage", "Manage programs and courses", "Create, edit and deactivate programs and their courses or branches",
            Domain.ACADEMICS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    SUBJECT_MANAGE("subject.manage", "Manage subjects and curricula", "Create and edit subjects, prerequisites and which terms of a course teach them",
            Domain.ACADEMICS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    ACADEMIC_CALENDAR_MANAGE("academic_calendar.manage", "Manage the academic calendar", "Create and edit academic years and their semesters or terms",
            Domain.ACADEMICS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    BATCH_MANAGE("batch.manage", "Manage batches and subject offerings", "Create and edit batches and decide which subjects are offered to them in a term",
            Domain.ACADEMICS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    FACULTY_ASSIGN("faculty.assign", "Assign faculty", "Assign faculty to subject offerings and end assignments",
            Domain.ACADEMICS, Level.WRITE, ScopeRule.UNIT, Availability.AVAILABLE),

    AUDIT_READ("audit.read", "View the audit trail", "View and verify the tamper-evident audit trail",
            Domain.AUDIT, Level.READ, ScopeRule.ORGANIZATION, Availability.AVAILABLE),

    REPORT_READ("report.read", "View and export operational reports", "Run and export reports; every report only contains records the holder may already see",
            Domain.RESULTS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    INTEGRATION_READ("integration.read", "View integrations", "View integrations, API clients, webhooks, their status and history",
            Domain.INTEGRATIONS, Level.READ, ScopeRule.ORGANIZATION, Availability.AVAILABLE),
    INTEGRATION_MANAGE("integration.manage", "Manage integrations and API access", "Configure integrations, create, rotate and revoke API credentials, manage webhooks",
            Domain.INTEGRATIONS, Level.MANAGE, ScopeRule.ORGANIZATION, Availability.AVAILABLE),

    // Catalogued for later modules: listed in the catalogue, not grantable until the module ships.
    STUDENT_READ("student.read", "View students", "View student profiles, admissions, enrolments and history (personal details need a separate permission)",
            Domain.STUDENTS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    STUDENT_PERSONAL_READ("student.personal.read", "View student personal details", "View dates of birth, contact details, addresses, admission categories and guardians",
            Domain.STUDENTS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    STUDENT_MANAGE("student.manage", "Manage students", "Create and edit student profiles, admissions, enrolments, guardians and lifecycle status",
            Domain.STUDENTS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    STUDENT_DOCUMENT_READ("student.document.read", "View student documents", "View and download scanned, clean student documents",
            Domain.STUDENTS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    STUDENT_DOCUMENT_MANAGE("student.document.manage", "Manage student documents", "Upload, verify, reject and remove student documents",
            Domain.STUDENTS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    STUDENT_IMPORT("student.import", "Import students in bulk", "Upload CSV/XLSX files, map columns, resolve duplicates and run student imports (also needs student management at the institution)",
            Domain.STUDENTS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    STUDENT_ACCOUNT_MANAGE("student.account.manage", "Create student sign-in accounts", "Create a sign-in account for a student on demand (student role only)",
            Domain.STUDENTS, Level.WRITE, ScopeRule.UNIT, Availability.AVAILABLE),
    ADMISSION_READ("admission.read", "View admissions", "View admission cycles, applicants and screening results",
            Domain.ADMISSIONS, Level.READ, ScopeRule.UNIT, Availability.PLANNED),
    ADMISSION_MANAGE("admission.manage", "Manage admissions", "Configure admission cycles and decide on applicants",
            Domain.ADMISSIONS, Level.MANAGE, ScopeRule.UNIT, Availability.PLANNED),
    ASSESSMENT_READ("assessment.read", "View assessments", "View assessments and question banks",
            Domain.ASSESSMENTS, Level.READ, ScopeRule.UNIT, Availability.PLANNED),
    ASSESSMENT_MANAGE("assessment.manage", "Author and publish assessments", "Create, edit and publish assessments and questions",
            Domain.ASSESSMENTS, Level.MANAGE, ScopeRule.UNIT, Availability.PLANNED),
    QUESTION_READ("question.read", "View question banks", "View banks, questions and their images (never answer keys)",
            Domain.ASSESSMENTS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    QUESTION_AUTHOR("question.author", "Author questions", "Create and edit own draft questions, upload images, import, submit for review; see the answer keys of own drafts",
            Domain.ASSESSMENTS, Level.WRITE, ScopeRule.UNIT, Availability.AVAILABLE),
    QUESTION_REVIEW("question.review", "Review questions", "Review questions written by others; see answer keys of questions in review",
            Domain.ASSESSMENTS, Level.WRITE, ScopeRule.UNIT, Availability.AVAILABLE),
    QUESTION_APPROVE("question.approve", "Approve and retire questions", "Approve reviewed questions written by others, request changes, retire; see answer keys",
            Domain.ASSESSMENTS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    QUESTION_KEY_READ("question.key.read", "View answer keys", "View answer keys of approved, superseded and retired questions (every view is audited)",
            Domain.ASSESSMENTS, Level.READ, ScopeRule.UNIT, Availability.AVAILABLE),
    QUESTION_BANK_MANAGE("question.bank.manage", "Manage question banks", "Banks, approval policies, taxonomy, metadata fields and image quotas (no answer-key access)",
            Domain.ASSESSMENTS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    EXAM_READ("exam.read", "View examinations", "View examination schedules and sessions",
            Domain.EXAMINATIONS, Level.READ, ScopeRule.UNIT, Availability.PLANNED),
    EXAM_MANAGE("exam.manage", "Conduct examinations", "Schedule examinations, manage sessions and proctoring",
            Domain.EXAMINATIONS, Level.MANAGE, ScopeRule.UNIT, Availability.PLANNED),
    EXAM_SESSION_MANAGE("exam.session.manage", "Manage exam portals and sessions", "Configure the exam portal, candidate access, readiness sessions and single-use session codes",
            Domain.EXAMINATIONS, Level.MANAGE, ScopeRule.UNIT, Availability.AVAILABLE),
    EXAM_INCIDENT_MANAGE("exam.incident.manage", "Manage exam incidents", "Record platform outages and propose candidate compensation",
            Domain.EXAMINATIONS, Level.MANAGE, ScopeRule.ORGANIZATION, Availability.AVAILABLE),
    EXAM_INCIDENT_DECIDE("exam.incident.decide", "Approve candidate compensation", "Approve or reject compensation proposed by another person (four-eyes)",
            Domain.EXAMINATIONS, Level.MANAGE, ScopeRule.ORGANIZATION, Availability.AVAILABLE),
    EVALUATION_WRITE("evaluation.write", "Evaluate answers", "Evaluate allocated answer scripts",
            Domain.EVALUATION, Level.WRITE, ScopeRule.UNIT, Availability.PLANNED),
    EVALUATION_MANAGE("evaluation.manage", "Manage evaluation", "Allocate scripts, moderate and approve evaluation",
            Domain.EVALUATION, Level.MANAGE, ScopeRule.UNIT, Availability.PLANNED),
    RESULT_READ("result.read", "View results and reports", "View results, analytics and reports",
            Domain.RESULTS, Level.READ, ScopeRule.UNIT, Availability.PLANNED),
    RESULT_PUBLISH("result.publish", "Approve and publish results", "Approve and publish results",
            Domain.RESULTS, Level.MANAGE, ScopeRule.UNIT, Availability.PLANNED),
    PLACEMENT_READ("placement.read", "View placement", "View placement drives and outcomes",
            Domain.PLACEMENT, Level.READ, ScopeRule.UNIT, Availability.PLANNED),
    PLACEMENT_MANAGE("placement.manage", "Manage placement", "Manage placement drives, recruiters and shortlists",
            Domain.PLACEMENT, Level.MANAGE, ScopeRule.UNIT, Availability.PLANNED),
    AI_USE("ai.use", "Use AI assistance", "Use enabled AI capabilities (output always needs human approval)",
            Domain.AI, Level.WRITE, ScopeRule.UNIT, Availability.PLANNED),
    AI_APPROVE("ai.approve", "Approve AI-generated output", "Review and approve AI-generated questions or evaluations",
            Domain.AI, Level.MANAGE, ScopeRule.UNIT, Availability.PLANNED);

    /** Functional grouping shown in the role editor (order = display order). */
    public enum Domain {
        ORGANIZATION("Organization management"),
        INSTITUTIONS("Institutions, campuses and departments"),
        USERS("User management"),
        STAFF("Staff management"),
        ROLES("Role management"),
        ACADEMICS("Academic structure"),
        STUDENTS("Student management"),
        ADMISSIONS("Admissions"),
        ASSESSMENTS("Assessment management"),
        EXAMINATIONS("Examination management"),
        EVALUATION("Evaluation"),
        RESULTS("Results and reporting"),
        PLACEMENT("Placement"),
        AI("AI capabilities"),
        INTEGRATIONS("Integrations and API access"),
        AUDIT("Audit and administration"),
        PLATFORM("Platform administration");

        private final String label;

        Domain(String label) { this.label = label; }

        public String label() { return label; }
    }

    public enum Level { READ, WRITE, MANAGE }

    /** ORGANIZATION: only effective when granted for the whole organization. UNIT: organization or any unit subtree. */
    public enum ScopeRule { ORGANIZATION, UNIT }

    public enum Availability { AVAILABLE, PLANNED, PLATFORM }

    private final String key;
    private final String displayName;
    private final String description;
    private final Domain domain;
    private final Level level;
    private final ScopeRule scope;
    private final Availability availability;

    Permission(String key, String displayName, String description, Domain domain, Level level, ScopeRule scope, Availability availability) {
        this.key = key;
        this.displayName = displayName;
        this.description = description;
        this.domain = domain;
        this.level = level;
        this.scope = scope;
        this.availability = availability;
    }

    public String key() { return key; }

    public String displayName() { return displayName; }

    public String description() { return description; }

    public Domain domain() { return domain; }

    public Level level() { return level; }

    public ScopeRule scope() { return scope; }

    public Availability availability() { return availability; }

    /** Organization roles may contain only available, non-platform permissions. */
    public boolean grantable() { return availability == Availability.AVAILABLE; }

    /**
     * Privileged permissions (P2-08): never granted by an administrator's own authority. A role containing one is assigned
     * only through an approved four-eyes request whose approver holds them, or by the operator bootstrap.
     */
    public static final java.util.Set<String> PRIVILEGED = java.util.Set.of("question.approve", "question.key.read");

    public static Optional<Permission> fromKey(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
    }
}
