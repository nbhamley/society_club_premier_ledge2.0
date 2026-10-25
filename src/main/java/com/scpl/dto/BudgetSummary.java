package com.scpl.dto;

import com.scpl.model.VendorCategory;

import java.util.List;

/** Budget dashboard model (§11). Every category appears, even at zero, so the chart axes stay stable. */
public record BudgetSummary(Long seasonId, String seasonName, double plannedBudget, double totalSpend,
                            double paid, double pending, double disputed, double remaining,
                            List<CategoryLine> categories) {

    public record CategoryLine(VendorCategory category, String label, double total, double sharePercent) {
    }
}