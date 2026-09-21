package com.healthupgrades.common.security;

import com.healthupgrades.support.AUser;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * A disabled account is refused, and — the part that is easy to get wrong — it is refused <em>after</em>
 * the password has been checked rather than before.
 *
 * <p>Spring's {@code DaoAuthenticationProvider} runs its pre-authentication checks first, so by default
 * a disabled account is turned away without BCrypt ever running. That reply comes back in a fraction of
 * the time a wrong password takes, because BCrypt is deliberately slow, and the gap is wide enough to
 * measure across a network. It answers a question nobody asked it: which addresses are real accounts
 * that happen to be switched off. {@code SecurityConfig} therefore empties the pre-check and moves the
 * enabled test after the password match.
 *
 * <p>That ordering is asserted by counting BCrypt rather than by timing anything — a timing assertion
 * would be the flakiest test in the suite and would prove nothing on a loaded CI runner. The encoder
 * here is the real BCrypt one behind a counter, so it costs what the production one costs; a stubbed
 * encoder would take no time and leave the ordering unobservable.
 *
 * <p>The provider is built by calling {@code SecurityConfig.authenticationProvider()} itself, not by
 * assembling a similar one here. Re-wiring it in the test would let production drift back to the
 * default order with this class still green.
 */
@ExtendWith(MockitoExtension.class)
class DisabledAccountAuthenticationTest {

    private static final String RAW_PASSWORD = "correct-horse-battery-staple";
    private static final String WRONG_PASSWORD = "not-the-password";

    @Mock UserQuery userQuery;

    private CountingPasswordEncoder encoder;
    private AuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        encoder = new CountingPasswordEncoder();
        UserDetailsServiceImpl userDetailsService = new UserDetailsServiceImpl(userQuery);
        // The filter is null because authenticationProvider() does not touch it; overriding the encoder
        // is the only reason this is a subclass rather than the bean itself.
        SecurityConfig config = new SecurityConfig(null, userDetailsService) {
            @Override
            public PasswordEncoder passwordEncoder() {
                return encoder;
            }
        };
        provider = config.authenticationProvider();
    }

    @Test
    void GivenADisabledAccountAndTheRightPassword_WhenItSignsIn_ThenItIsRefusedOnlyAfterThePasswordWasChecked() {
        registered(disabledUser());

        assertThatThrownBy(() -> provider.authenticate(credentials(RAW_PASSWORD)))
                .isInstanceOf(DisabledException.class);

        assertThat(encoder.matchCalls)
                .as("the password must be verified even for a disabled account, or the refusal comes "
                        + "back faster than a wrong password does and reveals that the account exists")
                .isEqualTo(1);
    }

    @Test
    void GivenADisabledAccountAndAWrongPassword_WhenItSignsIn_ThenItIsRefusedAsBadCredentials() {
        // The wrong password is found first, so the caller cannot tell a disabled account from a typo
        // by the answer either. Both reach the client as 401 "Invalid credentials".
        registered(disabledUser());

        assertThatThrownBy(() -> provider.authenticate(credentials(WRONG_PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void GivenAnEnabledAccountAndAWrongPassword_WhenItSignsIn_ThenItIsRefusedAsBadCredentials() {
        registered(enabledUser());

        assertThatThrownBy(() -> provider.authenticate(credentials(WRONG_PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(encoder.matchCalls).isEqualTo(1);
    }

    @Test
    void GivenAnEnabledAccountAndTheRightPassword_WhenItSignsIn_ThenItIsAuthenticated() {
        registered(enabledUser());

        Authentication authenticated = provider.authenticate(credentials(RAW_PASSWORD));

        assertThat(authenticated.isAuthenticated()).isTrue();
        assertThat(authenticated.getName()).isEqualTo(AUser.EMAIL);
    }

    private void registered(User user) {
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(user));
    }

    private User enabledUser() {
        return AUser.aUser().passwordHash(new BCryptPasswordEncoder().encode(RAW_PASSWORD)).build();
    }

    private User disabledUser() {
        User user = enabledUser();
        user.disable();
        return user;
    }

    private static Authentication credentials(String password) {
        return new UsernamePasswordAuthenticationToken(AUser.EMAIL, password);
    }

    /**
     * The real BCrypt encoder, counting the matches asked of it.
     *
     * <p>It has to be the real one: the assertion is that an expensive check happened, and a stub would
     * satisfy the count while costing nothing, which is the failure being guarded against.
     */
    private static final class CountingPasswordEncoder implements PasswordEncoder {

        private final PasswordEncoder delegate = new BCryptPasswordEncoder();
        private int matchCalls;

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchCalls++;
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
