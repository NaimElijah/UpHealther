package com.healthupgrades.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The matching rule of BR-17's common-password refusal (ADR-071): a password is common when its NFKC
 * form, lower-cased, is on the list, and the list is normalised the same way as it is loaded.
 *
 * <p>The list here is a lambda over the port: what the shipped resource holds is
 * {@code CommonPasswordListTest}'s business, and reading it is {@code ClasspathCommonPasswordListTest}'s.
 */
class CommonPasswordPolicyTest {

    /** One entry as the derived list writes it, one in capitals, one in fullwidth compatibility forms. */
    private final CommonPasswordPolicy policy =
            new CommonPasswordPolicy(() -> List.of("password1", "QWERTY123", "ｓｅｃｒｅｔ７"));

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"password1", "PASSWORD1", "Password1", "pAsSwOrD1"})
    void GivenAListedPasswordInAnyCasing_WhenItIsChecked_ThenItIsCommon(String password) {
        assertThat(policy.isCommon(password)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ｐａｓｓｗｏｒｄ１", "password１", "Ｐassword1"})
    void GivenACompatibilityFormOfAListedPassword_WhenItIsChecked_ThenItIsCommon(String password) {
        // Fullwidth letters and digits fold onto ASCII under NFKC, and so would let a listed password
        // through if the comparison were made on the code points as typed.
        assertThat(policy.isCommon(password)).isTrue();
    }

    @Test
    void GivenAnEntryListedInCapitals_WhenItsLowerCaseFormIsChecked_ThenItIsCommon() {
        assertThat(policy.isCommon("qwerty123")).isTrue();
    }

    @Test
    void GivenAnEntryListedInCompatibilityForms_WhenItsAsciiFormIsChecked_ThenItIsCommon() {
        assertThat(policy.isCommon("secret7")).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"password1!", "password", "passwordl", "s3cret!42"})
    void GivenAPasswordThatIsNotAListedOne_WhenItIsChecked_ThenItIsNotCommon(String password) {
        // A superstring, a prefix and a near miss are all different passwords: the list is matched whole.
        assertThat(policy.isCommon(password)).isFalse();
    }
}
