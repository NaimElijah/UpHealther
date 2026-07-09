package com.healthupgrades.common.security;

import com.healthupgrades.user.application.port.in.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a Spring Security principal by email.
 *
 * <p>Bridges the framework's {@link UserDetailsService} to the user context's {@link UserDirectory}
 * inbound port, wrapping the domain user in a {@link SecurityUser} so the domain stays framework-free.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserDirectory userDirectory; // inbound port of the user context

    /** {@inheritDoc} */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userDirectory.findByEmail(email)
                .map(u -> new SecurityUser(u.getId(), u.getEmail(), u.getPasswordHash())) // wrap domain user
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
