package com.healthupgrades.user.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers FR-4's comparison rule: an email is one identity however it was typed, so the address is
 * normalised once, here, and every lookup and every stored row goes through the same function.
 */
class EmailAddressTest {

    @Test
    void GivenAnAddressWithCapitalsAndSurroundingSpace_WhenNormalised_ThenItIsTrimmedAndLowercase() {
        assertThat(EmailAddress.normalise("  Someone@Example.COM	")).isEqualTo("someone@example.com");
    }

    @Test
    void GivenAnAlreadyNormalAddress_WhenNormalised_ThenItIsUnchanged() {
        assertThat(EmailAddress.normalise("someone@example.com")).isEqualTo("someone@example.com");
    }

    @Test
    void GivenATurkishDefaultLocale_WhenAnAddressWithACapitalIIsNormalised_ThenTheDotlessIIsNotProduced() {
        // toLowerCase() without a locale turns "I" into "ı" under tr-TR, which would make one
        // address two identities depending on the JVM it ran on.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(EmailAddress.normalise("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void GivenNoAddress_WhenNormalised_ThenNothingIsReturnedRatherThanAFailure() {
        // Bean validation reports a missing email by field; normalising must not pre-empt that with a
        // NullPointerException that would surface as a 500.
        assertThat(EmailAddress.normalise(null)).isNull();
    }
}
