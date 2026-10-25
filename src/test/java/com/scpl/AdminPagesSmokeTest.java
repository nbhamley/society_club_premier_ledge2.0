package com.scpl;

import com.scpl.model.UploadBatch;
import com.scpl.model.VendorBill;
import com.scpl.repo.InvoiceRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.UploadBatchRepository;
import com.scpl.repo.VendorBillRepository;
import com.scpl.repo.VendorContactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders every page of the application with real seeded data. A broken Thymeleaf expression, a missing model attribute
 * or a lazy-loading error in any template fails here instead of in a browser. Also acceptance 15 (vendors, bills, wa.me).
 */
class AdminPagesSmokeTest extends IntegrationTestBase {

    @Autowired
    SocietyRepository societies;
    @Autowired
    VendorContactRepository vendors;
    @Autowired
    VendorBillRepository bills;
    @Autowired
    InvoiceRepository invoices;
    @Autowired
    UploadBatchRepository batches;

    private Long gv() {
        return societies.findBySlug("green-valley").orElseThrow().getId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/society-admin", "/society-admin/approvals", "/society-admin/vendors", "/society-admin/bills",
            "/society-admin/import", "/society-admin/backup", "/society-admin/billing", "/society-admin/contacts",
            "/dashboard", "/profile", "/notifications", "/me/player", "/me/team", "/me/auction"})
    void greenValleyAdminPagesRender(String path) throws Exception {
        mvc.perform(get(path).with(as("gv.admin1"))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/dashboard", "/profile", "/notifications", "/me/player", "/me/team", "/me/auction"})
    void playerPagesRender(String path) throws Exception {
        mvc.perform(get(path).with(as("gv.player1"))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/society-admin", "/society-admin/approvals", "/society-admin/import", "/society-admin/backup",
            "/society-admin/billing", "/society-admin/contacts", "/me/player"})
    void trialSocietyAdminPagesRender(String path) throws Exception {
        mvc.perform(get(path).with(as("sh.admin1"))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/platform-admin", "/platform-admin/societies", "/platform-admin/subscriptions",
            "/platform-admin/events", "/platform-admin/audit", "/profile", "/notifications"})
    void platformAdminPagesRender(String path) throws Exception {
        mvc.perform(get(path).with(as("platformadmin"))).andExpect(status().isOk());
    }

    @Test
    void importPreviewAndDownloadsWork_andAreScopedToTheSociety() throws Exception {
        UploadBatch batch = batches.findBySociety_IdOrderByCreatedAtDesc(gv()).get(0);
        mvc.perform(get("/society-admin/import").param("batch", String.valueOf(batch.getId())).with(as("gv.admin1")))
                .andExpect(status().isOk());
        String csv = mvc.perform(get("/society-admin/import/" + batch.getId() + "/review").with(as("gv.admin1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Kind").contains("SourceFile").contains("Conflict");
        mvc.perform(get("/society-admin/import/" + batch.getId() + "/review").with(as("sh.admin1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invoicePdfDownloads() throws Exception {
        Long invoiceId = invoices.findBySociety_IdOrderByIssuedAtDesc(gv()).get(0).getId();
        byte[] pdf = mvc.perform(get("/society-admin/billing/invoices/" + invoiceId + "/pdf").with(as("gv.admin1")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        mvc.perform(get("/society-admin/billing/invoices/" + invoiceId + "/pdf").with(as("sh.admin1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void vendorsShowWhatsAppLinksAndBillsCanBeCreatedChangedAndDeleted() throws Exception {
        String vendorsHtml = mvc.perform(get("/society-admin/vendors").with(as("gv.admin1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(vendorsHtml).contains("https://wa.me/91").contains("reaching%20out%20from%20Green%20Valley");

        Long vendorId = vendors.findBySociety_IdOrderByNameAsc(gv()).get(0).getId();
        mvc.perform(post("/society-admin/bills").param("vendorId", String.valueOf(vendorId)).param("title", "Smoke test bill")
                .param("amount", "1234").with(csrf()).with(as("gv.admin1"))).andExpect(status().is3xxRedirection());

        VendorBill bill = bills.findBySociety_IdOrderByBillDateDesc(gv()).stream()
                .filter(b -> "Smoke test bill".equals(b.getTitle())).findFirst().orElseThrow();
        assertThat(mvc.perform(get("/society-admin/bills").with(as("gv.admin1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("Smoke test bill");

        mvc.perform(post("/society-admin/bills/" + bill.getId() + "/status").param("status", "PAID")
                .with(csrf()).with(as("gv.admin1"))).andExpect(status().is3xxRedirection());
        assertThat(bills.findById(bill.getId()).orElseThrow().getStatus().name()).isEqualTo("PAID");

        // another society's admin cannot touch it
        mvc.perform(post("/society-admin/bills/" + bill.getId() + "/delete").with(csrf()).with(as("sh.admin1")))
                .andExpect(status().isForbidden());   // Sunrise Heights has no vendor module on the trial
        mvc.perform(post("/society-admin/bills/" + bill.getId() + "/delete").with(csrf()).with(as("gv.admin1")))
                .andExpect(status().is3xxRedirection());
        assertThat(bills.findById(bill.getId())).isEmpty();
    }
}