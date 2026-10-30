package com.healthupgrades.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.user.domain.model.Role;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * NFR-6 for the admin context's wire records: Spring MVC writes a response body's {@code toString} at
 * DEBUG, and a whole one at TRACE, so the account list must not print anybody's name or address.
 */
class AdminWireRecordsTest {

    private static final String NAME = "Dana Example";
    private static final String EMAIL = "dana@example.com";

    @Test
    void
            GivenAnAccountAsAnAdministratorSeesIt_WhenItIsPrinted_ThenItIsNamedByIdAndNeitherItsNameNorItsAddressAppears() {
        // At TRACE, GET /api/admin/users writes every account on the page, so this is everybody's.
        UUID id = UUID.randomUUID();

        String printed =
                new AdminAccountDto(id, NAME, EMAIL, Role.USER, true, LocalDateTime.of(2026, 9, 1, 8, 0)).toString();

        assertThat(printed).contains(id.toString()).doesNotContain(NAME).doesNotContain(EMAIL);
    }
}
