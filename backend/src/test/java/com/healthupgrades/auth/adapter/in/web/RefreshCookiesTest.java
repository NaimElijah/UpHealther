package com.healthupgrades.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.support.WebSliceSupport;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * How the refresh cookie is read off a request (NFR-36, S5.A8).
 *
 * <p>The {@code __Host-} prefix already keeps any other host from setting a cookie of this name, so a
 * request carrying two of them is not expected from any browser. It is still refused, as a defence that
 * costs nothing, and it is logged, because if it ever happens something is planting cookies and that
 * should not be invisible. The log line must name neither value: either may be a live credential.
 */
class RefreshCookiesTest {

    private static final String OURS = "6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c.our-secret";
    private static final String PLANTED = "0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1.planted-secret";

    private final RefreshCookies cookies = new RefreshCookies(
            new RefreshCookieProperties(WebSliceSupport.COOKIE_NAME, WebSliceSupport.COOKIE_PATH),
            Clock.fixed(WebSliceSupport.COOKIE_NOW, ZoneOffset.UTC));

    @Test
    void GivenOneRefreshCookie_WhenItIsRead_ThenItsValueComesBackAndNothingIsLogged() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(WebSliceSupport.COOKIE_NAME, OURS));

        Captured<Optional<String>> read = captured(() -> cookies.read(request));

        assertThat(read.value()).hasValue(OURS);
        assertThat(read.logged()).isEmpty();
    }

    @Test
    void GivenTwoRefreshCookies_WhenTheyAreRead_ThenNeitherIsTrustedAndAWarningNamesNeitherValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(WebSliceSupport.COOKIE_NAME, OURS), new Cookie(WebSliceSupport.COOKIE_NAME, PLANTED));

        Captured<Optional<String>> read = captured(() -> cookies.read(request));

        assertThat(read.value()).isEmpty();
        assertThat(read.logged()).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage())
                    .as("either value may be a live credential, so neither is written")
                    .doesNotContain(OURS)
                    .doesNotContain(PLANTED)
                    .doesNotContain("our-secret")
                    .doesNotContain("planted-secret");
        });
    }

    @Test
    void GivenNoCookieAtAll_WhenTheRequestIsRead_ThenNothingComesBackAndNothingIsLogged() {
        Captured<Optional<String>> read = captured(() -> cookies.read(new MockHttpServletRequest()));

        assertThat(read.value()).isEmpty();
        assertThat(read.logged()).isEmpty();
    }

    /** What an action returned, and what {@link RefreshCookies} logged while it ran. */
    private record Captured<T>(T value, List<ILoggingEvent> logged) {}

    private static <T> Captured<T> captured(Supplier<T> action) {
        Logger logger = (Logger) LoggerFactory.getLogger(RefreshCookies.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            return new Captured<>(action.get(), appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
