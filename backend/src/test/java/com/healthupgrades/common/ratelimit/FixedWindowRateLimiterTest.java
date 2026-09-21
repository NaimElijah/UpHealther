package com.healthupgrades.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counter behind #56, and the three properties that make it worth having.
 *
 * <p><strong>It counts.</strong> The limit is exact — the tenth attempt is allowed and the eleventh is
 * not — because an off-by-one here either locks out a person who mistyped a password once too often or
 * hands an attacker a free attempt per window forever.
 *
 * <p><strong>It forgets.</strong> The window resets, so a limit is a slowdown rather than a ban. A
 * counter that never reset would turn one bad afternoon into a permanent lockout of whoever shares that
 * address.
 *
 * <p><strong>It is bounded.</strong> The map is keyed by something the caller chooses, so an unbounded
 * one is a second denial of service wearing the coat of a defence against the first.
 *
 * <p>Time is injected, so none of this waits. A rate-limit test that slept would be the slowest and
 * flakiest in the suite.
 */
class FixedWindowRateLimiterTest {

    private static final Instant START = Instant.parse("2026-09-21T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int LIMIT = 10;
    private static final String CLIENT = "203.0.113.7";

    private final MovableClock clock = new MovableClock(START);

    private FixedWindowRateLimiter limiterAllowing(int limit, int maxTracked) {
        return new FixedWindowRateLimiter(
                new RateLimitProperties(limit, WINDOW, maxTracked), clock);
    }

    private FixedWindowRateLimiter limiter() {
        return limiterAllowing(LIMIT, 1000);
    }

    @Test
    void GivenAClientWithinItsAllowance_WhenItAttempts_ThenEveryAttemptUpToTheLimitIsAllowed() {
        FixedWindowRateLimiter limiter = limiter();

        boolean allAllowed = IntStream.rangeClosed(1, LIMIT)
                .allMatch(attempt -> limiter.check(CLIENT).allowed());

        assertThat(allAllowed)
                .as("the limit is the number of attempts permitted, not the number before refusing")
                .isTrue();
    }

    @Test
    void GivenAClientThatHasSpentItsAllowance_WhenItAttemptsAgain_ThenItIsRefused() {
        FixedWindowRateLimiter limiter = limiter();
        IntStream.rangeClosed(1, LIMIT).forEach(attempt -> limiter.check(CLIENT));

        assertThat(limiter.check(CLIENT).allowed()).isFalse();
    }

    @Test
    void GivenARefusal_WhenItIsRead_ThenItSaysHowLongUntilTheWindowResets() {
        // Without this the client has nothing to do but keep asking, which is the behaviour being
        // limited. Twenty seconds in, forty remain of a one-minute window.
        FixedWindowRateLimiter limiter = limiter();
        IntStream.rangeClosed(1, LIMIT).forEach(attempt -> limiter.check(CLIENT));
        clock.moveTo(START.plusSeconds(20));

        FixedWindowRateLimiter.Decision refused = limiter.check(CLIENT);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void GivenTheWindowHasPassed_WhenTheClientAttemptsAgain_ThenItIsAllowedWithAFreshAllowance() {
        FixedWindowRateLimiter limiter = limiter();
        IntStream.rangeClosed(1, LIMIT + 5).forEach(attempt -> limiter.check(CLIENT));

        clock.moveTo(START.plus(WINDOW));

        assertThat(limiter.check(CLIENT).allowed())
                .as("a limit is a slowdown, not a ban - the window has to reset")
                .isTrue();
    }

    @Test
    void GivenTheWindowHasAlmostPassed_WhenTheClientAttemptsAgain_ThenItIsStillRefused() {
        // The boundary in the other direction: one second early is still inside the window.
        FixedWindowRateLimiter limiter = limiter();
        IntStream.rangeClosed(1, LIMIT).forEach(attempt -> limiter.check(CLIENT));

        clock.moveTo(START.plus(WINDOW).minusSeconds(1));

        assertThat(limiter.check(CLIENT).allowed()).isFalse();
    }

    @Test
    void GivenTwoClients_WhenOneExhaustsItsAllowance_ThenTheOtherIsUnaffected() {
        // The property that makes this usable at all. A shared counter would let one attacker lock out
        // everybody else on the installation, which is a better attack than the one being prevented.
        FixedWindowRateLimiter limiter = limiter();
        IntStream.rangeClosed(1, LIMIT + 1).forEach(attempt -> limiter.check(CLIENT));

        assertThat(limiter.check("198.51.100.4").allowed()).isTrue();
    }

    @Test
    void GivenMoreClientsThanTheCap_WhenNewOnesArrive_ThenTheMapDoesNotGrowWithoutBound() {
        // The map is keyed by the caller's address. Left unbounded, an attacker rotating addresses
        // fills the heap - a denial of service delivered through the thing defending against one.
        FixedWindowRateLimiter limiter = limiterAllowing(LIMIT, 50);

        IntStream.range(0, 500).forEach(i -> limiter.check("198.51.100." + i));

        assertThat(limiter.trackedClients())
                .as("the cap is a cap, not a suggestion")
                .isLessThanOrEqualTo(50);
    }

    @Test
    void GivenTheCapIsReached_WhenExpiredWindowsExist_ThenThoseAreDroppedRatherThanLiveOnes() {
        // Expired windows are dead weight and there are usually plenty, so sweeping them first means a
        // busy client keeps its count while a client that stopped an hour ago loses one it is not using.
        FixedWindowRateLimiter limiter = limiterAllowing(LIMIT, 3);
        limiter.check("198.51.100.1");
        limiter.check("198.51.100.2");
        IntStream.rangeClosed(1, LIMIT).forEach(attempt -> limiter.check(CLIENT));

        clock.moveTo(START.plus(WINDOW).plusSeconds(1));
        limiter.check("198.51.100.9");

        assertThat(limiter.check(CLIENT).allowed())
                .as("the window that held the count has itself expired, so a fresh one starts")
                .isTrue();
    }

    @Test
    void GivenAnIpv6ClientInOneAllocation_WhenAddressesAreRotated_ThenTheyShareOneBucket() {
        // The reason ClientAddressKey exists. A residential IPv6 allocation is a /64 or larger, so
        // counting whole addresses lets one attacker walk through billions without meeting a limit.
        FixedWindowRateLimiter limiter = limiter();
        String[] sameAllocation = {
                "2001:db8:1234:5678::1",
                "2001:db8:1234:5678::dead",
                "2001:db8:1234:5678:aaaa:bbbb:cccc:dddd",
        };

        for (int attempt = 0; attempt < LIMIT; attempt++) {
            limiter.check(ClientAddressKey.of(sameAllocation[attempt % sameAllocation.length]));
        }

        assertThat(limiter.check(ClientAddressKey.of("2001:db8:1234:5678::99")).allowed())
                .as("rotating within one /64 must not buy a fresh allowance")
                .isFalse();
    }

    @Test
    void GivenTwoDifferentIpv6Allocations_WhenBothAttempt_ThenTheyAreCountedApart() {
        FixedWindowRateLimiter limiter = limiter();
        IntStream.rangeClosed(1, LIMIT + 1)
                .forEach(attempt -> limiter.check(ClientAddressKey.of("2001:db8:1111:2222::1")));

        assertThat(limiter.check(ClientAddressKey.of("2001:db8:3333:4444::1")).allowed()).isTrue();
    }

    @Test
    void GivenManyThreadsAttemptingTogether_WhenTheLimitIsReached_ThenExactlyTheLimitIsAllowed() {
        // Read-then-write would let two threads both see the last permitted attempt and both allow it,
        // which is how a limit of ten quietly becomes a limit of "about ten, under load".
        FixedWindowRateLimiter limiter = limiter();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        try {
            IntStream.range(0, threads).forEach(i -> pool.submit(() -> {
                startLine.await();
                if (limiter.check(CLIENT).allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            }));
            startLine.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the pool", interrupted);
        }

        assertThat(allowed.get()).isEqualTo(LIMIT);
    }

    /** A clock that can be moved, so a window can pass without anything sleeping. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void moveTo(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
