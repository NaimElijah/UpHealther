package com.healthupgrades.upgrade.application;

import com.healthupgrades.upgrade.domain.service.UpgradeSchedulingService; // pure domain service being exposed
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the upgrade context's pure domain services as Spring beans.
 *
 * <p>Domain services are framework-free (no {@code @Service}), so the application layer — which is
 * allowed to depend on Spring — is responsible for exposing them for constructor injection.
 */
@Configuration
public class UpgradeBeansConfig {

    /** Exposes the stateless {@link UpgradeSchedulingService} as an injectable bean. */
    @Bean
    public UpgradeSchedulingService upgradeSchedulingService() {
        return new UpgradeSchedulingService();
    }
}
