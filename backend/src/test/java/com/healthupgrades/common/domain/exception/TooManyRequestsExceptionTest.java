package com.healthupgrades.common.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The {@code Retry-After} a 429 carries, which NFR-42 promises and a client acts on.
 *
 * <p>The header holds whole seconds, so a wait has to be rounded, and only up is safe: rounded down,
 * any wait under a second becomes {@code Retry-After: 0}, an invitation to retry at once into the same
 * refusal, and a client honouring it spins.
 */
class TooManyRequestsExceptionTest {

    @Test
    void GivenNoWaitLeft_WhenTheRetryAfterIsRead_ThenItIsStillOneSecond() {
        assertThat(new TooManyRequestsException(Duration.ZERO).retryAfterSeconds())
                .isEqualTo(1);
    }

    @Test
    void GivenAWaitOfOneMillisecond_WhenTheRetryAfterIsRead_ThenItRoundsUpToOneSecond() {
        assertThat(new TooManyRequestsException(Duration.ofMillis(1)).retryAfterSeconds())
                .isEqualTo(1);
    }

    @Test
    void GivenAWaitOfASecondAndAHalf_WhenTheRetryAfterIsRead_ThenItRoundsUpToTwoSeconds() {
        assertThat(new TooManyRequestsException(Duration.ofMillis(1500)).retryAfterSeconds())
                .isEqualTo(2);
    }

    @Test
    void GivenAWaitOfWholeSeconds_WhenTheRetryAfterIsRead_ThenItIsThatManySeconds() {
        // Rounding up must not add a second to a wait that needs none added.
        assertThat(new TooManyRequestsException(Duration.ofSeconds(2)).retryAfterSeconds())
                .isEqualTo(2);
    }
}
