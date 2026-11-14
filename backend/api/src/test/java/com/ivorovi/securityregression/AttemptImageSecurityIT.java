package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.TestOrg;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Security regression for question images in delivery (KI-83, QA, D-7), over HTTP through the delivery security chain:
 * {@code GET /api/v1/delivery/portals/{key}/attempts/{attemptId}/images/{assetId}} answers only the caller's own open attempt
 * and only images of its paper, never cacheable, with a fixed content type; everything else is a 404 that looks the same.
 * Complements {@code AttemptIT} (service level). Fixture: {@link DeliveryJourneySupport} with two image questions.
 */
@Tag("security-regression")
class AttemptImageSecurityIT extends DeliveryJourneySupport {

    private final List<String> inPaper = new ArrayList<>();
    private String unlinked;

    /** Two single-choice questions with an image in the statement and one in an option; one more image used by no question. */
    @Override
    protected List<String> questionDrafts(UUID bank) throws Exception {
        inPaper.clear();
        for (int i = 0; i < 4; i++) inPaper.add(upload(bank, 40 + i, faculty));
        unlinked = upload(bank, 50, faculty);
        return List.of(imageMcq("Image question 1 " + rnd(), inPaper.get(0), inPaper.get(1)), imageMcq("Image question 2 " + rnd(), inPaper.get(2), inPaper.get(3)));
    }

    @Test
    void theOwnOpenAttemptGetsItsImagesUncacheableWithAFixedContentType() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        for (String asset : inPaper) {
            byte[] png = as(get(image(attempt, asset)), asha).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Disposition", "inline"))
                    .andExpect(r -> assertThat(r.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store").contains("private"))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        }
    }

    @Test
    void anotherCandidateAnUnlinkedAssetAnUnknownIdAndAnotherOrganizationsAssetAreNotFound() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        UUID bilals = startAttempt(bilal, codes.get(1));
        // Bilal's own paper has the same questions, so the image itself is his to see — but never through Asha's attempt
        as(get(image(bilals, inPaper.get(0))), bilal).andExpect(status().isOk());
        // another candidate's attempt answers exactly like an attempt id that does not exist
        String foreignAttempt = problem(as(get(image(attempt, inPaper.get(0))), bilal).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        String unknownAttempt = problem(as(get(image(UUID.randomUUID(), inPaper.get(0))), bilal).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        assertThat(foreignAttempt).isEqualTo(unknownAttempt);
        // uploaded to the same bank but used by no question; a random id; an image of another organization
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "secreg-img");
        TestOrg.Account otherAdmin = other.account("ORG_ADMIN", null);
        String otherBank = call(post("/api/v1/question-banks").content("""
                {"orgUnitId":null,"code":"OB%s","name":"Other bank %s","intendedUses":["ACADEMIC"],
                 "policy":{"approvalPolicy":"STANDARD","requiredReviews":1,"approverDistinct":false}}""".formatted(rnd(), rnd())), otherAdmin, status().isCreated())
                .path("id").asString();
        String foreign = upload(UUID.fromString(otherBank), 60, otherAdmin);
        List<String> answers = new ArrayList<>();
        for (String asset : List.of(unlinked, UUID.randomUUID().toString(), foreign)) {
            answers.add(problem(as(get(image(attempt, asset)), asha).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString()));
        }
        assertThat(answers).containsOnly(answers.getFirst());                                  // one answer for every reason
    }

    /** The parts of a problem response that could tell reasons apart (not the request path or trace id). */
    private static String problem(String body) {
        var p = JSON.readTree(body);
        return p.path("status").asInt() + " " + p.path("code").asString() + " " + p.path("detail").asString();
    }

    @Test
    void anotherOrganizationsPortalAnotherRealmsTokenAndNoTokenAreRefused() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "secreg-img2");
        String otherKey = "si-" + rnd();
        ownerJdbc().sql("INSERT INTO exam_portal (id, tenant_id, portal_key, name, mfa_policy, self_registration, created_by) VALUES (?, ?, ?, 'Other', 'OFF', true, ?)")
                .params(UUID.randomUUID(), other.tenant, otherKey, UUID.randomUUID()).update();
        mvc.perform(get("/api/v1/delivery/portals/" + otherKey + "/me").header(HttpHeaders.AUTHORIZATION, asha.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/delivery/portals/" + otherKey + "/attempts/" + attempt + "/images/" + inPaper.get(0)).header(HttpHeaders.AUTHORIZATION, asha.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get(image(attempt, inPaper.get(0))).header(HttpHeaders.AUTHORIZATION, controller.bearer())).andExpect(status().isUnauthorized());
        mvc.perform(get(image(attempt, inPaper.get(0)))).andExpect(status().isUnauthorized());
        // staff have no image route through delivery either: the staff asset endpoint does not accept a candidate token
        mvc.perform(get("/api/v1/question-assets/" + inPaper.get(0) + "/content").header(HttpHeaders.AUTHORIZATION, asha.bearer()))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 404));
    }

    @Test
    void imagesCloseWithTheAttempt() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        as(get(image(attempt, inPaper.get(0))), asha).andExpect(status().isOk());
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), asha, 200);
        for (String asset : inPaper) as(get(image(attempt, asset)), asha).andExpect(status().isNotFound());
        // also after time-up (automatic submission)
        UUID late = startAttempt(bilal, codes.get(1));
        as(get(image(late, inPaper.get(2))), bilal).andExpect(status().isOk());
        expireByRecreating(late);
        attempts.autoSubmitDue();
        as(get(image(late, inPaper.get(2))), bilal).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private String image(UUID attempt, String asset) {
        return portal("/attempts/" + attempt + "/images/" + asset);
    }

    private String upload(UUID bank, int width, TestOrg.Account by) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        return JSON.readTree(mvc.perform(multipart("/api/v1/question-banks/" + bank + "/assets")
                        .file(new MockMultipartFile("file", "figure.png", "image/png", png.toByteArray())).param("altText", "Figure")
                        .header(HttpHeaders.AUTHORIZATION, by.bearer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asString();
    }

    private static String imageMcq(String stem, String statementAsset, String optionAsset) {
        String image = "{\"format\":\"ivorovi.content.v1\",\"blocks\":[{\"type\":\"paragraph\",\"runs\":[{\"text\":\"" + stem
                + "\"}]},{\"type\":\"image\",\"asset\":\"" + statementAsset + "\",\"alt\":\"A labelled triangle\"}]}";
        String option = "{\"format\":\"ivorovi.content.v1\",\"blocks\":[{\"type\":\"image\",\"asset\":\"" + optionAsset + "\",\"alt\":\"Right angle\"}]}";
        return """
                {"type":"SINGLE_CHOICE","content":%s,"body":{"options":[{"id":"A","content":%s},{"id":"B","content":%s}]},
                 "answerKey":{"correct":"B"},"marks":2}""".formatted(image, content("No right angle"), option);
    }
}
