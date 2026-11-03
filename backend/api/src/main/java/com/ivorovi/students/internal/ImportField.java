package com.ivorovi.students.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Ivorovi fields a student import file can provide, grouped in template sections. Headers are matched to fields by
 * their normalised key, label or an alias only; anything else (or anything that fits several fields) needs a manual
 * mapping. {@code personal} fields need {@code student.personal.read} to import or view.
 */
public enum ImportField {

    // ---- personal information
    FIRST_NAME(Section.PERSONAL, "First name", Kind.TEXT, 100, false, "Required", "Asha", "firstname", "given name", "fname", "student first name", "name first"),
    MIDDLE_NAME(Section.PERSONAL, "Middle name", Kind.TEXT, 100, false, null, "", "middlename", "mname"),
    LAST_NAME(Section.PERSONAL, "Last name", Kind.TEXT, 100, false, "Recommended", "Rao", "lastname", "surname", "family name", "lname", "student last name"),
    DATE_OF_BIRTH(Section.PERSONAL, "Date of birth", Kind.DATE, 10, true, "YYYY-MM-DD or DD/MM/YYYY", "2007-08-14", "dob", "birth date", "birthdate", "date of birth"),
    GENDER(Section.PERSONAL, "Gender", Kind.CHOICE, 20, true, "Female, Male, Other or Undisclosed", "Female", "sex"),
    NATIONALITY(Section.PERSONAL, "Nationality", Kind.TEXT, 60, true, null, "Indian", "citizenship"),
    PERSONAL_EMAIL(Section.PERSONAL, "Personal email", Kind.EMAIL, 320, true, null, "asha.rao@example.com", "email", "email address", "email id", "personal email id"),
    MOBILE(Section.PERSONAL, "Mobile", Kind.PHONE, 20, true, "7–20 digits; spaces, - and a leading + allowed", "+91 98450 12345",
            "mobile number", "mobile no", "phone", "phone number", "contact number", "student mobile"),
    ALTERNATE_PHONE(Section.PERSONAL, "Alternate phone", Kind.PHONE, 20, true, null, "", "alternate number", "alternate mobile", "other phone", "alternative phone"),
    CURRENT_ADDRESS(Section.PERSONAL, "Current address", Kind.TEXT, 500, true, null, "", "address", "correspondence address", "present address"),
    PERMANENT_ADDRESS(Section.PERSONAL, "Permanent address", Kind.TEXT, 500, true, null, "", "home address", "native address"),

    // ---- identification
    STUDENT_NO(Section.IDENTIFICATION, "Student ID", Kind.IDENTIFIER, 40, false, "Required when the institution enters student IDs; leave empty when they are generated",
            "", "student id", "student number", "student no", "enrolment number", "enrollment number", "enrolment no", "enrollment no"),
    REGISTRATION_NO(Section.IDENTIFICATION, "Registration number", Kind.IDENTIFIER, 40, false, "Must match the institution's registration format", "",
            "registration no", "reg no", "regno", "university registration number", "prn"),
    ROLL_NO(Section.IDENTIFICATION, "Roll number", Kind.IDENTIFIER, 40, false, null, "", "roll no", "rollno"),
    PREVIOUS_INSTITUTION_REF(Section.IDENTIFICATION, "Previous institution ID", Kind.TEXT, 100, false, null, "", "previous id", "old student id", "legacy id"),
    INSTITUTIONAL_EMAIL(Section.IDENTIFICATION, "Institutional email", Kind.EMAIL, 320, false, null, "", "college email", "official email", "institute email",
            "university email"),
    ADMISSION_YEAR(Section.IDENTIFICATION, "Admission year", Kind.INTEGER, 4, false, "Year used for generated student IDs (default: current year)", "2026",
            "year of admission", "intake year"),

