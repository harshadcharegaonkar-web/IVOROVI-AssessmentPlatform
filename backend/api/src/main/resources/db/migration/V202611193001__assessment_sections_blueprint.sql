-- Demo programme, add-on C (Session 4): assessment sections and blueprint constraints.
--  * assessment_item.section_no and assessment_rule.section_no exist since V202611130010 (default 1). A version that
--    defines sections has one header row per section number used; a version without header rows is a single unnamed
--    section (exactly as before: papers keep generator version 1).
--  * Blueprint constraints state the coverage a version must GUARANTEE for every generated paper (by subject, topic,
--    difficulty or question type, for the whole paper or one section). Validation proves them from fixed questions and
--    the rules' pools; publication re-runs that proof on the live pools before freezing them.
--  * Both tables change only while their version is a draft (same guard as items and rules); RLS per tenant.

CREATE TABLE assessment_section (
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    version_id            uuid          NOT NULL,
    section_no            smallint      NOT NULL CHECK (section_no BETWEEN 1 AND 20),
    title                 varchar(150)  NOT NULL CHECK (length(btrim(title)) > 0),
    instructions          varchar(4000),
    PRIMARY KEY (version_id, section_no),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id)
);
CREATE INDEX assessment_section_tenant_idx ON assessment_section (tenant_id, version_id);

CREATE TABLE assessment_blueprint_constraint (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    version_id            uuid          NOT NULL,
    ordinal               integer       NOT NULL,
    section_no            smallint      CHECK (section_no BETWEEN 1 AND 20),          -- NULL: the whole paper
    dimension             varchar(20)   NOT NULL CHECK (dimension IN ('SUBJECT', 'TOPIC', 'DIFFICULTY', 'QUESTION_TYPE')),
    target_id             uuid,                                                        -- subject, topic or difficulty id
    target_code           varchar(40),                                                 -- question type code
    min_count             integer       NOT NULL DEFAULT 0 CHECK (min_count BETWEEN 0 AND 500),
    max_count             integer       CHECK (max_count BETWEEN 0 AND 500),
    min_marks             numeric(9,2)  CHECK (min_marks >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (version_id, ordinal),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id),
    CONSTRAINT assessment_blueprint_target CHECK ((dimension = 'QUESTION_TYPE') = (target_code IS NOT NULL) AND (dimension = 'QUESTION_TYPE') = (target_id IS NULL)),
    CONSTRAINT assessment_blueprint_range CHECK (max_count IS NULL OR max_count >= min_count),
    CONSTRAINT assessment_blueprint_meaningful CHECK (min_count > 0 OR max_count IS NOT NULL OR min_marks IS NOT NULL)
);
CREATE INDEX assessment_blueprint_version_idx ON assessment_blueprint_constraint (tenant_id, version_id);

CREATE TRIGGER assessment_section_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_section
    FOR EACH ROW EXECUTE FUNCTION assessment_draft_only_guard();
CREATE TRIGGER assessment_blueprint_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_blueprint_constraint
    FOR EACH ROW EXECUTE FUNCTION assessment_draft_only_guard();

ALTER TABLE assessment_section ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON assessment_section USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE assessment_blueprint_constraint ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON assessment_blueprint_constraint USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
