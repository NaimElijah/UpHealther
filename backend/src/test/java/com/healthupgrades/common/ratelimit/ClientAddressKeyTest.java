package com.healthupgrades.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * NFR-43: the key a rate limit counts against, for every shape of address Tomcat can hand over.
 *
 * <p>Every input is a literal or a string no resolver could answer, so nothing here touches the
 * network. That the hostname guard keeps a name from ever reaching {@code InetAddress.getByName} is not
 * observable from the key: a lookup that fails returns the input unchanged, exactly as the guard does.
 * Pinning the guard would take a test-scope {@code java.net.spi.InetAddressResolverProvider} that
 * records every lookup. The JDK installs such a provider for the whole JVM, so it would stand under
 * every test in the fork, not only this class; it is not adopted here (#359).
 */
class ClientAddressKeyTest {

    @Test
    void GivenNoAddress_WhenTheKeyIsTaken_ThenItIsTheSharedUnknownBucket() {
        // Pooled rather than exempted: a request with no address must still be counted against something.
        assertThat(ClientAddressKey.of(null)).isEqualTo("unknown");
    }

    @Test
    void GivenABlankAddress_WhenTheKeyIsTaken_ThenItIsTheSharedUnknownBucket() {
        assertThat(ClientAddressKey.of("  ")).isEqualTo("unknown");
    }

    @Test
    void GivenAnIpv4Address_WhenTheKeyIsTaken_ThenItIsTheWholeAddress() {
        // No block to collapse to: a /24 would put unrelated customers of one ISP in the same bucket.
        assertThat(ClientAddressKey.of("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void GivenAnIpv6Address_WhenTheKeyIsTaken_ThenItIsItsSlash64() {
        assertThat(ClientAddressKey.of("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd"))
                .isEqualTo("20010db812345678::/64");
    }

    @Test
    void GivenTwoSpellingsOfOneIpv6Address_WhenTheKeysAreTaken_ThenTheyAreTheSame() {
        // The key is read from the parsed bytes, so a compressed and an expanded form cannot be counted apart.
        assertThat(ClientAddressKey.of("2001:0db8:0000:0001:0:0:0:1"))
                .isEqualTo(ClientAddressKey.of("2001:db8:0:1::99"));
    }

    @Test
    void GivenTwoIpv4MappedAddresses_WhenTheKeysAreTaken_ThenTheyAreNotFoldedIntoOneSharedSlash64() {
        // Behind a dual-stack proxy every IPv4 client arrives in this form, and the first 64 bits of each
        // are zero: keyed by the /64, they would all share one bucket, and one client's attempts would
        // lock out every other. Whether a mapped address shares a key with its plain IPv4 spelling is a
        // separate question, which this test leaves open.
        assertThat(ClientAddressKey.of("::ffff:203.0.113.7")).isNotEqualTo(ClientAddressKey.of("::ffff:198.51.100.9"));
    }

    @Test
    void GivenAHostname_WhenTheKeyIsTaken_ThenItIsCountedUnderItself() {
        assertThat(ClientAddressKey.of("client.example.invalid")).isEqualTo("client.example.invalid");
    }

    @Test
    void GivenAColonSeparatedValueThatIsNotAnAddress_WhenTheKeyIsTaken_ThenItIsCountedUnderItself() {
        // Nine groups: shaped like IPv6, refused by the parser. Counted under itself rather than not at all.
        assertThat(ClientAddressKey.of("1:2:3:4:5:6:7:8:9")).isEqualTo("1:2:3:4:5:6:7:8:9");
    }
}
