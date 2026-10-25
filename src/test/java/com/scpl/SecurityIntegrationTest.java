package com.scpl;

import com.scpl.model.Player;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Acceptance 2, 3, 4, 5: public pages, tenant isolation, role walls, PII, throttle, security headers. */
class SecurityIntegrationTest extends IntegrationTestBase {

    @Autowired
    SocietyRepository societies;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    PlayerRepository players;
    @Autowired
    MatchRepository matches;

    private Long societyId(String slug) {
        return societies.findBySlug(slug).orElseThrow().getId();
    }

    private static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ public

    @ParameterizedTest
    @ValueSource(strings = {"/", "/about", "/subscribe", "/login", "/society/green-valley", "/society/green-valley/matches",
            "/society/green-valley/seasons", "/society/green-valley/players", "/society/green-valley/register",
            "/society/sunrise-heights"})
    void publicPagesRenderForAnonymousVisitors(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void platformHomeIsNotASocietyPage() throws Exception {
        String html = body(mvc.perform(get("/")).andExpect(status().isOk()));
        assertThat(html).contains("Subscribe your society").contains("Green Valley").contains("Sunrise Heights");
    }

    @Test
    void detailPagesRenderAndRefuseForeignIds() throws Exception {
        Long gv = societyId("green-valley");
        Long sh = societyId("sunrise-heights");
        Long gvSeason = seasons.findBySociety_IdOrderByCreatedAtDesc(gv).get(0).getId();
        Long shSeason = seasons.findBySociety_IdOrderByCreatedAtDesc(sh).get(0).getId();
        Long gvMatch = matches.findBySociety_IdOrderByMatchDateAsc(gv).get(0).getId();
        Long gvPlayer = players.findBySociety_IdOrderByNameAsc(gv).get(0).getId();

        mvc.perform(get("/society/green-valley/seasons/" + gvSeason)).andExpect(status().isOk());
        mvc.perform(get("/matches/" + gvMatch)).andExpect(status().isOk());
        mvc.perform(get("/society/green-valley/player/" + gvPlayer)).andExpect(status().isOk());

        // an id that belongs to another society is a 404 under this society's URL
        mvc.perform(get("/society/green-valley/seasons/" + shSeason)).andExpect(status().isNotFound());
        mvc.perform(get("/society/sunrise-heights/player/" + gvPlayer)).andExpect(status().isNotFound());
        mvc.perform(get("/society/does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void publicPlayerPagesNeverShowPersonalDetails_evenToTheSocietysOwnAdmin() throws Exception {
        Long gv = societyId("green-valley");
        Player first = players.findBySociety_IdOrderByNameAsc(gv).get(0);
        for (String path : new String[]{"/society/green-valley/players", "/society/green-valley/player/" + first.getId()}) {
            String anonymous = body(mvc.perform(get(path)).andExpect(status().isOk()));
            assertThat(anonymous).doesNotContain("greenvalley.example").doesNotContain("98220");
            String asAdmin = body(mvc.perform(get(path).with(as("gv.admin1"))).andExpect(status().isOk()));
            assertThat(asAdmin).doesNotContain("greenvalley.example").doesNotContain("98220");
        }
    }

    @Test
    void securityHeadersAreSent() throws Exception {
        mvc.perform(get("/"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("object-src 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    // ------------------------------------------------------------------ tenant isolation and roles

    @Test
    void anonymousVisitorsAreSentToTheLoginPage() throws Exception {
        mvc.perform(get("/society-admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection());
    }

    @Test
    void societyAdminSeesOnlyTheirOwnSociety() throws Exception {
        String html = body(mvc.perform(get("/society-admin").with(as("gv.admin1"))).andExpect(status().isOk()));
        assertThat(html).contains("Green Valley").contains("greenvalley.example")   // admins do see their own players' details
                .doesNotContain("Sunrise Heights").doesNotContain("sunriseheights.example");
    }

    @Test
    void openingAnotherSocietysConsoleIsForbidden() throws Exception {
        Long sh = societyId("sunrise-heights");
        mvc.perform(get("/society-admin").param("societyId", String.valueOf(sh)).with(as("gv.admin1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aForgedEntityIdFromAnotherSocietyIsNotFound() throws Exception {
        Long shSeason = seasons.findBySociety_IdOrderByCreatedAtDesc(societyId("sunrise-heights")).get(0).getId();
        mvc.perform(post("/society-admin/seasons/" + shSeason + "/status").param("status", "COMPLETED")
                .with(csrf()).with(as("gv.admin1"))).andExpect(status().isNotFound());
    }

    @Test
    void roleWallsHold() throws Exception {
        mvc.perform(get("/society-admin").with(as("gv.player1"))).andExpect(status().isForbidden());
        mvc.perform(get("/society-admin").with(as("platformadmin"))).andExpect(status().isForbidden());
        mvc.perform(get("/platform-admin").with(as("gv.admin1"))).andExpect(status().isForbidden());
        mvc.perform(get("/platform-admin").with(as("gv.player1"))).andExpect(status().isForbidden());
    }

    @Test
    void platformAdminSeesTotalsButNoPlayerDetails() throws Exception {
        String html = body(mvc.perform(get("/platform-admin").with(as("platformadmin"))).andExpect(status().isOk()));
        assertThat(html).contains("Platform admin").doesNotContain("greenvalley.example").doesNotContain("sunriseheights.example");
    }

    @Test
    void crossSocietyTournamentsAreInvisibleWhileTheFlagIsOff() throws Exception {
        mvc.perform(get("/platform-admin/tournaments").with(as("platformadmin"))).andExpect(status().isNotFound());
    }

    @Test
    void dashboardRoutesByRole() throws Exception {
        mvc.perform(get("/dashboard").with(as("gv.admin1"))).andExpect(status().isOk());
        mvc.perform(get("/dashboard").with(as("platformadmin"))).andExpect(redirectedUrl("/platform-admin"));
    }

    @Test
    void theOldAdminRoutesRedirectToSocietyAdmin() throws Exception {
        mvc.perform(get("/admin/seasons").with(as("gv.admin1")))
                .andExpect(status().isMovedPermanently()).andExpect(header().string("Location", "/society-admin/seasons"));
        mvc.perform(post("/admin/seasons").with(csrf()).with(as("gv.admin1")))
                .andExpect(status().isPermanentRedirect()).andExpect(header().string("Location", "/society-admin/seasons"));
    }

    // ------------------------------------------------------------------ login

    @Test
    void signingInWorks() throws Exception {
        mvc.perform(post("/login").param("username", "gv.admin1").param("password", "Passw0rd!").with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    void fiveWrongPasswordsLockTheLoginForThatUser() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").param("username", "throttle.victim").param("password", "wrong-" + i).with(csrf()))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").param("username", "throttle.victim").param("password", "anything").with(csrf()))
                .andExpect(redirectedUrl("/login?locked"));
    }
}