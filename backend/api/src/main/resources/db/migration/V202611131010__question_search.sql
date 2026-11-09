-- WS-B / KI-75: bank-scoped question search that can use indexes (performance; no change to access rules).
-- ===================================================================================================== why
-- Under row-level security PostgreSQL may not evaluate a non-leakproof user predicate (full-text @@, ILIKE, the tag
-- subquery) before the policy predicate, so it cannot use their indexes. Every search, even an unfiltered first page,
-- therefore read every question version of the organization (and the planner's hash join made it read all versions of
-- the table), and the cost grew with the organization, not the bank.
--
-- question_search() follows the pattern of question_near_duplicate() (V202611121001):
--   * it runs as the owner with the SAME tenant predicate as the RLS policy, on both tables (current_tenant_id();
--     NULL outside a tenant => no rows);
--   * it is limited to one bank; the caller has already checked that the user may read that bank;
--   * it returns only question ids (one page, in display order) and the number of matches, never content.
-- The application then loads the page's rows by id under RLS, as before.
-- All values are passed as bind parameters (EXECUTE ... USING); only fixed SQL fragments are concatenated.

-- ===================================================================================================== indexes
-- page order within a bank (index-only for unfiltered pages and counts)
CREATE INDEX question_bank_order_idx ON question (tenant_id, bank_id, updated_at DESC, id);
-- substring search (ILIKE) over statement and option text; plain_text never holds answer information
CREATE INDEX question_version_plain_trgm_idx ON question_version USING gin (plain_text gin_trgm_ops);
-- case-insensitive tag filter
CREATE FUNCTION question_tags_ci(p_tags text[]) RETURNS text[]
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    SET search_path = pg_catalog
AS $$ SELECT coalesce(array_agg(lower(t)), '{}') FROM unnest(p_tags) t $$;
CREATE INDEX question_version_tags_ci_idx ON question_version USING gin (question_tags_ci(tags));

-- ===================================================================================================== search
CREATE FUNCTION question_search(
    p_bank uuid, p_approved_only boolean, p_state text, p_type text, p_status text, p_subject uuid, p_topic uuid,
    p_subtopic uuid, p_difficulty uuid, p_outcome uuid, p_tag text, p_attr_key text, p_attr_value text, p_q text,
    p_limit integer, p_offset integer,
    OUT ids uuid[], OUT total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, ${flyway:defaultSchema}, public
AS $$
DECLARE
    tenant  uuid := current_tenant_id();
    q       text := nullif(btrim(coalesce(p_q, '')), '');
    lim     integer := greatest(1, least(100, coalesce(p_limit, 25)));
    off     integer := greatest(0, coalesce(p_offset, 0));
    attr    boolean := p_attr_key IS NOT NULL AND p_attr_value IS NOT NULL;
    vfilter boolean;
    from_   text;
    where_  text;
BEGIN
    ids := ARRAY[]::uuid[];
    total := 0;
    IF tenant IS NULL OR p_bank IS NULL THEN RETURN; END IF;                     -- no tenant => nothing, as under RLS
    vfilter := p_state IS NOT NULL OR p_type IS NOT NULL OR p_subject IS NOT NULL OR p_topic IS NOT NULL OR p_subtopic IS NOT NULL
               OR p_difficulty IS NOT NULL OR p_outcome IS NOT NULL OR p_tag IS NOT NULL OR attr OR q IS NOT NULL;
    where_ := ' WHERE q.tenant_id = $1 AND q.bank_id = $2'
        || CASE WHEN p_status IS NOT NULL THEN ' AND q.status = $5' ELSE '' END
        || CASE WHEN p_approved_only THEN ' AND q.approved_version_id IS NOT NULL' ELSE '' END;
    IF vfilter THEN
        from_ := ' FROM question q JOIN question_version v ON v.id = '
              || CASE WHEN p_approved_only THEN 'q.approved_version_id' ELSE 'q.current_version_id' END
              || ' AND v.tenant_id = $1';
        where_ := where_
            || CASE WHEN p_state IS NOT NULL THEN ' AND v.state = $3' ELSE '' END
            || CASE WHEN p_type IS NOT NULL THEN ' AND v.type_code = $4' ELSE '' END
            || CASE WHEN p_subject IS NOT NULL THEN ' AND v.subject_id = $6' ELSE '' END
            || CASE WHEN p_topic IS NOT NULL THEN ' AND v.topic_id = $7' ELSE '' END
            || CASE WHEN p_subtopic IS NOT NULL THEN ' AND v.subtopic_id = $8' ELSE '' END
            || CASE WHEN p_difficulty IS NOT NULL THEN ' AND v.difficulty_id = $9' ELSE '' END
            || CASE WHEN p_outcome IS NOT NULL THEN ' AND $10 = ANY (v.outcome_ids)' ELSE '' END
            || CASE WHEN p_tag IS NOT NULL THEN ' AND question_tags_ci(v.tags) @> ARRAY[lower($11)]' ELSE '' END
            || CASE WHEN attr THEN ' AND v.attributes ->> $12 = $13' ELSE '' END
            || CASE WHEN q IS NOT NULL THEN ' AND (v.search_vector @@ websearch_to_tsquery(''simple'', $14) OR v.plain_text ILIKE $15)' ELSE '' END;
    ELSE
        from_ := ' FROM question q';
    END IF;
    EXECUTE 'SELECT count(*)' || from_ || where_ INTO total
        USING tenant, p_bank, p_state, p_type, p_status, p_subject, p_topic, p_subtopic, p_difficulty, p_outcome, p_tag,
              p_attr_key, p_attr_value, q, '%' || replace(replace(replace(q, '\', '\\'), '%', '\%'), '_', '\_') || '%';
    IF total > off THEN
        EXECUTE 'SELECT ARRAY(SELECT q.id' || from_ || where_ || ' ORDER BY q.updated_at DESC, q.id LIMIT $16 OFFSET $17)' INTO ids
            USING tenant, p_bank, p_state, p_type, p_status, p_subject, p_topic, p_subtopic, p_difficulty, p_outcome, p_tag,
                  p_attr_key, p_attr_value, q, '%' || replace(replace(replace(q, '\', '\\'), '%', '\%'), '_', '\_') || '%', lim, off;
    END IF;
END $$;
REVOKE ALL ON FUNCTION question_search(uuid, boolean, text, text, text, uuid, uuid, uuid, uuid, uuid, text, text, text, text, integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION question_search(uuid, boolean, text, text, text, uuid, uuid, uuid, uuid, uuid, text, text, text, text, integer, integer) TO ${appRole};
