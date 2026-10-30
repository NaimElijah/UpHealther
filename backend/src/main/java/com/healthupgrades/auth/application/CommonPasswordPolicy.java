package com.healthupgrades.auth.application;

import static java.util.stream.Collectors.toUnmodifiableSet;

import com.healthupgrades.auth.domain.port.out.CommonPasswordListPort;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides whether a password is on the common-password list that registration refuses (BR-17, ADR-071).
 *
 * <p>A password is common when its NFKC form, lower-cased, is on the list, and each entry is normalised
 * the same way as it is loaded. NFKC folds compatibility forms onto the characters they stand for, so
 * {@code password1} typed in fullwidth letters is {@code password1}; lower-casing makes the match blind to
 * case. Nothing else is compared: not the email, the name or this service's name, by the owner's
 * decision on S5.A6.
 *
 * <p>The list is read once, when the bean is built at startup, into an immutable set. A list that
 * cannot be read fails that construction, so the application does not start without the rule.
 */
@Component
public class CommonPasswordPolicy {

    private final Set<String> normalisedEntries;

    /**
     * Loads and normalises the list.
     *
     * @param list the port that supplies the entries
     * @throws IllegalStateException if the port cannot supply them
     */
    public CommonPasswordPolicy(CommonPasswordListPort list) {
        this.normalisedEntries =
                list.entries().stream().map(CommonPasswordPolicy::normalise).collect(toUnmodifiableSet());
    }

    /**
     * Whether registration must refuse this password as a common one.
     *
     * @param password the password as submitted; not null
     * @return true when its normalised form is on the list
     */
    public boolean isCommon(String password) {
        return normalisedEntries.contains(normalise(password));
    }

    /** NFKC first, then lower case: the order the rule states, and the derivation script's. */
    private static String normalise(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
