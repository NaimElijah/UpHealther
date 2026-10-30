package com.healthupgrades.auth.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long a session lives and how forgiving rotation is, bound from {@code app.auth.session} and
 * validated when the application starts.
 *
 * <p>A missing value fails the boot with the property named, rather than surfacing as a null in the
 * first refresh that needs it — at which point it would present as a session that cannot be renewed. So
 * does a value outside its bounds, or an idle window longer than the absolute cap (S5.A13): each bound
 * below admits the shipped default and any setting an operator could mean, and refuses the settings
 * that would quietly undo what the session is for.
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
        @NotNull @DurationMin(hours = MIN_IDLE_HOURS) Duration idle,

        @NotNull @DurationMin(hours = MIN_ABSOLUTE_HOURS) @DurationMax(days = MAX_ABSOLUTE_DAYS)
        Duration absolute,

        @NotNull @DurationMax(minutes = MAX_ROTATION_GRACE_MINUTES)
        Duration rotationGrace) {

    /**
     * The shortest idle window: an hour, the longest access-token lifetime {@code JwtProperties} admits. A
     * shorter one would let a session lapse between two renewals of a tab that was in use all along.
     */
    public static final long MIN_IDLE_HOURS = 1;

    /** The shortest absolute cap, for the same reason as {@link #MIN_IDLE_HOURS}. */
    public static final long MIN_ABSOLUTE_HOURS = 1;

    /**
     * The longest absolute cap: ninety days, three times the shipped thirty. NFR-47 says no session lives
     * indefinitely, and a cap of years would say that only on paper.
     */
    public static final long MAX_ABSOLUTE_DAYS = 90;

    /**
     * The longest rotation grace: a minute, six times the shipped ten seconds. Two tabs racing collide
     * within a moment; a window as long as a session would forgive every replay, which is reuse detection
     * turned off. No lower bound: zero forgives nothing, a strict setting an operator may choose.
     */
    public static final long MAX_ROTATION_GRACE_MINUTES = 1;

    /**
     * Whether the idle window fits inside the absolute cap. The idle expiry never passes the cap, so one
     * longer than it does nothing it says, and is almost certainly a mistake worth naming at boot.
     *
     * @return true when either is missing, which {@code @NotNull} reports on its own, or when idle is at
     *         most absolute
     */
    @AssertTrue(message = "must not exceed app.auth.session.absolute")
    public boolean isIdleWithinAbsolute() {
        return idle == null || absolute == null || idle.compareTo(absolute) <= 0;
    }
}
