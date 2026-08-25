package com.healthupgrades.user.application;

import com.healthupgrades.support.AUser;
import com.healthupgrades.user.domain.model.User;
import com.healthupgrades.user.domain.port.out.UserRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers the user context's inbound ports.
 *
 * <p>{@code UserService} is a pass-through to the repository port and holds no rule of its own, so these
 * assertions are deliberately thin: they pin the <em>wiring</em>, which is the only thing that can break
 * here. The value is in the absent case — {@code findByEmail} returning empty rather than null is what
 * {@code AuthService}, {@code UserDetailsServiceImpl} and {@code JwtChannelInterceptor} all rely on, and
 * every one of them would fail differently if that changed.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepositoryPort repository;

    @InjectMocks UserService service;

    @Test
    void GivenARegisteredEmail_WhenTheUserIsLookedUp_ThenTheUserIsReturned() {
        User user = AUser.aUser().build();
        when(repository.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(user));

        assertThat(service.findByEmail(AUser.EMAIL)).contains(user);
    }

    @Test
    void GivenAnUnknownEmail_WhenTheUserIsLookedUp_ThenNothingIsReturnedRatherThanNull() {
        when(repository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(service.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void GivenARegisteredEmail_WhenExistenceIsChecked_ThenItIsReportedAsTaken() {
        when(repository.existsByEmail(AUser.EMAIL)).thenReturn(true);

        assertThat(service.existsByEmail(AUser.EMAIL)).isTrue();
    }

    @Test
    void GivenAnUnregisteredEmail_WhenExistenceIsChecked_ThenItIsReportedAsFree() {
        when(repository.existsByEmail(AUser.EMAIL)).thenReturn(false);

        assertThat(service.existsByEmail(AUser.EMAIL)).isFalse();
    }

    @Test
    void GivenANewUser_WhenItIsSaved_ThenThePersistedInstanceIsReturned() {
        // The returned instance is the managed one, which is what carries the generated id back to
        // registration; returning the argument instead would hand out a user with a null id.
        User unsaved = AUser.aUser().id(null).build();
        User persisted = AUser.aUser().build();
        when(repository.save(unsaved)).thenReturn(persisted);

        assertThat(service.save(unsaved)).isSameAs(persisted);
    }
}
