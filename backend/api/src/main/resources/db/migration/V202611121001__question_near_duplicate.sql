-- Increment 2.1: indexed near-duplicate search for question import (performance; no change to access rules).
-- ===================================================================================================== near-duplicate search
-- Row-level security prevents the trigram index from being used (the % operator is not leakproof), which made the
-- near-duplicate check a sequential scan of every version of the organization. This function runs as the owner with
-- the SAME tenant predicate as the RLS policy (current_tenant_id(); NULL outside a tenant => no rows), is limited to one
-- bank, fixes the threshold, and returns only an id and a similarity score (never content).
CREATE FUNCTION question_near_duplicate(p_bank uuid, p_text text) RETURNS TABLE (question_id uuid, sim real)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, ${flyway:defaultSchema}, public
AS $$
BEGIN
    PERFORM similarity('', '');                                             -- loads pg_trgm so its setting exists
    PERFORM set_config('pg_trgm.similarity_threshold', '0.85', true);      -- transaction-local; the % operator then prunes via the index
    RETURN QUERY
        SELECT q.id, similarity(v.normalized_text, p_text)
        FROM question_version v JOIN question q ON q.current_version_id = v.id
        WHERE v.tenant_id = current_tenant_id() AND q.tenant_id = current_tenant_id() AND q.bank_id = p_bank
          AND v.normalized_text % p_text
        ORDER BY 2 DESC LIMIT 1;
END $$;
REVOKE ALL ON FUNCTION question_near_duplicate(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION question_near_duplicate(uuid, text) TO ${appRole};
