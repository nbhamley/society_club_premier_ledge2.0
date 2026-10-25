package com.scpl.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structure guard for §14: the models that public pages, the platform console and cross-society rosters render must have
 * no field that could hold personal details or private money figures. If this fails, someone added such a field.
 */
class PrivacyBoundaryTest {

    private static final Set<String> PERSONAL = Set.of("email", "phone", "dateofbirth", "address", "flatnumber", "whatsappnumber", "passwordhash");

    private static List<String> componentNames(Class<?> holder) {
        List<String> names = new ArrayList<>();
        List<Class<?>> records = new ArrayList<>();
        if (holder.isRecord()) {
            records.add(holder);
        }
        records.addAll(Arrays.stream(holder.getDeclaredClasses()).filter(Class::isRecord).toList());
        for (Class<?> r : records) {
            for (RecordComponent c : r.getRecordComponents()) {
                names.add(r.getSimpleName() + "." + c.getName());
            }
        }
        return names;
    }

    private static void assertNoPersonalFields(Class<?> holder) {
        List<String> offending = componentNames(holder).stream()
                .filter(n -> PERSONAL.contains(n.substring(n.indexOf('.') + 1).toLowerCase(Locale.ROOT))).toList();
        assertThat(offending).as("personal fields on %s", holder.getSimpleName()).isEmpty();
    }

    @Test
    void publicAndCrossSocietyModelsCarryNoPersonalDetails() {
        assertNoPersonalFields(PublicPlayerView.class);
        assertNoPersonalFields(PublicViews.class);
        assertNoPersonalFields(PlatformViews.class);
        assertNoPersonalFields(TournamentViews.class);
        assertNoPersonalFields(ShareViews.class);
        assertThat(componentNames(PublicPlayerView.class)).isNotEmpty();
        assertThat(componentNames(PublicViews.class)).isNotEmpty();
    }

    @Test
    void publicModelsCarryNoPrivateMoneyFigures() {
        List<String> names = componentNames(PublicViews.class).stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        assertThat(names).noneMatch(n -> n.startsWith("sponsorview.") && n.endsWith(".amount"));
        assertThat(names).noneMatch(n -> n.endsWith(".plannedbudget") || n.endsWith(".teampurse") || n.endsWith(".purseremaining"));
    }

    @Test
    void thePlayerProfileModelIsTheOnlyOnePairingPersonalFieldsWithAFlag() {
        assertThat(componentNames(PlayerProfileView.class)).contains("PlayerProfileView.piiVisible", "PlayerProfileView.email");
    }
}