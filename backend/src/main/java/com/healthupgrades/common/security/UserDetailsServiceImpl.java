package com.healthupgrades.common.security;

import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.EmailAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a Spring Security principal by email, for the login endpoint's password check.
 *
 * <p>Bridges the framework's {@link UserDetailsService} to the user context's {@link UserQuery}
 * inbound port, wrapping the domain user in a {@link SecurityUser} so the domain stays framework-free.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserQuery userQuery; // inbound read port of the user context

    /**
     * {@inheritDoc}
     *
     * <p>The address is normalised here as well as at the login boundary, because this is the method
     * Spring Security calls and nothing forces every future caller through that boundary. The
     * exception's message omits the address: it is personal data (NFR-6), and a framework is free to
     * log a message it was handed.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userQuery.findByEmail(EmailAddress.normalise(email))
                .map(SecurityUser::from)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
