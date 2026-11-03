package com.ivorovi.tenancy;

/**
 * Stable entity types whose display labels an organization may rename in System Settings (e.g. "School" for
 * COLLEGE, "Branch" for COURSE, "Paper" for SUBJECT, "Trimester" for TERM). Labels never change stored types.
 */
public enum Terminology {
    UNIVERSITY("University", "Universities"),
    COLLEGE("College", "Colleges"),
    CAMPUS("Campus", "Campuses"),
    DEPARTMENT("Department", "Departments"),
    PROGRAM("Program", "Programs"),
    COURSE("Course", "Courses"),
    SUBJECT("Subject", "Subjects"),
    TERM("Semester", "Semesters"),
    BATCH("Batch", "Batches");

    private final String singular;
    private final String plural;

    Terminology(String singular, String plural) {
        this.singular = singular;
        this.plural = plural;
    }

    public String defaultSingular() { return singular; }

    public String defaultPlural() { return plural; }
}
