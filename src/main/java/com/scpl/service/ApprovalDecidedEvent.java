package com.scpl.service;

import com.scpl.model.AdminApproval;
import com.scpl.model.User;

/**
 * Published synchronously, inside the deciding transaction, when an approval leaves PENDING.
 * Each domain service listens and acts only on its own entityType. Listeners must check
 * approval.getStatus(): APPROVED means execute, REJECTED or WITHDRAWN means undo any hold.
 */
public record ApprovalDecidedEvent(AdminApproval approval, User decidedBy) {
}