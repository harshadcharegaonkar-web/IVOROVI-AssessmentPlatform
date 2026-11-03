-- Phase 1, increment 1.6 (performance): duplicate lookups of the student import.
--
-- Under row-level security PostgreSQL cannot use expression indexes such as lower(student_no) for user predicates,
-- because lower() is not leakproof; every per-row duplicate check of an import then scanned the whole student table
-- (about 40 ms per row at 25,000 students, growing with the table). This function returns only the ids of the current
-- tenant's students that match the given identifiers or name, using the indexes. Isolation is kept inside the function:
-- it filters on current_tenant_id() explicitly and returns nothing without a tenant context. The caller then reads the
-- matching students by primary key under RLS as usual.
CREATE FUNCTION student_import_candidates(p_institution uuid, p_student_no text, p_registration_no text, p_institutional_email text,
                                          p_admission_no text, p_first_name text, p_last_name text) RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, ${flyway:defaultSchema} AS
$$
    SELECT id FROM ${flyway:defaultSchema}.student
        WHERE p_student_no IS NOT NULL AND tenant_id = current_tenant_id() AND lower(student_no) = lower(p_student_no)
    UNION
    SELECT id FROM ${flyway:defaultSchema}.student
        WHERE p_registration_no IS NOT NULL AND tenant_id = current_tenant_id() AND lower(registration_no) = lower(p_registration_no)
    UNION
    SELECT id FROM ${flyway:defaultSchema}.student
        WHERE p_institutional_email IS NOT NULL AND tenant_id = current_tenant_id() AND institutional_email = p_institutional_email
    UNION
    SELECT student_id FROM ${flyway:defaultSchema}.admission_record
        WHERE p_admission_no IS NOT NULL AND tenant_id = current_tenant_id() AND institution_id = p_institution
          AND lower(admission_no) = lower(p_admission_no) AND status <> 'CANCELLED'
    UNION
    SELECT id FROM ${flyway:defaultSchema}.student
        WHERE p_first_name IS NOT NULL AND p_last_name IS NOT NULL AND tenant_id = current_tenant_id()
          AND lower(first_name) = lower(p_first_name) AND lower(last_name) = lower(p_last_name) AND institution_id = p_institution
    UNION
    SELECT id FROM ${flyway:defaultSchema}.student
        WHERE p_first_name IS NOT NULL AND p_last_name IS NULL AND tenant_id = current_tenant_id()
          AND lower(first_name) = lower(p_first_name) AND last_name IS NULL AND institution_id = p_institution
$$;
REVOKE ALL ON FUNCTION student_import_candidates(uuid, text, text, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION student_import_candidates(uuid, text, text, text, text, text, text) TO ${appRole};

-- Lookups by student made for every imported row (guardian count, admissions referenced by enrolments). Plain additive
-- indexes; they also speed up the same queries of the student services (no behaviour change).
CREATE INDEX IF NOT EXISTS guardian_student_idx ON guardian (tenant_id, student_id);
CREATE INDEX IF NOT EXISTS enrolment_admission_idx ON enrolment (tenant_id, admission_id) WHERE admission_id IS NOT NULL;
