package com.healthupgrades.auth.adapter.out.resource;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.auth.adapter.in.web.AuthController;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * What the shipped common-password list holds, and that its notice travels with it (ADR-071).
 *
 * <p>The list is derived by {@code scripts/derive_common_passwords.py}. These are the properties a
 * hand edit, or a derivation gone wrong, would break: the size, the shapes the derivation keeps, and
 * the SHA-256 the notice records. The notice is checked for SecLists' MIT notice, which must ship with
 * every copy.
 */
class CommonPasswordListTest {

    /** The owner's decision on S5.A6. */
    static final int ENTRIES = 10_000;

    private static final String NOTICE = "auth/common-passwords-NOTICE.txt";

    /** The line of the notice that records the derived list's digest. */
    private static final Pattern DERIVED_DIGEST = Pattern.compile("Derived SHA-256:\\s+([0-9a-f]{64})");

    /** Printable ASCII, the space included, which is all the derivation keeps. */
    private static final Pattern PRINTABLE_ASCII = Pattern.compile("[\\x20-\\x7e]+");

    @Test
    void GivenTheShippedList_WhenItIsRead_ThenItHoldsTenThousandDistinctEntries() throws IOException {
        List<String> entries = shippedEntries();

        assertThat(entries).hasSize(ENTRIES).doesNotHaveDuplicates();
    }

    @Test
    void GivenTheShippedList_WhenEachEntryIsRead_ThenItHasTheShapeTheDerivationKeeps() throws IOException {
        // At least the minimum, since a shorter one is refused already; printable ASCII with no "@";
        // and already lower case, which on ASCII is the whole of the normalisation.
        assertThat(shippedEntries())
                .allSatisfy(entry -> assertThat(entry)
                        .hasSizeGreaterThanOrEqualTo(AuthController.PASSWORD_MIN)
                        .matches(PRINTABLE_ASCII)
                        .doesNotContain("@")
                        .isEqualTo(entry.toLowerCase(Locale.ROOT)));
    }

    @Test
    void GivenTheShippedList_WhenItIsHashed_ThenItMatchesTheDigestItsNoticeRecords() throws Exception {
        Matcher recorded = DERIVED_DIGEST.matcher(read(NOTICE));

        assertThat(recorded.find()).as("the notice records the derived SHA-256").isTrue();
        assertThat(sha256(bytes(ClasspathCommonPasswordList.LOCATION))).isEqualTo(recorded.group(1));
    }

    @Test
    void GivenTheNotice_WhenItIsRead_ThenItCarriesSecListsMitNoticeAndThePin() throws IOException {
        assertThat(read(NOTICE))
                .contains("Copyright (c) 2018 Daniel Miessler")
                .contains("Permission is hereby granted, free of charge")
                .contains("https://github.com/danielmiessler/SecLists")
                .contains("0008f4343439b48257c0815fc797955d0ab1e755");
    }

    private static List<String> shippedEntries() throws IOException {
        return read(ClasspathCommonPasswordList.LOCATION).lines().toList();
    }

    private static String read(String location) throws IOException {
        return new String(bytes(location), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String location) throws IOException {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
