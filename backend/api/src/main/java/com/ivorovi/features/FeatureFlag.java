package com.ivorovi.features;

import java.util.Arrays;
import java.util.Optional;

/**
 * Feature catalog. Defaults are deliberately conservative: every AI capability and every external integration
 * is OFF until a tenant explicitly enables it.
 */
public enum FeatureFlag {
    MODULE_ADMISSIONS("module.admissions", Category.MODULE, true, "Admissions & eligibility screening"),
    MODULE_ACADEMICS("module.academics", Category.MODULE, true, "Academic (internal/semester) assessments"),
    MODULE_PLACEMENT("module.placement", Category.MODULE, true, "Placement & employability assessments"),
    MODULE_PROCTORING("module.proctoring", Category.MODULE, false, "Online exam proctoring"),
    ASSESSMENT_TYPE_OBJECTIVE("assessment.type.objective", Category.ASSESSMENT_TYPE, true, "Objective question types"),
    ASSESSMENT_TYPE_DESCRIPTIVE("assessment.type.descriptive", Category.ASSESSMENT_TYPE, true, "Descriptive / essay questions"),
    ASSESSMENT_TYPE_CODING("assessment.type.coding", Category.ASSESSMENT_TYPE, false, "Coding questions (requires code execution service)"),
    AI_EVALUATION("ai.evaluation", Category.AI, false, "AI-suggested scores for descriptive answers (human approval required)"),
    AI_RECOMMENDATIONS("ai.recommendations", Category.AI, false, "AI learning, admission and placement recommendations (advisory)"),
    AI_QUESTION_GENERATION("ai.question-generation", Category.AI, false, "AI-assisted question drafting"),
    AI_PROCTORING_VISION("ai.proctoring-vision", Category.AI, false, "Computer-vision analysis of proctoring snapshots"),
    INTEGRATION_PUBLIC_API("integration.public-api", Category.INTEGRATION, false, "Public API clients"),
    INTEGRATION_WEBHOOKS("integration.webhooks", Category.INTEGRATION, false, "Outbound webhooks"),
    INTEGRATION_LTI("integration.lti", Category.INTEGRATION, false, "LMS integration via LTI 1.3"),
    INTEGRATION_SIS("integration.sis", Category.INTEGRATION, false, "Student information system synchronisation");

    public enum Category { MODULE, ASSESSMENT_TYPE, AI, INTEGRATION }

    private final String key;
    private final Category category;
    private final boolean defaultEnabled;
    private final String description;

    FeatureFlag(String key, Category category, boolean defaultEnabled, String description) {
        this.key = key;
        this.category = category;
        this.defaultEnabled = defaultEnabled;
        this.description = description;
    }

    public String key() { return key; }
    public Category category() { return category; }
    public boolean defaultEnabled() { return defaultEnabled; }
    public String description() { return description; }

    /** Integrations are organisation-level contracts and cannot be narrowed per institution. */
    public boolean institutionOverridable() { return category != Category.INTEGRATION; }

    public static Optional<FeatureFlag> fromKey(String key) {
        return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
    }
}
