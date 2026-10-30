package com.healthupgrades.auth.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * A refresh credential a session has already exchanged: its digest, and when the exchange happened.
 *
 * <p>A session remembers every one of these for as long as it lives, not only the last. A credential
 * presented again is either a request that was already in flight when another tab rotated, which is
 * forgiven inside the grace window, or a replay by somebody who is not the legitimate client, which
 * ends the session. Remembering only the last one let a thief who had refreshed twice go undetected
 * (S5.A1, ADR-065).
 *
 * @param tokenHash the SHA-256 of the credential, as {@link RefreshToken#hash()} computes it
 * @param spentAt   the instant a rotation retired it, which the grace window is measured from
 */
public record SpentCredential(byte[] tokenHash, Instant spentAt) {

    /**
     * Copies the digest, so the record stays immutable whoever else holds the array.
     *
     * @throws IllegalArgumentException if either component is missing
     */
    public SpentCredential {
        if (tokenHash == null || spentAt == null) {
            throw new IllegalArgumentException("a spent credential needs a digest and an instant");
        }
        tokenHash = tokenHash.clone();
    }

    /** A copy of the digest, for the same reason the constructor takes one. */
    @Override
    public byte[] tokenHash() {
        return tokenHash.clone();
    }

    /**
     * Whether presenting this credential now is merely stale rather than a replay.
     *
     * @param now   the instant it was presented
     * @param grace how long after a rotation the retired credential is still forgiven
     * @return true up to and including {@code spentAt + grace}
     */
    public boolean isWithinGrace(Instant now, Duration grace) {
        return !now.isAfter(spentAt.plus(grace));
    }

    /** By value: a record compares an array component by reference, which no caller would expect. */
    @Override
    public boolean equals(Object other) {
        return other instanceof SpentCredential that
                && Arrays.equals(tokenHash, that.tokenHash)
                && spentAt.equals(that.spentAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(tokenHash), spentAt);
    }

    /** Prints when it was spent and never the digest, which is no use in a log line. */
    @Override
    public String toString() {
        return "SpentCredential[spentAt=" + spentAt + "]";
    }
}
