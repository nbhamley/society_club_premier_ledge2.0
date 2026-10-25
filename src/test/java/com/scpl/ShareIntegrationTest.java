package com.scpl;

import com.scpl.model.Player;
import com.scpl.model.User;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SocietyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Acceptance 16: share links, ownership rule, the Instagram/Facebook modal, the public landing page. */
class ShareIntegrationTest extends IntegrationTestBase {

    @Autowired
    PlayerRepository players;
    @Autowired
    SocietyRepository societies;
    @Autowired
    com.scpl.repo.UserRepository users;

    private Long ownPlayerId() {
        User u = users.findByUsernameIgnoreCase("gv.player1").orElseThrow();
        return u.getPlayerId();
    }

    private static ResultActions json(ResultActions a) {
        return a;
    }

    @Test
    void aPlayerCanPreviewAndShareTheirOwnProfileToWhatsApp() throws Exception {
        Long id = ownPlayerId();
        mvc.perform(get("/share/player/" + id + "/preview").param("platform", "WHATSAPP")
                        .header("X-Requested-With", "XMLHttpRequest").with(as("gv.player1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation", containsString("This share is public")));

        String response = mvc.perform(post("/share/player/" + id).param("platform", "WHATSAPP")
                        .header("X-Requested-With", "XMLHttpRequest").with(csrf()).with(as("gv.player1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.modal").value(false))
                .andExpect(jsonPath("$.outboundUrl", startsWith("https://wa.me/?text=")))
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(response, "$.token");

        // the deep link is public and shows no personal details
        Player p = players.findById(id).orElseThrow();
        String landing = mvc.perform(get("/share/" + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(landing).contains(p.getName()).doesNotContain("greenvalley.example").doesNotContain("98220");
    }

    @Test
    void instagramAndFacebookUseTheModalFlow() throws Exception {
        mvc.perform(post("/share/player/" + ownPlayerId()).param("platform", "INSTAGRAM")
                        .header("X-Requested-With", "XMLHttpRequest").with(csrf()).with(as("gv.player1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.modal").value(true));
    }

    @Test
    void youCannotShareSomeoneElsesProfileOrAnotherSocietysPlayer() throws Exception {
        Long gv = societies.findBySlug("green-valley").orElseThrow().getId();
        Long sh = societies.findBySlug("sunrise-heights").orElseThrow().getId();
        Long own = ownPlayerId();
        Long teammate = players.findBySociety_IdOrderByNameAsc(gv).stream().map(Player::getId)
                .filter(i -> !i.equals(own)).findFirst().orElseThrow();
        Long foreign = players.findBySociety_IdOrderByNameAsc(sh).get(0).getId();

        mvc.perform(post("/share/player/" + teammate).param("platform", "WHATSAPP")
                        .header("X-Requested-With", "XMLHttpRequest").with(csrf()).with(as("gv.player1")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").isNotEmpty());
        mvc.perform(post("/share/player/" + foreign).param("platform", "WHATSAPP")
                        .header("X-Requested-With", "XMLHttpRequest").with(csrf()).with(as("gv.player1")))
                .andExpect(status().isNotFound());
    }
}