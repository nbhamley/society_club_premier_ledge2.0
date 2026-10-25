package com.scpl.model;

/**
 * Marker for entities that belong to exactly one society. TenantGuard and the backup
 * exporter work against this interface so tenant checks are written once, not per entity.
 * Implementations must read the id from the association without initialising a lazy proxy.
 */
public interface SocietyScoped {
    Long getSocietyId();
}