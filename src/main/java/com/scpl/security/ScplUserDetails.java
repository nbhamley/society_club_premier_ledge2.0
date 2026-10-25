package com.scpl.security;

import com.scpl.model.PlatformRole;
import com.scpl.model.User;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Session principal. It holds only ids and the role, never an entity, so nothing lazy or stale lives in
 * the HTTP session. It is a first gate for URL rules; anything that matters is re-read from the database
 * through CurrentUser.
 */
public class ScplUserDetails implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String username;
    private String passwordHash;
    private final boolean enabled;
    private final PlatformRole role;
    private final Long societyId;
    private final Long playerId;
    private final String displayName;
    private final List<SimpleGrantedAuthority> authorities;

    private ScplUserDetails(User u) {
        this.id = u.getId();
        this.username = u.getUsername();
        this.passwordHash = u.getPasswordHash();
        this.enabled = Boolean.TRUE.equals(u.getEnabled());
        this.role = u.getRole();
        this.societyId = u.getSocietyId();
        this.playerId = u.getPlayerId();
        this.displayName = u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()));
    }

    public static ScplUserDetails from(User u) {
        return new ScplUserDetails(u);
    }

    public Long getId() {
        return id;
    }

    public PlatformRole getRole() {
        return role;
    }

    public Long getSocietyId() {
        return societyId;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Called after a successful login so the hash does not sit in the session. */
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScplUserDetails other && username.equalsIgnoreCase(other.username);
    }

    @Override
    public int hashCode() {
        return username.toLowerCase(Locale.ROOT).hashCode();
    }
}