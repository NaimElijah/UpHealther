package com.healthupgrades.common.domain.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * NFR-6 for the kernel's account record: printed by accident, in a log line or an assertion message, it
 * names the account by id and gives away neither its address nor its password hash.
 */
class AccountCredentialsTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuvwxyz012345678901234567890123456";

    @Test
    void GivenAnAccountsCredentials_WhenTheyArePrinted_ThenNeitherTheAddressNorTheHashAppears() {
        UUID id = UUID.randomUUID();

        String printed = new AccountCredentials(id, EMAIL, PASSWORD_HASH, "ADMIN", true).toString();

        assertThat(printed)
                .contains(id.toString(), "ADMIN")
                .doesNotContain(EMAIL)
                .doesNotContain(PASSWORD_HASH);
    }
}
