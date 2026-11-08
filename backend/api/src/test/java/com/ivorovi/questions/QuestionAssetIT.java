package com.ivorovi.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.questions.internal.AssetService;
import com.ivorovi.support.FakeMalwareScanner;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Secure images (demo item 4): magic-byte type check, size and dimension limits, quarantine and malware scan before
 * availability (infected, scanner error, scanner unavailable — fail closed), re-encoding without metadata, per-question
 * total, institution quotas, access-checked serving with hardened headers, and safe 30-day cleanup.
 */
class QuestionAssetIT extends QuestionTestSupport {

    @Autowired FakeMalwareScanner scanner;
    @Autowired AssetService assets;

    private UUID bank;

    @BeforeEach
    void setUp() throws Exception {
        setUpOrg("qimg");
        bank = standardBank();
    }

    @AfterEach
    void scannerBack() {
        scanner.available = true;
    }

    static byte[] png(int w, int h, String textChunk, byte[] trailing) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ImageWriter wr = ImageIO.getImageWritersByFormatName("png").next();
        IIOMetadata meta = wr.getDefaultImageMetadata(new ImageTypeSpecifier(img), null);
        if (textChunk != null) {
            IIOMetadataNode text = new IIOMetadataNode("tEXt");
            IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "Comment");
            entry.setAttribute("value", textChunk);
            text.appendChild(entry);
            IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
            root.appendChild(text);
            meta.mergeTree("javax_imageio_png_1.0", root);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            wr.setOutput(ios);
            wr.write(new IIOImage(img, null, meta));
        }
        if (trailing != null) out.write(trailing);
        return out.toByteArray();
    }

    private ResultActions upload(TestOrg.Account who, UUID bankId, String name, byte[] bytes, String contentType) throws Exception {
        return mvc.perform(multipart("/api/v1/question-banks/" + bankId + "/assets").file(new MockMultipartFile("file", name, contentType, bytes))
                .param("altText", "Diagram").header(HttpHeaders.AUTHORIZATION, who.bearer()));
    }

    private JsonNode uploaded(byte[] bytes) throws Exception {
        return JSON.readTree(upload(faculty, bank, "figure.png", bytes, "image/png").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String withImage(UUID asset, String stem) {
        return """
                {"type":"TRUE_FALSE","content":{"format":"ivorovi.content.v1","blocks":[{"type":"paragraph","runs":[{"text":"%s"}]},
                  {"type":"image","asset":"%s","alt":"A labelled right triangle"}]},"body":{},"answerKey":{"value":true}}""".formatted(stem, asset);
    }

    @Test
    void cleanImagesAreReencodedWithoutMetadataAndServedWithHardenedHeaders() throws Exception {
        byte[] original = png(64, 48, "GPS 12.97N 77.59E secret-location", null);
        JsonNode a = uploaded(original);
        assertThat(a.path("scanStatus").asString()).isEqualTo("CLEAN");
        assertThat(a.path("width").asInt()).isEqualTo(64);
        UUID id = UUID.fromString(a.path("id").asString());
        byte[] served = perform(get("/api/v1/question-assets/" + id + "/content"), designer).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain("secret-location");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(served)).getWidth()).isEqualTo(64);
        // the stored bytes are the re-encoded ones; the original stays only in quarantine
        assertThat(ownerJdbc().sql("SELECT served_key LIKE '%/question-assets/' || bank_id || '/%' AND quarantine_key LIKE '%/quarantine/%' FROM question_asset WHERE id = ?")
                .param(id).query(Boolean.class).single()).isTrue();
        assertThat(audits("question.asset.uploaded")).isEqualTo(1);
    }

    @Test
    void onlyRealPngAndJpegWithinLimitsAreAccepted() throws Exception {
        upload(faculty, bank, "x.png", "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8), "image/png")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unsupported_image"));
        upload(faculty, bank, "x.svg", "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8), "image/svg+xml")
                .andExpect(status().isBadRequest());
        upload(faculty, bank, "x.gif", "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".getBytes(StandardCharsets.ISO_8859_1), "image/gif").andExpect(status().isBadRequest());
        upload(faculty, bank, "x.png", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8), "image/png").andExpect(status().isBadRequest());
        upload(faculty, bank, "wide.png", png(4097, 2, null, null), "image/png").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("image_too_large"));
        byte[] big = new byte[(int) AssetService.MAX_BYTES + 1];
        System.arraycopy(png(4, 4, null, null), 0, big, 0, 60);
        upload(faculty, bank, "big.png", big, "image/png").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("image_too_large"));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM question_asset WHERE tenant_id = ?").param(org.tenant).query(Long.class).single()).isZero();
    }

    @Test
    void infectedImagesAreQuarantinedNeverServedAndBlockSubmission() throws Exception {
        JsonNode a = uploaded(png(8, 8, null, FakeMalwareScanner.EICAR.getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(a.path("scanStatus").asString()).isEqualTo("INFECTED");
        UUID id = UUID.fromString(a.path("id").asString());
        assertThat(ownerJdbc().sql("SELECT served_key FROM question_asset WHERE id = ?").param(id).query(String.class).optional().orElse(null)).isNull();
        perform(get("/api/v1/question-assets/" + id + "/content"), faculty).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("image_not_available"));
        assertThat(audits("question.asset.infected")).isEqualTo(1);
        UUID q = create(bank, faculty, withImage(id, "Uses an infected image"));
        perform(post("/api/v1/questions/" + q + "/submit").content("{}"), faculty)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("images_not_scanned"));
    }

    @Test
    void scannerFailureAndOutageFailClosed() throws Exception {
        JsonNode err = uploaded(png(8, 8, null, "SCANNER-ERROR".getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(err.path("scanStatus").asString()).isEqualTo("ERROR");
        perform(get("/api/v1/question-assets/" + err.path("id").asString() + "/content"), faculty).andExpect(status().isConflict());
        scanner.available = false;
        JsonNode down = uploaded(png(8, 8, null, null));
        assertThat(down.path("scanStatus").asString()).isEqualTo("ERROR");
        perform(get("/api/v1/question-assets/" + down.path("id").asString() + "/content"), faculty).andExpect(status().isConflict());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM question_asset WHERE tenant_id = ? AND served_key IS NOT NULL").param(org.tenant).query(Long.class).single())
                .isZero();
        // the database refuses a served copy for an unscanned image
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE question_asset SET served_key = 'x' WHERE id = ?").param(UUID.fromString(down.path("id").asString())).update())
                .isInstanceOf(Exception.class);
    }

    @Test
    void imagesAreReadableOnlyByReadersOfTheirBank() throws Exception {
        UUID id = UUID.fromString(uploaded(png(8, 8, null, null)).path("id").asString());
        String path = "/api/v1/question-assets/" + id + "/content";
        perform(get(path), faculty2).andExpect(status().isOk());
        perform(get(path), otherFaculty).andExpect(status().isNotFound());
        perform(get(path), otherController).andExpect(status().isNotFound());
        perform(get(path), student).andExpect(status().isForbidden());
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "qimg-other");
        perform(get(path), other.account("ORG_ADMIN", null)).andExpect(status().isNotFound());
        assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.candidate("c-" + UUID.randomUUID(), "c@x.test")))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(audits("question.asset.view_denied")).isEqualTo(2);
        // uploads need authoring permission in that bank
        upload(otherFaculty, bank, "f.png", png(4, 4, null, null), "image/png").andExpect(status().isNotFound());
        upload(student, bank, "f.png", png(4, 4, null, null), "image/png").andExpect(status().isForbidden());
        // an image of another bank cannot be placed in a question
        UUID bank2 = standardBank();
        perform(post("/api/v1/question-banks/" + bank2 + "/questions")
                .content(withImage(id, "Borrowed image")), faculty).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_asset"));
    }

    @Test
    void perQuestionTotalAndInstitutionQuotaAreEnforced() throws Exception {
        // four 5 MB images (rows created directly: generating real 5 MB images is slow) exceed 15 MB per question
        StringBuilder blocks = new StringBuilder("{\"type\":\"paragraph\",\"runs\":[{\"text\":\"Many images\"}]}");
        for (int i = 0; i < 4; i++) {
            UUID a = UUID.randomUUID();
            ownerJdbc().sql("""
                    INSERT INTO question_asset (id, tenant_id, bank_id, sha256, media_type, width, height, bytes, scan_status, quarantine_key, served_key, uploaded_by)
                    VALUES (?, ?, ?, repeat('a', 64), 'image/png', 10, 10, 5242880, 'CLEAN', 'q', 's', ?)""").params(a, org.tenant, bank, faculty.id()).update();
            blocks.append(",{\"type\":\"image\",\"asset\":\"").append(a).append("\",\"decorative\":true}");
        }
        perform(post("/api/v1/question-banks/" + bank + "/questions").content("""
                {"type":"TRUE_FALSE","content":{"format":"ivorovi.content.v1","blocks":[%s]},"body":{},"answerKey":{"value":true}}""".formatted(blocks)), faculty)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("images_too_large"));
        // the institution quota (20 MB already used) refuses further uploads once set below usage
        perform(put("/api/v1/question-asset-quotas?orgUnitId=" + college)
                .content("{\"limitBytes\":20971520}"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.usedBytes").value(20971520));
        upload(faculty, bank, "f.png", png(4, 4, null, null), "image/png").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("asset_quota_exceeded"));
        // the other institution has its own (default) quota
        UUID otherBank = bank(admin, otherCollege, "STANDARD", 1, false, "\"PRACTICE\"");
        upload(otherFaculty, otherBank, "f.png", png(4, 4, null, null), "image/png").andExpect(status().isCreated());
        // quotas are managed with bank.manage only
        perform(put("/api/v1/question-asset-quotas?orgUnitId=" + college)
                .content("{\"limitBytes\":20971520}"), faculty).andExpect(status().isForbidden());
    }

    @Test
    void cleanupRemovesOnlyImagesNoVersionUses() throws Exception {
        UUID unused = UUID.fromString(uploaded(png(8, 8, null, null)).path("id").asString());
        UUID used = UUID.fromString(uploaded(png(9, 9, null, null)).path("id").asString());
        UUID inApproved = UUID.fromString(uploaded(png(10, 10, null, null)).path("id").asString());
        create(bank, faculty, withImage(used, "Draft with image"));
        UUID q = approved(bank, faculty, designer, controller, withImage(inApproved, "Approved with image"));
        // a new version that drops the image: the approved version still links it
        perform(post("/api/v1/questions/" + q + "/versions"), faculty).andExpect(status().isCreated());
        update(q, faculty, "{\"type\":\"TRUE_FALSE\",\"content\":" + content("No image now") + ",\"body\":{},\"answerKey\":{\"value\":true}}").andExpect(status().isOk());
        ownerJdbc().sql("UPDATE question_asset SET created_at = now() - interval '31 days' WHERE tenant_id = ?").param(org.tenant).update();
        int n = TenantContext.callInTenant(org.tenant, () -> assets.cleanup(Instant.now().minus(Duration.ofDays(30))));
        assertThat(n).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT id FROM question_asset WHERE tenant_id = ? ORDER BY id").param(org.tenant).query(UUID.class).list())
                .containsExactlyInAnyOrder(used, inApproved).doesNotContain(unused);
        assertThat(audits("question.asset.purged")).isEqualTo(1);
        // a linked image can never be deleted, even directly
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM question_asset WHERE id = ?").param(inApproved).update()).hasMessageContaining("used by a question version");
        perform(get("/api/v1/question-assets/" + inApproved + "/content"), designer).andExpect(status().isOk());
    }
}
