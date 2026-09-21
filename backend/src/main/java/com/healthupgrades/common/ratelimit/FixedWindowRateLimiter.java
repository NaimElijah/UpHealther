package com.healthupgrades.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counts attempts per client, in fixed windows, in this process's memory.
 *
 * <p>A fixed window rather than a sliding one or a token bucket, and in memory rather than in Redis,
 * because of what this is defending: anonymous sign-in and registration against guessing. The failure
 * mode of a fixed window is that a client can spend its whole allowance at the end of one window and
 * again at the start of the next — twice the limit across an instant. For a limit of ten attempts a
 * minute, "twenty in two seconds, then nothing for a minute" is not a meaningful attack, and it buys a
 * counter with no dependency and no network hop on the request path. ADR-017 records what would make a
 * shared store worth it.
 *
 * <p><strong>The map is bounded.</strong> It is keyed by something the caller chooses, so an unbounded
 * one is its own denial of service: addresses that appear once and never again would accumulate until
 * the heap ran out. At the cap, expired windows are swept first — they are dead weight and there are
 * usually plenty — and only if that frees nothing is the oldest live window evicted. Eviction means
 * somebody's count is forgotten, which is a weaker limit rather than a broken process, and that is the
 * right way round.
 *
 * <p>Correctness under concurrency rests on {@link ConcurrentHashMap#compute}, which holds the bin lock
 * for one key while the window is read, judged and replaced. A read-then-write pair would let two
 * threads both see the last permitted attempt and both allow it.
 */
@Component
@RequiredArgsConstructor
public class FixedWindowRateLimiter {

    private final RateLimitProperties properties;
    private final Clock clock;

    /** One live window per client key. */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** When the expired-window sweep last ran, so a full scan cannot happen once per request. */
    private final AtomicReference<Instant> lastSweep = new AtomicReference<>(Instant.EPOCH);

    /**
     * Counts one attempt from a client and says whether it is allowed.
     *
     * @param clientKey the client to count against, from {@link ClientAddressKey}
     * @return allowed, or refused with how long until the window resets
     */
    public Decision check(String clientKey) {
        Instant now = clock.instant();
        evictIfFull(now, clientKey);

        Window window = windows.compute(clientKey, (key, existing) -> {
            if (existing == null || !existing.covers(now, properties.window())) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.attempts().incrementAndGet();
            return existing;
        });

        int attempts = window.attempts().get();
        if (attempts <= properties.limit()) {
            return Decision.allow();
        }
        Duration wait = Duration.between(now, window.startedAt().plus(properties.window()));
        return Decision.refuse(wait.isNegative() ? Duration.ZERO : wait);
    }

    /**
     * How many client keys are currently held.
     *
     * <p>Package-private, and here for one reason: the cap is a claim about memory, and a claim about
     * memory that nothing can observe is a comment. {@code FixedWindowRateLimiterTest} reads it.
     */
    int trackedClients() {
        return windows.size();
    }

    /**
     * Keeps the map inside its cap before a new key is added.
     *
     * <p>Only runs when the map is full and the key is new, so the ordinary path — an address already
     * being tracked — costs one lookup and nothing else.
     *
     * <p><strong>Both steps are bounded, and that is the point.</strong> An earlier version swept the
     * whole map and then took a {@code min()} over it on every request once the map was full — which
     * an attacker reaches deliberately by spraying fresh keys, turning a defence against a denial of
     * service into a way to spend the server's CPU. The sweep is now throttled to once per window, and
     * the overflow eviction drops whichever entry the iterator reaches first rather than searching for
     * the oldest. Evicting an arbitrary window instead of the oldest is a slightly weaker limit for one
     * client; scanning ten thousand entries per request is a worse problem than the one being solved.
     */
    private void evictIfFull(Instant now, String clientKey) {
        if (windows.size() < properties.maxTrackedClients() || windows.containsKey(clientKey)) {
            return;
        }
        sweepExpiredAtMostOncePerWindow(now);
        if (windows.size() < properties.maxTrackedClients()) {
            return;
        }
        // Everything tracked is still live, so somebody's count is forgotten either way. A weaker
        // limit for one client beats an unbounded map or a per-request scan.
        Iterator<String> keys = windows.keySet().iterator();
        if (keys.hasNext()) {
            windows.remove(keys.next());
        }
    }

    /**
     * Removes expired windows, at most once per window length.
     *
     * <p>Expired entries are dead weight and there are usually plenty, so this is what normally keeps
     * the map inside its cap. It is throttled because it is O(n): without the throttle, a full map
     * would make every request with a new key walk every entry.
     */
    private void sweepExpiredAtMostOncePerWindow(Instant now) {
        Instant previous = lastSweep.get();
        if (now.isBefore(previous.plus(properties.window())) || !lastSweep.compareAndSet(previous, now)) {
            return;
        }
        windows.entrySet().removeIf(entry -> !entry.getValue().covers(now, properties.window()));
    }

    /**
     * One client's window: when it opened and how many attempts have landed in it.
     *
     * <p>The count is an {@link AtomicInteger} rather than an {@code int} so the record itself can be
     * reused across attempts in the same window — replacing it on every attempt would allocate per
     * request and lose the increment under {@code compute}'s lock.
     */
    private record Window(Instant startedAt, AtomicInteger attempts) {

        boolean covers(Instant now, Duration length) {
            return now.isBefore(startedAt.plus(length));
        }
    }

    /**
     * What the limiter decided.
     *
     * @param allowed    whether the attempt may proceed
     * @param retryAfter how long until the window resets; meaningless when allowed
     */
    public record Decision(boolean allowed, Duration retryAfter) {

        // Named allow/refuse rather than allowed/refused: a static factory sharing a name with a
        // record component is read by the compiler as a malformed accessor.
        static Decision allow() {
            return new Decision(true, Duration.ZERO);
        }

        static Decision refuse(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}
