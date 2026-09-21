package com.healthupgrades.admin.adapter.in.web;

import com.healthupgrades.admin.application.AccountPage;
import com.healthupgrades.admin.application.port.in.AdminUserCommand;
import com.healthupgrades.admin.application.port.in.AdminUserQuery;
import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Inbound web adapter for account administration.
 *
 * <p>{@code @PreAuthorize} on the class as well as the {@code /api/admin/**} matcher in
 * {@code SecurityConfig}. The two say the same thing on purpose: the annotation is the rule a reader of
 * this class sees, and the matcher is what closes a path somebody adds here later and forgets to
 * annotate. Neither is redundant, because they fail in opposite directions.
 *
 * <p>Every method threads the caller's own id down. The service refuses an administrator acting on
 * their own account, and it cannot enforce that without knowing who is asking.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    /** The page size used when a caller names none. */
    static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * The largest page this endpoint will serve.
     *
     * <p>Bounded because the parameter is a caller's, and an unbounded one is an invitation to read the
     * whole table in a single request — which is both a slow query and, for a list of every account on
     * the installation, a generous thing to hand anybody who has just acquired an admin token.
     */
    static final int MAX_PAGE_SIZE = 100;

    private final AdminUserQuery adminUserQuery;
    private final AdminUserCommand adminUserCommand;

    /**
     * Lists accounts, oldest first.
     *
     * @param page zero-based page number
     * @param size how many per page, at most {@link #MAX_PAGE_SIZE}
     * @return 200 with the page and the total behind it
     */
    @GetMapping
    public ResponseEntity<AccountPageDto> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(toPageDto(adminUserQuery.list(page, size)));
    }

    /**
     * Switches an account off and ends every session it holds.
     *
     * @return 200 with the account as it now stands
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<AdminAccountDto> disable(@AuthenticationPrincipal SecurityUser principal,
                                           @PathVariable UUID id) {
        return ResponseEntity.ok(toAccountDto(adminUserCommand.disable(principal.getId(), id)));
    }

    /** Switches an account back on. */
    @PostMapping("/{id}/enable")
    public ResponseEntity<AdminAccountDto> enable(@AuthenticationPrincipal SecurityUser principal,
                                          @PathVariable UUID id) {
        return ResponseEntity.ok(toAccountDto(adminUserCommand.enable(principal.getId(), id)));
    }

    /** Grants or revokes a role. */
    @PutMapping("/{id}/role")
    public ResponseEntity<AdminAccountDto> changeRole(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody RoleRequest request) {
        return ResponseEntity.ok(toAccountDto(adminUserCommand.changeRole(principal.getId(), id, request.role())));
    }

    private AccountPageDto toPageDto(AccountPage page) {
        List<AdminAccountDto> accounts =
                page.accounts().stream().map(AdminUserController::toAccountDto).toList();
        return new AccountPageDto(accounts, page.page(), page.size(), page.total());
    }

    private static AdminAccountDto toAccountDto(User user) {
        return new AdminAccountDto(user.getId(), user.getName(), user.getEmail(), user.getRole(),
                user.isEnabled(), user.getCreatedAt());
    }

    /**
     * The body of a role change.
     *
     * <p>A record with one field rather than a bare enum in the body, so adding a second field later —
     * a reason, say — does not change the shape of the request.
     */
    public record RoleRequest(@NotNull Role role) {
    }
}
