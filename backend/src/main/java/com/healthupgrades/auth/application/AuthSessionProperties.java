package com.healthupgrades.auth.application;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How long a session lives and how forgiving rotation is, bound from {@code app.auth.session} and
 * validated when the application starts.
 *
 * <p>A missing value fails the boot with the property named, rather than surfacing as a null in the
 * first refresh that needs it — at which point it would present as a session that cannot be renewed.
 *
 * @param idle           how long a session survives without being used; slides forward on each refresh
 * @param absolute       the cap a session never outlives, however often it is refreshed
 * @param rotationGrace  how long after a rotation the superseded credential is merely stale rather than
 *                       replayed. It exists because two tabs waking together, or a retried request, can
 *                       legitimately present the same credential twice within a moment; long enough to
 *                       cover that and short enough that a stolen credential is of no use
 */
@Validated
@ConfigurationProperties("app.auth.session")
public record AuthSessionProperties(
        @NotNull Duration idle,
        @NotNull Duration absolute,
        @NotNull Duration rotationGrace
) {
}