    // ---- admission details
    ADMISSION_NO(Section.ADMISSION, "Admission number", Kind.IDENTIFIER, 40, false, "Required with admission or enrolment details", "ADM-2026-0001",
            "admission no", "admission id"),
    ADMISSION_DATE(Section.ADMISSION, "Admission date", Kind.DATE, 10, false, "YYYY-MM-DD or DD/MM/YYYY", "2026-07-01", "date of admission"),
    ADMISSION_TYPE(Section.ADMISSION, "Admission type", Kind.CHOICE, 40, false, "Regular, Lateral entry, Transfer or an organization-defined type (default: Regular)",
            "Regular", "entry type", "type of admission"),
    ADMISSION_CATEGORY(Section.ADMISSION, "Admission category", Kind.CHOICE, 40, true, "An admission category configured in Student settings", "",
            "category", "quota", "reservation category"),
    ADMISSION_STATUS(Section.ADMISSION, "Admission status", Kind.CHOICE, 20, false, "Confirmed (default) or Provisional", "Confirmed"),
    ENTRY_ACADEMIC_YEAR(Section.ADMISSION, "Entry academic year", Kind.REFERENCE, 100, false, "Academic year code or name (default: the batch's year)", "",
            "admission academic year", "entry year"),
    PROGRAM(Section.ADMISSION, "Program", Kind.REFERENCE, 100, false, "Program code or name (optional when the course or batch identifies it)", "",
            "programme", "program code", "programme code", "degree"),
    COURSE(Section.ADMISSION, "Course", Kind.REFERENCE, 100, false, "Course code or name (optional when the batch identifies it)", "", "course code", "branch",
            "specialisation", "specialization"),
    ENTRY_TERM(Section.ADMISSION, "Entry semester", Kind.INTEGER, 2, false, "Default 1 (lateral entry and transfers may start later)", "1", "entry term", "entry sem"),
    PREVIOUS_INSTITUTION_NAME(Section.ADMISSION, "Previous institution", Kind.TEXT, 200, false, "Required for transfer admissions", "", "previous school",
            "previous college", "last institution", "previous institution name"),

    // ---- academic enrolment
    INSTITUTION(Section.ENROLMENT, "Institution", Kind.REFERENCE, 200, false, "Optional check: code or name of the university/college chosen for the import", "",
            "college", "university", "institute"),
    DEPARTMENT(Section.ENROLMENT, "Department", Kind.REFERENCE, 200, false, "Optional check: must be the course's department", "", "dept", "department code"),
    BATCH(Section.ENROLMENT, "Batch", Kind.REFERENCE, 40, false, "Batch code; required to enrol the student", "", "batch code", "class", "cohort"),
    ACADEMIC_YEAR(Section.ENROLMENT, "Academic year", Kind.REFERENCE, 100, false, "Academic year code or name (default: the batch's year)", "", "current academic year"),
    TERM_NUMBER(Section.ENROLMENT, "Semester", Kind.INTEGER, 2, false, "Current semester/term number (default: the entry semester)", "1",
            "sem", "term", "current semester", "semester number", "term number"),
    ENROLMENT_DATE(Section.ENROLMENT, "Enrolment date", Kind.DATE, 10, false, "Default: the admission date", "", "enrollment date", "date of enrolment",
            "date of enrollment", "joining date"),
    STATUS(Section.ENROLMENT, "Student status", Kind.CHOICE, 20, false, "Prospective, Admitted, Enrolled or Active (default: derived from the records)", "Active",
            "status", "lifecycle status"),

