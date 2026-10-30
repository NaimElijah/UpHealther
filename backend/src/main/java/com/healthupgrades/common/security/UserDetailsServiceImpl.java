package com.healthupgrades.common.security;

import com.healthupgrades.common.domain.port.out.AccountCredentialsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a Spring Security principal by email, for the login endpoint's password check.
 *
 * <p>Bridges the framework's {@link UserDetailsService} to the kernel's {@link AccountCredentialsPort},
 * which the user context supplies, wrapping the account in a {@link SecurityUser} so the domain stays
 * framework-free and the kernel names no type of the user context (ADR-062).
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final AccountCredentialsPort accounts; // inverted port; the user context supplies it

    /**
     * {@inheritDoc}
     *
     * <p>The address is normalised on this path as well as at the login boundary, by the port's supplier,
     * because this is the method Spring Security calls and nothing forces every future caller through
     * that boundary. The exception's message omits the address: it is personal data (NFR-6), and a
     * framework is free to log a message it was handed.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return accounts.findByEmail(email)
                .map(SecurityUser::from)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
