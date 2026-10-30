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
 * Pins the refresh cookie's name as the jar's own {@code application.yml} ships it (S5.A8).
 *
 * <p>The {@code __Secure-} prefix makes a browser refuse the cookie unless it is set with {@code Secure}
 * from a secure origin, so a plain-HTTP sibling host or a network attacker cannot plant one. The web
 * slices build their cookie from {@link WebSliceSupport#COOKIE_NAME}, so without this test the shipped
 * name could lose its prefix with every other test still green. The configuration is read as
 * {@code ShippedJwtConfigurationTest} reads it: the shipped file alone, with the process environment
 * removed.
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
    void GivenTheShippedConfiguration_WhenTheCookieSettingsAreBound_ThenTheNameCarriesTheSecurePrefix() {
        shipped.run(context -> assertThat(
                        context.getBean(RefreshCookieProperties.class).name())
                .isEqualTo(WebSliceSupport.COOKIE_NAME)
                .startsWith("__Secure-"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefreshCookieProperties.class)
    static class BindsRefreshCookieProperties {}
}