    // ---- guardians (two per row)
    GUARDIAN1_NAME(Section.GUARDIAN, "Guardian 1 name", Kind.TEXT, 200, true, "Required with any guardian detail", "", "guardian name", "parent name", "guardian"),
    GUARDIAN1_RELATIONSHIP(Section.GUARDIAN, "Guardian 1 relationship", Kind.CHOICE, 20, true, "Mother, Father, Guardian, Grandparent, Sibling, Spouse, Relative or Other",
            "", "guardian relationship", "relationship", "relation"),
    GUARDIAN1_MOBILE(Section.GUARDIAN, "Guardian 1 mobile", Kind.PHONE, 20, true, null, "", "guardian mobile", "parent mobile", "guardian phone", "parent phone"),
    GUARDIAN1_EMAIL(Section.GUARDIAN, "Guardian 1 email", Kind.EMAIL, 320, true, null, "", "guardian email", "parent email"),
    GUARDIAN1_ADDRESS(Section.GUARDIAN, "Guardian 1 address", Kind.TEXT, 500, true, null, "", "guardian address", "parent address"),
    GUARDIAN1_EMERGENCY(Section.GUARDIAN, "Guardian 1 emergency contact", Kind.BOOLEAN, 5, true, "Yes or No", "", "emergency contact"),
    GUARDIAN2_NAME(Section.GUARDIAN, "Guardian 2 name", Kind.TEXT, 200, true, null, "", "second guardian name"),
    GUARDIAN2_RELATIONSHIP(Section.GUARDIAN, "Guardian 2 relationship", Kind.CHOICE, 20, true, null, "", "second guardian relationship"),
    GUARDIAN2_MOBILE(Section.GUARDIAN, "Guardian 2 mobile", Kind.PHONE, 20, true, null, "", "second guardian mobile"),
    GUARDIAN2_EMAIL(Section.GUARDIAN, "Guardian 2 email", Kind.EMAIL, 320, true, null, "", "second guardian email"),
    GUARDIAN2_ADDRESS(Section.GUARDIAN, "Guardian 2 address", Kind.TEXT, 500, true, null, "", "second guardian address"),
    GUARDIAN2_EMERGENCY(Section.GUARDIAN, "Guardian 2 emergency contact", Kind.BOOLEAN, 5, true, "Yes or No", "");

    public enum Section {
        PERSONAL("Student personal information"), IDENTIFICATION("Student identification"), ADMISSION("Admission details"),
        ENROLMENT("Academic enrolment"), GUARDIAN("Guardian information");

        public final String label;

        Section(String label) { this.label = label; }
    }

    public enum Kind { TEXT, IDENTIFIER, DATE, EMAIL, PHONE, CHOICE, INTEGER, REFERENCE, BOOLEAN }

    public final Section section;
    public final String label;
    public final Kind kind;
    public final int maxLength;
    public final boolean personal;
    public final String hint;
    public final String example;
    private final List<String> aliases;

    ImportField(Section section, String label, Kind kind, int maxLength, boolean personal, String hint, String example, String... aliases) {
        this.section = section;
        this.label = label;
        this.kind = kind;
        this.maxLength = maxLength;
        this.personal = personal;
        this.hint = hint;
        this.example = example;
        this.aliases = List.of(aliases);
    }

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public List<String> aliases() { return aliases; }

    public boolean guardian(int n) { return name().startsWith("GUARDIAN" + n + "_"); }

    static final Set<ImportField> ADMISSION_FIELDS = Set.of(ADMISSION_NO, ADMISSION_DATE, ADMISSION_TYPE, ADMISSION_CATEGORY, ADMISSION_STATUS,
            ENTRY_ACADEMIC_YEAR, PROGRAM, COURSE, ENTRY_TERM, PREVIOUS_INSTITUTION_NAME);
    static final Set<ImportField> ENROLMENT_FIELDS = Set.of(BATCH, ACADEMIC_YEAR, TERM_NUMBER, ENROLMENT_DATE);

    static ImportField ofKey(String key) {
        if (key == null) return null;
        for (ImportField f : values()) if (f.key().equals(key)) return f;
        return null;
    }

    /** Lower-case letters and digits only; accents removed; parenthesised notes (e.g. "(DD/MM/YYYY)") and "*" dropped. */
    static String normalise(String header) {
        if (header == null) return "";
        String s = header.replaceAll("\\([^)]*\\)", " ").replace("*", " ");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static final Map<String, List<ImportField>> INDEX = buildIndex();

    private static Map<String, List<ImportField>> buildIndex() {
        Map<String, List<ImportField>> idx = new LinkedHashMap<>();
        for (ImportField f : values()) {
            List<String> names = new ArrayList<>(f.aliases);
            names.add(f.key());
            names.add(f.label);
            for (String n : names) {
                List<ImportField> list = idx.computeIfAbsent(normalise(n), k -> new ArrayList<>());
                if (!list.contains(f)) list.add(f);
            }
        }
        return idx;
    }

    /** Fields whose key, label or alias equals the normalised header (several = ambiguous). */
    static List<ImportField> candidates(String header) {
        return INDEX.getOrDefault(normalise(header), List.of());
    }

    static List<ImportField> inSection(Section s) {
        return Arrays.stream(values()).filter(f -> f.section == s).toList();
    }
}
