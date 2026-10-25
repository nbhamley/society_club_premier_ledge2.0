package com.scpl.util;

import com.scpl.model.SubscriptionPlan;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MiniPdfAndPlanCatalogTest {

    @Test
    void thePdfIsStructurallyValid_andEscapesText() {
        byte[] bytes = MiniPdf.render("Invoice (INV-1)", List.of("Amount: INR 999.00", "Back\\slash", "caf\u00e9"));
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("%PDF-1.4").contains("%%EOF").contains("\\(INV-1\\)").contains("Back\\\\slash");
        assertThat(text).doesNotContain("\u00e9");

        Matcher m = Pattern.compile("startxref\\n(\\d+)").matcher(text);
        assertThat(m.find()).isTrue();
        assertThat(text.substring(Integer.parseInt(m.group(1)))).startsWith("xref");
    }

    @Test
    void planPricesAndLimitsLiveInOnePlace() {
        PlanCatalog.Limits trial = PlanCatalog.of(SubscriptionPlan.FREE_TRIAL);
        assertThat(trial.monthlyCost()).isZero();
        assertThat(trial.maxTeams()).isEqualTo(2);
        assertThat(trial.maxPlayers()).isEqualTo(20);
        assertThat(trial.vendorModule()).isFalse();

        assertThat(PlanCatalog.of(SubscriptionPlan.BASIC).monthlyCost()).isEqualTo(499.0);
        assertThat(PlanCatalog.of(SubscriptionPlan.BASIC).vendorModule()).isFalse();

        PlanCatalog.Limits standard = PlanCatalog.of(SubscriptionPlan.STANDARD);
        assertThat(standard.monthlyCost()).isEqualTo(999.0);
        assertThat(standard.maxTeams()).isEqualTo(8);
        assertThat(standard.maxPlayers()).isEqualTo(150);
        assertThat(standard.maxConcurrentSeasons()).isEqualTo(2);
        assertThat(standard.vendorModule()).isTrue();
        assertThat(standard.aiImport()).isTrue();
        assertThat(standard.crossSociety()).isFalse();

        PlanCatalog.Limits premium = PlanCatalog.of(SubscriptionPlan.PREMIUM);
        assertThat(premium.monthlyCost()).isEqualTo(1999.0);
        assertThat(premium.maxTeams()).isEqualTo(PlanCatalog.UNLIMITED);
        assertThat(premium.crossSociety()).isTrue();
    }
}