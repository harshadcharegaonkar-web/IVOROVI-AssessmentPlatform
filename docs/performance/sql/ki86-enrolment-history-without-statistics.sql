-- KI-86 reproduction (local development database only, superuser): removes the planner statistics of four academic
-- tables as after a restore or crash recovery, times the former and the new enrolment-history query, then runs ANALYZE.
-- Usage: psql -U postgres -d ivorovi -v student=<uuid> -v tenant=<uuid> -f ki86-enrolment-history-without-statistics.sql
-- Day 4 result (synthetic data, 36,474 enrolments): former query 216,839 ms; new query 1.3 ms; former query after ANALYZE 5 ms.
SET search_path=ivorovi;
UPDATE pg_class SET reltuples = -1 WHERE relname IN ('program','course','batch','academic_year');
DELETE FROM pg_statistic WHERE starelid IN (SELECT oid FROM pg_class WHERE relname IN ('program','course','batch','academic_year'));
SET ROLE ivorovi_app; SET app.tenant_id=:'tenant';
\timing on
SELECT 'current', count(*) FROM (SELECT e.*, i.name, cp.name, d.name, p.name, c.name, b.code, b.name, y.name, t.name, u.display_name FROM enrolment e JOIN org_unit i ON i.id = e.institution_id LEFT JOIN org_unit cp ON cp.id = e.campus_id JOIN org_unit d ON d.id = e.department_id JOIN program p ON p.id = e.program_id JOIN course c ON c.id = e.course_id JOIN batch b ON b.id = e.batch_id JOIN academic_year y ON y.id = e.academic_year_id LEFT JOIN academic_term t ON t.id = e.term_id LEFT JOIN user_account u ON u.id = e.created_by WHERE e.student_id=:'student') x;
SELECT 'proposed', count(*) FROM (SELECT e.*, (SELECT name FROM org_unit WHERE id = e.institution_id), (SELECT name FROM org_unit WHERE id = e.campus_id), (SELECT name FROM org_unit WHERE id = e.department_id), (SELECT name FROM program WHERE id = e.program_id), (SELECT name FROM course WHERE id = e.course_id), (SELECT code FROM batch WHERE id = e.batch_id), (SELECT name FROM batch WHERE id = e.batch_id), (SELECT name FROM academic_year WHERE id = e.academic_year_id), (SELECT name FROM academic_term WHERE id = e.term_id), (SELECT display_name FROM user_account WHERE id = e.created_by) FROM enrolment e WHERE e.student_id=:'student') x;
RESET ROLE;
ANALYZE;
SET ROLE ivorovi_app; SET app.tenant_id=:'tenant';
SELECT 'current, analysed', count(*) FROM (SELECT e.* FROM enrolment e JOIN org_unit i ON i.id = e.institution_id LEFT JOIN org_unit cp ON cp.id = e.campus_id JOIN org_unit d ON d.id = e.department_id JOIN program p ON p.id = e.program_id JOIN course c ON c.id = e.course_id JOIN batch b ON b.id = e.batch_id JOIN academic_year y ON y.id = e.academic_year_id LEFT JOIN academic_term t ON t.id = e.term_id LEFT JOIN user_account u ON u.id = e.created_by WHERE e.student_id=:'student') x;
