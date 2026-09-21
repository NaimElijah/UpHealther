package com.healthupgrades.common.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Turns a client address into the key a rate limit counts against.
 *
 * <p><strong>IPv6 is keyed by its /64, not by the whole address.</strong> This is the difference
 * between a limit that works and a limit that does nothing: a residential IPv6 allocation is typically
 * a /64 or larger, so counting whole addresses lets one attacker walk through billions of them without
 * ever meeting a limit. A /64 is the smallest block that is handed out as a unit, so it is the smallest
 * thing worth counting.
 *
 * <p>IPv4 is used whole. There is no equivalent block to collapse to — addresses are scarce enough that
 * they are not handed out in ranges to one person, and collapsing to a /24 would put unrelated
 * customers of one ISP in the same bucket.
 *
 * <p>Nothing here is logged or used as a metric tag. An address is personal data (NFR-6), and as a tag
 * it would also be unbounded, which is the other reason ADR-012 forbids it.
 */
final class ClientAddressKey {

    /**
     * What may be handed to {@link InetAddress#getByName} without risking a name lookup.
     *
     * <p>{@code getByName} resolves anything it cannot read as a literal, and a resolution is a blocking
     * network call on a request thread. A hostname cannot contain a colon, so requiring at least one —
     * and nothing outside the hex, colon and dot alphabet — means only an IPv6 literal, valid or not,
     * ever reaches the parser.
     */
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*$");

    /** The leading bytes of an IPv6 address that identify its /64. */
    private static final int IPV6_PREFIX_BYTES = 8;

    private ClientAddressKey() {
    }

    /**
     * The key to count attempts from this address against.
     *
     * @param clientAddress the address Tomcat resolved for the request, already stripped of any
     *                      untrusted {@code X-Forwarded-For} hops
     * @return the /64 prefix for an IPv6 address, the address itself for IPv4, and the input unchanged
     *         for anything unrecognisable — an address that cannot be parsed still gets counted, just
     *         under itself, because the alternative is not counting it at all
     */
    static String of(String clientAddress) {
        if (clientAddress == null || clientAddress.isBlank()) {
            // One shared bucket for requests with no address at all. They should not exist behind a
            // proxy that is doing its job, and pooling them is safer than exempting them.
            return "unknown";
        }
        if (!IPV6_LITERAL.matcher(clientAddress).matches()) {
            return clientAddress;
        }
        try {
            InetAddress address = InetAddress.getByName(clientAddress);
            if (!(address instanceof Inet6Address)) {
                return clientAddress;
            }
            byte[] bytes = address.getAddress();
            StringBuilder prefix = new StringBuilder(IPV6_PREFIX_BYTES * 2 + 3);
            for (int i = 0; i < IPV6_PREFIX_BYTES; i++) {
                prefix.append(String.format("%02x", bytes[i]));
            }
            return prefix.append("::/64").toString();
        } catch (UnknownHostException unparseable) {
            return clientAddress;
        }
    }
}
