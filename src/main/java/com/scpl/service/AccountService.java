package com.scpl.service;

import com.scpl.dto.AccountViews.ProfileView;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.User;
import com.scpl.repo.UserRepository;
import com.scpl.security.LoginThrottle;
import com.scpl.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Self-service profile and password (§7). Users can only ever change their own row: the id comes from the session user. */
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final int MIN_PASSWORD = 10;
    private static final int MAX_PASSWORD_BYTES = 72; // bcrypt silently ignores anything past 72 bytes

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final AuditService audit;

    /** Built from the already-loaded user (society and player come from CurrentUser's entity graph). */
    public ProfileView profileView(User u) {
        String societyName = u.getSociety() == null ? null : u.getSociety().getName();
        String societySlug = u.getSociety() == null ? null : u.getSociety().getSlug();
        return new ProfileView(u.getUsername(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole().getLabel(),
                societyName, societySlug, u.getPlayer() == null ? null : u.getPlayer().getName(),
                u.isSocietyAdmin() && u.getPlayer() == null);
    }

    @Transactional
    public void updateProfile(User actor, String fullName, String email, String phone) {
        User u = users.findById(actor.getId()).orElseThrow(() -> new NotFoundException("Account not found"));
        String newEmail = email.trim();
        if (users.existsByEmailIgnoreCaseAndIdNot(newEmail, u.getId())) {
            throw new BusinessRuleException("That email is already used by another account");
        }
        u.setFullName(Text.clean(fullName));
        u.setEmail(newEmail);
        u.setPhone(Text.clean(phone));
        users.save(u);
        audit.record(actor, u.getSocietyId(), AuditActions.PROFILE_UPDATED, "USER", u.getId(), null, null);
    }

    /**
     * The current password is re-checked even though the user is signed in, so a stolen session cannot change
     * it. Wrong guesses feed the same throttle as the login form, keyed separately.
     */
    @Transactional
    public void changePassword(User actor, String current, String next, String confirm) {
        String key = LoginThrottle.key("pwchange", actor.getUsername());
        if (throttle.isBlocked(key)) {
            throw new BusinessRuleException("Too many wrong attempts. Try again in a few minutes.");
        }
        User u = users.findById(actor.getId()).orElseThrow(() -> new NotFoundException("Account not found"));
        if (current == null || !encoder.matches(current, u.getPasswordHash())) {
            throttle.failure(key);
            throw new BusinessRuleException("Your current password is not correct");
        }
        throttle.success(key);
        if (next == null || next.length() < MIN_PASSWORD) {
            throw new BusinessRuleException("The new password must be at least " + MIN_PASSWORD + " characters");
        }
        if (next.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BusinessRuleException("The new password is too long (72 bytes at most)");
        }
        if (!next.equals(confirm)) {
            throw new BusinessRuleException("The two new passwords do not match");
        }
        if (next.equals(current)) {
            throw new BusinessRuleException("Choose a password you have not used before");
        }
        if (next.toLowerCase(Locale.ROOT).contains(u.getUsername().toLowerCase(Locale.ROOT))) {
            throw new BusinessRuleException("The password must not contain your username");
        }
        u.setPasswordHash(encoder.encode(next));
        users.save(u);
        audit.record(actor, u.getSocietyId(), AuditActions.PASSWORD_CHANGED, "USER", u.getId(), null, Map.of("by", "self"));
    }
}