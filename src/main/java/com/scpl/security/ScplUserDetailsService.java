package com.scpl.security;

import com.scpl.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScplUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    /**
     * The message never reaches the user: DaoAuthenticationProvider converts "not found" into a generic
     * bad-credentials error, so the login page cannot be used to discover which usernames exist.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return users.findByUsernameIgnoreCase(username == null ? "" : username.trim())
                .map(ScplUserDetails::from)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}