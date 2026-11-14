-- Result publication governance (decision R-1, 2026-11-14, pending product-owner confirmation), consistent with P2-08:
--  * exam.results.review is ordinary: administrators hold it too (1.3 privilege ceiling: administrators hold every
--    non-privileged permission they can grant), so the head of an institution can review results;
--  * exam.results.approve is PRIVILEGED (like assessment.publish): held by exam controllers; granting it to anyone needs
--    independent approval (P2-08). The reviewer can never approve their own review (framework, handler, database).
UPDATE role SET permissions = permissions || ARRAY['exam.results.review']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN') AND NOT ('exam.results.review' = ANY (permissions));

CREATE OR REPLACE FUNCTION privileged_permissions() RETURNS text[] LANGUAGE sql IMMUTABLE AS
$$ SELECT ARRAY['question.approve', 'question.key.read', 'assessment.publish', 'exam.results.approve'] $$;
