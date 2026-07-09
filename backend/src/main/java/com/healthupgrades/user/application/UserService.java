package com.healthupgrades.user.application;

import com.healthupgrades.user.application.port.in.UserDirectory;
import com.healthupgrades.user.domain.User;
import com.healthupgrades.user.domain.port.out.UserRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Application service implementing the {@link UserDirectory} inbound port over the user repository port.
 */
@Service
@RequiredArgsConstructor
public class UserService implements UserDirectory {

    private final UserRepositoryPort repository; // outbound persistence port

    /** {@inheritDoc} */
    @Override
    public Optional<User> findByEmail(String email) {
        return repository.findByEmail(email);
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByEmail(String email) {
        return repository.existsByEmail(email);
    }

    /** {@inheritDoc} */
    @Override
    public User save(User user) {
        return repository.save(user);
    }
}
