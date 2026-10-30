package com.healthupgrades.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.WebSliceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * Pins the refresh cookie's name and path as the jar's own {@code application.yml} ships them (S5.A8).
 *
 * <p>The {@code __Host-} prefix makes a browser refuse the cookie unless it is set with {@code Secure},
 * from a secure origin, with {@code Path=/} and with no {@code Domain}, so no other host, sibling or
 * parent, can plant one. A browser also refuses a {@code __Host-} cookie on any other path, so the two
 * settings are pinned together: a narrower path would leave every browser without a session. The web
 * slices build their cookie from {@link WebSliceSupport#COOKIE_NAME} and {@link
 * WebSliceSupport#COOKIE_PATH}, so without this test the shipped settings could drift with every other
 * test still green. The configuration is read as {@code ShippedJwtConfigurationTest} reads it: the
 * shipped file alone, with the process environment removed.
 */
class ShippedRefreshCookieConfigurationTest {

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withUserConfiguration(BindsRefreshCookieProperties.class);

    @Test
    void GivenTheShippedConfiguration_WhenTheCookieSettingsAreBound_ThenTheNameCarriesTheHostPrefix() {
        shipped.run(context -> assertThat(
                        context.getBean(RefreshCookieProperties.class).name())
                .isEqualTo(WebSliceSupport.COOKIE_NAME)
                .startsWith("__Host-"));
    }

    @Test
    void GivenTheShippedConfiguration_WhenTheCookieSettingsAreBound_ThenThePathIsTheRootTheHostPrefixRequires() {
        shipped.run(context -> assertThat(
                        context.getBean(RefreshCookieProperties.class).path())
                .isEqualTo(WebSliceSupport.COOKIE_PATH)
                .isEqualTo("/"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefreshCookieProperties.class)
    static class BindsRefreshCookieProperties {}
}
