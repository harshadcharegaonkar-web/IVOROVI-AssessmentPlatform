package com.ivorovi.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.TestOrg;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * KI-75: question search through the indexed {@code question_search} function. Same results and order as before
 * (filters, full text, substring with escaping, paging, approved-only), and the function applies the RLS tenant rule:
 * nothing for another organization, nothing without a tenant, nothing from another bank, and only ids and a count.
 */
class QuestionSearchIT extends QuestionTestSupport {

    private static final String CALL = "SELECT cardinality(ids) AS n, total FROM question_search(?, false, null, null, null, null, null, null, null, null, null, null, null, ?, 25, 0)";

    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        setUpOrg("qsearch");
    }

    /** {@code query} is a URL-encoded query string; it is decoded and sent as request parameters. */
    private JsonNode search(UUID bank, String query) throws Exception {
        var request = get("/api/v1/question-banks/" + bank + "/questions");
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            request = request.param(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return call(request, faculty, status().isOk());
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("items").forEach(i -> out.add(i.path("id").asString()));
        return out;
    }

    @Test
    void filtersTextPagingAndOrderAreUnchanged() throws Exception {
        UUID bank = standardBank();
        UUID other = standardBank();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) created.add(create(bank, faculty, mcq("Velocity question number " + i, "A")));
        UUID pct = create(bank, faculty, mcq("Discount of 50% applies to item_7", "B"));
        UUID approved = approved(bank, faculty, designer, controller, mcq("Approved momentum statement", "C"));
        create(other, faculty, mcq("Velocity question in another bank", "A"));

        // newest first; total counts the whole bank, never the other bank
        JsonNode first = search(bank, "size=4");
        assertThat(first.path("total").asLong()).isEqualTo(9);
        assertThat(ids(first)).containsExactly(approved.toString(), pct.toString(), created.get(6).toString(), created.get(5).toString());
        assertThat(first.path("items").get(0).path("approvedVersionNo").asInt()).isEqualTo(1);
        assertThat(first.path("items").get(1).path("excerpt").asString()).contains("Discount of 50%");
        JsonNode third = search(bank, "size=4&page=2");
        assertThat(ids(third)).containsExactly(created.get(0).toString());
        assertThat(search(bank, "size=4&page=9").path("items").size()).isZero();
        assertThat(search(bank, "size=4&page=9").path("total").asLong()).isEqualTo(9);
        // paging covers every question exactly once
        List<String> all = new ArrayList<>();
        for (int p = 0; p < 3; p++) all.addAll(ids(search(bank, "size=4&page=" + p)));
        assertThat(all).hasSize(9).doesNotHaveDuplicates();

        // full text and substring
        assertThat(search(bank, "q=velocity").path("total").asLong()).isEqualTo(7);
        assertThat(search(bank, "q=VELOCITY%20question%20number%203").path("total").asLong()).isEqualTo(1);
        assertThat(search(bank, "q=elocit").path("total").asLong()).as("substring").isEqualTo(7);
        assertThat(search(bank, "q=Option%20two").path("total").asLong()).as("option text is searched").isEqualTo(9);
        // LIKE wildcards in the query are literal
        assertThat(ids(search(bank, "q=50%25"))).containsExactly(pct.toString());
        assertThat(ids(search(bank, "q=item_7"))).containsExactly(pct.toString());
        assertThat(search(bank, "q=item_").path("total").asLong()).isEqualTo(1);
        assertThat(search(bank, "q=%25").path("total").asLong()).isEqualTo(1);
        assertThat(search(bank, "q=%20%20").path("total").asLong()).as("blank query means no text filter").isEqualTo(9);
        // answer keys and explanations are never searched
        assertThat(search(bank, "q=Because").path("total").asLong()).isZero();

        // state, type, status and approved-only
        assertThat(ids(search(bank, "state=APPROVED"))).containsExactly(approved.toString());
        assertThat(search(bank, "state=DRAFT").path("total").asLong()).isEqualTo(8);
        assertThat(search(bank, "type=SINGLE_CHOICE").path("total").asLong()).isEqualTo(9);
        assertThat(search(bank, "type=NUMERIC").path("total").asLong()).isZero();
        assertThat(search(bank, "status=RETIRED").path("total").asLong()).isZero();
        assertThat(ids(search(bank, "approvedOnly=true"))).containsExactly(approved.toString());
        assertThat(ids(search(bank, "approvedOnly=true&q=momentum"))).containsExactly(approved.toString());
        assertThat(search(bank, "approvedOnly=true&q=velocity").path("total").asLong()).isZero();
        // a new draft of the approved question: the default search matches the draft, approved-only the approved text
        call(post("/api/v1/questions/" + approved + "/versions"), faculty, status().isCreated());
        update(approved, faculty, mcq("Approved momentum statement, revised wording about impulse", "C")).andExpect(status().isOk());
        assertThat(ids(search(bank, "q=impulse"))).containsExactly(approved.toString());
        assertThat(search(bank, "approvedOnly=true&q=impulse").path("total").asLong()).isZero();
        assertThat(ids(search(bank, "approvedOnly=true&q=momentum"))).containsExactly(approved.toString());
    }

    @Test
    void theSearchFunctionAppliesTheTenantRuleAndReturnsOnlyIds() throws Exception {
        UUID bank = standardBank();
        UUID q = create(bank, faculty, mcq("Isolated searchable photosynthesis", "A"));
        UUID otherBank = standardBank();
        create(otherBank, faculty, mcq("Photosynthesis elsewhere", "A"));
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "qsearch-other");
        TestOrg.Account foreign = other.account("ORG_ADMIN", null);

        // own organization, own bank: the id and count only
        Map<String, Object> own = TenantContext.callInTenant(org.tenant, () -> jdbc.sql("SELECT * FROM question_search(?, false, null, null, null, null,"
                + " null, null, null, null, null, null, null, ?, 25, 0)").params(bank, "photosynthesis").query().singleRow());
        assertThat(own.keySet()).containsExactlyInAnyOrder("ids", "total");
        assertThat(own.get("total")).isEqualTo(1L);
        assertThat(own.get("ids").toString()).isNotNull();
        assertThat(TenantContext.callInTenant(org.tenant, () -> jdbc.sql("SELECT ids[1] FROM question_search(?, false, null, null, null, null, null, null,"
                + " null, null, null, null, null, ?, 25, 0)").params(bank, "photosynthesis").query(UUID.class).single())).isEqualTo(q);
        // another organization (even passing this bank's id), and no tenant at all: nothing
        for (String text : new String[] {"photosynthesis", null}) {
            Map<String, Object> foreignRow = TenantContext.callInTenant(other.tenant, () -> jdbc.sql(CALL).params(bank, text).query().singleRow());
            assertThat(foreignRow.get("n")).isEqualTo(0);
            assertThat(foreignRow.get("total")).isEqualTo(0L);
            Map<String, Object> none = rawAppJdbc().sql(CALL).params(bank, text).query().singleRow();
            assertThat(none.get("n")).isEqualTo(0);
            assertThat(none.get("total")).isEqualTo(0L);
        }
        // bank scope: the other bank's question is not returned for this bank
        assertThat(TenantContext.callInTenant(org.tenant, () -> jdbc.sql(CALL).params(bank, "elsewhere").query().singleRow().get("total"))).isEqualTo(0L);
        // the API stays closed to other organizations and institutions
        perform(get("/api/v1/question-banks/" + bank + "/questions?q=photosynthesis"), foreign).andExpect(status().isNotFound());
        perform(get("/api/v1/question-banks/" + bank + "/questions?q=photosynthesis"), otherFaculty).andExpect(status().isNotFound());
        // the function is executable by the application role only, not by PUBLIC
        assertThat(ownerJdbc().sql("""
                SELECT has_function_privilege('public', 'question_search(uuid, boolean, text, text, text, uuid, uuid, uuid, uuid, uuid, text, text, text, text, integer, integer)', 'EXECUTE')""")
                .query(Boolean.class).single()).isFalse();
    }
}
