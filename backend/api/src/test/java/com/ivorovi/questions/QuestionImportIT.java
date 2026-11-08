package com.ivorovi.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.FakeMalwareScanner;
import com.ivorovi.support.TestOrg;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Bulk import (demo item 10): scanned upload, row validation through the editor's rules, duplicates within the file and
 * the bank, near duplicates, math injection refused, keys encrypted at rest and never returned, uploader-only idempotent
 * commit into drafts, and access control.
 */
class QuestionImportIT extends QuestionTestSupport {

    private static final String HEADER = "type,statement,option_a,option_b,option_c,option_d,correct,accepted_answers,numeric_value,tolerance,precision,marks,explanation\n";
    private static final String IMPORT_SECRET = "IMPORTKEY-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired FakeMalwareScanner scanner;
    private UUID bank;

    @BeforeEach
    void setUp() throws Exception {
        setUpOrg("qimp");
        bank = standardBank();
    }

    @AfterEach
    void scannerBack() {
        scanner.available = true;
    }

    private ResultActions upload(TestOrg.Account who, UUID bankId, String csv) throws Exception {
        return mvc.perform(multipart("/api/v1/question-banks/" + bankId + "/imports")
                .file(new MockMultipartFile("file", "questions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, who.bearer()));
    }

    private String csv() {
        return HEADER
                + "SINGLE_CHOICE,Which organ pumps blood through the body?,Lungs,Heart,Liver,Kidney,B,,,,,1," + IMPORT_SECRET + "\n"     // 2 valid
                + "NUMERIC,Evaluate $\\frac{1}{4}$ as a decimal.,,,,,,,0.25,0.001,3,2,\n"                                                  // 3 valid
                + "SINGLE_CHOICE,Which organ pumps blood through the body?,Lungs,Heart,Liver,Kidney,B,,,,,1,\n"                             // 4 dup in file
                + "SINGLE_CHOICE,What is the capital of France?,Paris,Rome,Madrid,Berlin,A,,,,,1,\n"                                        // 5 dup in bank
                + "SINGLE_CHOICE,Which of the following planets is the largest planet in our solar system,Mars,Jupiter,Venus,Earth,B,,,,,1,\n" // 6 near dup
                + "TRUE_FALSE,Click $\\href{javascript:alert(1)}{here}$,,,,,TRUE,,,,,1,\n"                                                  // 7 math injection
                + "NUMERIC,Missing value,,,,,,,,,,1,\n"                                                                                      // 8 invalid
                + "ESSAY,Unsupported type,,,,,,,,,,1,\n"                                                                                      // 9 invalid
                + "SHORT_ANSWER,Name the largest ocean.,,,,,,Pacific|Pacific Ocean,,,,1,\n";                                               // 10 valid
    }

    @Test
    void previewClassifiesRowsAndCommitCreatesDraftsOnceForTheUploader() throws Exception {
        // existing questions: an exact duplicate of row 5 and a near duplicate of row 6
        UUID existing = create(bank, faculty, """
                {"type":"SINGLE_CHOICE","content":%s,"body":{"options":[{"id":"A","content":%s},{"id":"B","content":%s},{"id":"C","content":%s},{"id":"D","content":%s}]},
                 "answerKey":{"correct":"A"}}""".formatted(content("What is the capital of France?"), content("Paris"), content("Rome"), content("Madrid"), content("Berlin")));
        create(bank, faculty, """
                {"type":"SINGLE_CHOICE","content":%s,"body":{"options":[{"id":"A","content":%s},{"id":"B","content":%s}]},"answerKey":{"correct":"A"}}"""
                .formatted(content("Which of the following planets is the largest planet in our solar system?"), content("Jupiter"), content("Mars")));

        JsonNode imp = JSON.readTree(upload(faculty, bank, csv()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID id = UUID.fromString(imp.path("id").asString());
        assertThat(imp.path("status").asString()).isEqualTo("PREVIEWED");
        assertThat(imp.path("totalRows").asInt()).isEqualTo(9);
        JsonNode rows = call(get("/api/v1/question-imports/" + id + "/rows"), faculty, status().isOk());
        Map<Integer, String> outcome = new HashMap<>();
        for (JsonNode r : rows) outcome.put(r.path("rowNo").asInt(), r.path("outcome").asString());
        assertThat(outcome).containsEntry(2, "VALID").containsEntry(3, "VALID").containsEntry(4, "DUPLICATE_IN_FILE").containsEntry(5, "DUPLICATE_IN_BANK")
                .containsEntry(6, "NEAR_DUPLICATE").containsEntry(7, "INVALID").containsEntry(8, "INVALID").containsEntry(9, "INVALID").containsEntry(10, "VALID");
        assertThat(rows.toString()).contains(existing.toString()).contains("not allowed");
        // keys and explanations are never returned and are encrypted at rest
        assertThat(rows.toString()).doesNotContain(IMPORT_SECRET).doesNotContain("\"correct\"");
        assertThat(ownerJdbc().sql("SELECT coalesce(string_agg(row_to_json(r)::text, ''), '') FROM question_import_row r WHERE import_id = ?").param(id)
                .query(String.class).single()).doesNotContain(IMPORT_SECRET);

        // only the uploader commits
        perform(post("/api/v1/question-imports/" + id + "/commit"), faculty2).andExpect(status().isForbidden());
        perform(post("/api/v1/question-imports/" + id + "/commit"), designer).andExpect(status().isForbidden());
        JsonNode done = call(post("/api/v1/question-imports/" + id + "/commit"), faculty, status().isOk());
        assertThat(done.path("status").asString()).isEqualTo("COMMITTED");
        assertThat(done.path("createdRows").asInt()).isEqualTo(3);
        // idempotent
        assertThat(call(post("/api/v1/question-imports/" + id + "/commit?includeNearDuplicates=true"), faculty, status().isOk()).path("createdRows").asInt()).isEqualTo(3);
        // encrypted row keys are discarded after commit
        assertThat(ownerJdbc().sql("SELECT count(*) FROM question_import_row WHERE import_id = ? AND key_enc IS NOT NULL").param(id).query(Long.class).single()).isZero();
        // created questions are drafts of the uploader, with the imported key visible only to them
        List<UUID> created = ownerJdbc().sql("SELECT created_question_id FROM question_import_row WHERE import_id = ? AND created_question_id IS NOT NULL ORDER BY row_no")
                .param(id).query(UUID.class).list();
        assertThat(created).hasSize(3);
        JsonNode d = detail(created.getFirst(), faculty);
        assertThat(d.path("current").path("state").asString()).isEqualTo("DRAFT");
        assertThat(d.path("current").path("source").asString()).isEqualTo("IMPORT");
        assertThat(call(get("/api/v1/questions/" + created.getFirst() + "/versions/1/answer-key"), faculty, status().isOk()).toString()).contains(IMPORT_SECRET);
        perform(get("/api/v1/questions/" + created.getFirst() + "/versions/1/answer-key"), controller).andExpect(status().isForbidden());
        assertThat(audits("question.import.committed")).isEqualTo(1);
    }

    @Test
    void nearDuplicatesAreCreatedOnlyWhenAsked() throws Exception {
        create(bank, faculty, """
                {"type":"SINGLE_CHOICE","content":%s,"body":{"options":[{"id":"A","content":%s},{"id":"B","content":%s}]},"answerKey":{"correct":"A"}}"""
                .formatted(content("Which of the following planets is the largest planet in our solar system?"), content("Jupiter"), content("Mars")));
        String file = HEADER + "SINGLE_CHOICE,Which of the following planets is the largest planet in our solar system,Mars,Jupiter,Venus,Earth,B,,,,,1,\n";
        UUID id = UUID.fromString(JSON.readTree(upload(faculty, bank, file).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("id").asString());
        assertThat(call(post("/api/v1/question-imports/" + id + "/commit?includeNearDuplicates=true"), faculty, status().isOk()).path("createdRows").asInt()).isEqualTo(1);
    }

    @Test
    void infectedFilesAndScannerOutagesAreRefusedBeforeParsing() throws Exception {
        upload(faculty, bank, HEADER + "TRUE_FALSE," + FakeMalwareScanner.EICAR.replace(",", ";") + ",,,,,TRUE,,,,,1,\n")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("file_infected"));
        scanner.available = false;
        upload(faculty, bank, csv()).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("scanning_failed"));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM question_import WHERE tenant_id = ?").param(org.tenant).query(Long.class).single()).isZero();
        assertThat(audits("question.import.upload_refused")).isEqualTo(2);
    }

    @Test
    void importsFollowBankPermissionsAndInstitutionBoundaries() throws Exception {
        upload(student, bank, csv()).andExpect(status().isForbidden());               // no authoring permission
        upload(otherFaculty, bank, csv()).andExpect(status().isNotFound());           // other institution
        UUID id = UUID.fromString(JSON.readTree(upload(faculty, bank, csv()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("id").asString());
        perform(get("/api/v1/question-imports/" + id), otherController).andExpect(status().isNotFound());
        perform(get("/api/v1/question-imports/" + id + "/rows"), otherFaculty).andExpect(status().isNotFound());
        perform(post("/api/v1/question-imports/" + id + "/commit"), otherFaculty).andExpect(status().isNotFound());
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "qimp-other");
        perform(get("/api/v1/question-imports/" + id), other.account("ORG_ADMIN", null)).andExpect(status().isNotFound());
        // unknown columns are refused (no silent data loss)
        upload(faculty, bank, "type,statement,answer_key_secret\nTRUE_FALSE,x,y\n").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_column"));
        // the template downloads for authors
        String template = perform(get("/api/v1/question-imports/template"), faculty).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(template).startsWith("type,statement").contains("NUMERIC").contains("SHORT_ANSWER");
        perform(get("/api/v1/question-imports/template"), student).andExpect(status().isForbidden());
    }
}
