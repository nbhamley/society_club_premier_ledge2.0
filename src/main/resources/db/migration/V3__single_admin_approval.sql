-- Only ADMIN_INVITE keeps a multi-admin threshold; every other approval type now needs one approval.
-- Move requests that are still waiting onto the new rule. Decided requests keep their recorded threshold.
-- A pending request that already has an approval is decided by the next approval from another admin.
UPDATE admin_approvals
SET approvals_required = 1
WHERE status = 'PENDING'
  AND entity_type <> 'ADMIN_INVITE'
  AND approvals_required > 1;
