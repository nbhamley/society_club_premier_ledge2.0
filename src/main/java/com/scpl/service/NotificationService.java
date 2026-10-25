package com.scpl.service;

import com.scpl.model.Notification;
import com.scpl.model.PlatformRole;
import com.scpl.model.User;
import com.scpl.repo.NotificationRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

import java.util.List;

/** Stands in for email: writes rows that the admin bell menu and /notifications page display. */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final SocietyRepository societies;

    @Transactional
    public void notifyUser(User recipient, Long societyId, String title, String message, String link) {
        Notification n = new Notification();
        n.setUser(recipient);
        n.setSociety(societyId == null ? null : societies.getReferenceById(societyId));
        n.setTitle(title);
        n.setMessage(message);
        n.setLink(link);
        notifications.save(n);
    }

    /** Every enabled admin of the society, optionally excluding the person who caused the event. */
    @Transactional
    public void notifyAdmins(Long societyId, Long excludeUserId, String title, String message, String link) {
        for (User admin : users.findBySociety_IdAndRoleAndEnabledTrue(societyId, PlatformRole.SOCIETY_ADMIN)) {
            if (excludeUserId == null || !excludeUserId.equals(admin.getId())) {
                notifyUser(admin, societyId, title, message, link);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> recent(Long userId) {
        return notifications.findTop50ByUser_IdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByUser_IdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(Long notificationId, Long userId) {
        notifications.findByIdAndUser_Id(notificationId, userId).ifPresent(n -> n.setRead(true));
    }

    @Transactional
    public void markAllRead(Long userId) {
        notifications.markAllRead(userId);
    }

    /** Housekeeping: removes read notifications older than the cutoff. Unread ones are never deleted. */
    @Transactional
    public int purgeRead(LocalDateTime cutoff) {
        return notifications.purgeReadBefore(cutoff);
    }
}