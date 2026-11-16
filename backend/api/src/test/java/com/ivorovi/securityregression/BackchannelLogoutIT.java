package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * KI-79 (Day 7): immediate end of candidate sessions through OIDC back-channel logout. A session the identity provider
 * reports as ended is refused at once on every delivery endpoint, before its access token expires; other sessions of the
 * same candidate keep working; only genuine logout tokens of the candidate realm are accepted; nothing is recorded for a
 * refused token; repeated notifications are harmless; the record cannot be rewritten or removed early.
 */
class BackchannelLogoutIT extends DeliveryJourneySupport {

    static final String LOGOUT = "/api/v1/delivery/backchannel-logout";

    Candidate session(Candidate c, String sid) {
        return new Candidate(c.sub(), c.email(), TestTokens.candidate(c.sub(), b -> b.claim("email", c.email()).claim("email_verified", true)
                .claim("name", "Candidate").claim("sid", sid)));
    }

    int notify(String token) throws Exception {
        return mvc.perform(post(LOGOUT).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", token)).andReturn().getResponse().getStatus();
    }

    long ended() {
        return ownerJdbc().sql("SELECT count(*) FROM candidate_ended_session").query(Long.class).single();
    }

    @Test
    void anEndedSessionIsRefusedAtOnceEverywhereAndOtherSessionsKeepWorking() throws Exception {
        String laptop = "sid-laptop-" + UUID.randomUUID();
        String phone = "sid-phone-" + UUID.randomUUID();
        Candidate onLaptop = session(asha, laptop);
        Candidate onPhone = session(asha, phone);
        UUID attempt = startAttempt(onLaptop, codes.get(0));
        delivery(get(portal("/me")), onPhone, 200);

        assertThat(notify(TestTokens.candidateLogout(asha.sub(), laptop, "logout+jwt", true, b -> {}))).isEqualTo(200);
        // the very next request of the ended session is refused, on every kind of endpoint, although its token is still valid
        assertThat(delivery(get(portal("/me")), onLaptop, 401).path("code").asString()).isEqualTo("session_ended");
        delivery(get(portal("/exams")), onLaptop, 401);
        delivery(post(portal("/attempts/" + attempt + "/pulse")), onLaptop, 401);
        delivery(put(portal("/attempts/" + attempt + "/answers/1"))
                .content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), onLaptop, 401);
        // the candidate's other session (and so the attempt and its answers) continue
        delivery(get(portal("/me")), onPhone, 200);
        delivery(put(portal("/attempts/" + attempt + "/answers/1"))
                .content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), onPhone, 200);
        // a repeated notification is harmless (one record)
        assertThat(notify(TestTokens.candidateLogout(asha.sub(), laptop, "logout+jwt", true, b -> {}))).isEqualTo(200);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM candidate_ended_session WHERE sid = ?").param(laptop).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void onlyGenuineLogoutTokensOfTheCandidateRealmAreAcceptedAndRefusalsRecordNothing() throws Exception {
        String sid = "sid-" + UUID.randomUUID();
        Candidate s = session(bilal, sid);
        delivery(get(portal("/me")), s, 200);
        record Bad(String why, String token) {}
        List<Bad> bad = List.of(
                new Bad("untrusted key", TestTokens.candidateLogout(bilal.sub(), sid, "logout+jwt", false, b -> {})),
                new Bad("type JWT", TestTokens.candidateLogout(bilal.sub(), sid, "JWT", true, b -> {})),
                new Bad("no type", TestTokens.candidateLogout(bilal.sub(), sid, null, true, b -> {})),
                new Bad("staff issuer", TestTokens.candidateLogout(bilal.sub(), sid, "logout+jwt", true, b -> b.issuer(TestTokens.ISSUER))),
                new Bad("unknown client", TestTokens.candidateLogout(bilal.sub(), sid, "logout+jwt", true, b -> b.audience(List.of("some-other-client")))),
                new Bad("no logout event", TestTokens.candidateLogout(bilal.sub(), sid, "logout+jwt", true, b -> b.claim("events", java.util.Map.of("x", java.util.Map.of())))),
                new Bad("nonce", TestTokens.candidateLogout(bilal.sub(), sid, "logout+jwt", true, b -> b.claim("nonce", "n"))),
                new Bad("no session id", TestTokens.candidateLogout(bilal.sub(), null, "logout+jwt", true, b -> {})),
                new Bad("stale", TestTokens.candidateLogout(bilal.sub(), sid, "logout+jwt", true,
                        b -> b.issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().plusSeconds(60)))),
                new Bad("expired", TestTokens.candidateLogout(bilal.sub(), sid, "logout+jwt", true,
                        b -> b.issuedAt(Instant.now().minusSeconds(200)).expiresAt(Instant.now().minusSeconds(100)))),
                new Bad("an access token", s.token()),
                new Bad("garbage", "not-a-token"));
        long before = ended();
        for (Bad b : bad) assertThat(notify(b.token())).as(b.why()).isEqualTo(400);
        assertThat(mvc.perform(post(LOGOUT).contentType(MediaType.APPLICATION_FORM_URLENCODED)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(ended()).isEqualTo(before);
        delivery(get(portal("/me")), s, 200);                                                    // the session is untouched
    }

    @Test
    void endedSessionsCannotBeRewrittenOrRemovedEarly() throws Exception {
        String sid = "sid-" + UUID.randomUUID();
        assertThat(notify(TestTokens.candidateLogout(asha.sub(), sid, "logout+jwt", true, b -> {}))).isEqualTo(200);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE candidate_ended_session SET sid = 'other' WHERE sid = ?").param(sid).update())
                .hasMessageContaining("never changes");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM candidate_ended_session WHERE sid = ?").param(sid).update())
                .hasMessageContaining("kept for two days");
    }
}
