package com.scpl.dto;

/**
 * Returned to the inviting admin once. Only a hash is stored (in the approval payload), so
 * the temporary password cannot be shown again.
 */
public record InviteTicket(Long approvalId, String temporaryPassword) {
}