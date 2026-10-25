package com.scpl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scpl.model.AuditLog;
import com.scpl.model.User;
import com.scpl.repo.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes join the caller's transaction (REQUIRED), so a rolled-back action never leaves
 * an audit row claiming it happened. Callers must pass non-PII snapshots (ids, names,
 * statuses) because platform admins can read these logs.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MAX_USER_AGENT = 300;

    private final AuditLogRepository logs;
    private final ObjectMapper mapper;

    @Transactional
    public AuditLog record(User actor, Long societyId, String action, String entityType, Long entityId,
                           Object oldValue, Object newValue) {
        AuditLog row = new AuditLog();
        row.setUser(actor);
        row.setSocietyId(societyId);
        row.setAction(action);
        row.setEntityType(entityType);
        row.setEntityId(entityId);
        row.setOldValue(toJson(oldValue));
        row.setNewValue(toJson(newValue));

        HttpServletRequest request = currentRequest();
        if (request != null) {
            // getRemoteAddr is the TCP peer. Behind a proxy, enable server.forward-headers-strategy
            // rather than trusting a client-supplied X-Forwarded-For header here.
            row.setIpAddress(request.getRemoteAddr());
            String ua = request.getHeader("User-Agent");
            row.setUserAgent(ua != null && ua.length() > MAX_USER_AGENT ? ua.substring(0, MAX_USER_AGENT) : ua);
        }
        return logs.save(row);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> forSociety(Long societyId, Pageable pageable) {
        return logs.findBySocietyIdOrderByTimestampDesc(societyId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> all(Pageable pageable) {
        return logs.findAllByOrderByTimestampDesc(pageable);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // An unserialisable snapshot must not block the audited action itself.
            return String.valueOf(value);
        }
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }
}