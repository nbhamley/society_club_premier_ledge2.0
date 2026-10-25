package com.scpl.service;

import com.scpl.dto.ImportRows.SegregationInput;
import com.scpl.dto.ImportRows.SegregationResult;

/**
 * Turns an uploaded file into proposed rows (§9). Two implementations: RuleBasedSegregationProvider
 * (always present, header heuristics) and LocalAiSegregationProvider (registered, and marked primary,
 * only when feature.ai.enabled=true; it falls back to the rule-based one on any failure).
 *
 * <p>Providers only propose. They never touch the database, and everything they return is validated and
 * conflict-checked by ImportService and approved by the admin team before anything is inserted.
 */
public interface DataSegregationProvider {

    SegregationResult segregate(SegregationInput input);
}