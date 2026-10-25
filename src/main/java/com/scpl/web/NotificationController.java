package com.scpl.web;

import com.scpl.dto.AccountViews.NotificationView;
import com.scpl.model.Notification;
import com.scpl.model.User;
import com.scpl.security.CurrentUser;
import com.scpl.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/** The bell menu and /notifications page. Every call is keyed by the signed-in user's id, so nobody can read or clear another user's. */
@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final CurrentUser current;
    private final NotificationService notifications;

    @GetMapping
    public String list(Model model) {
        User u = current.require();
        List<NotificationView> rows = notifications.recent(u.getId()).stream().map(NotificationController::toView).toList();
        model.addAttribute("notifications", rows);
        return "notifications";
    }

    @PostMapping("/{id}/read")
    public String markRead(@PathVariable Long id) {
        notifications.markRead(id, current.require().getId());
        return "redirect:/notifications";
    }

    @PostMapping("/read-all")
    public String markAllRead() {
        notifications.markAllRead(current.require().getId());
        return "redirect:/notifications";
    }

    private static NotificationView toView(Notification n) {
        return new NotificationView(n.getId(), n.getTitle(), n.getMessage(), n.getLink(),
                Boolean.TRUE.equals(n.getRead()), n.getCreatedAt());
    }
}