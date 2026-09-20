import client from './client';
import type { AccountPage, AdminAccount, UserRole } from '../types';

/**
 * The account-administration endpoints.
 *
 * Every one of these answers 403 for an ordinary user, whatever the interface happens to be showing.
 * The `RequireRole` gate and the admin-only sidebar link exist to keep somebody from walking into a
 * dead end — never as the thing that enforces this.
 */

/** The page size the list uses, matching the API's own default. */
export const ACCOUNTS_PAGE_SIZE = 25;

/**
 * Lists accounts, oldest first.
 *
 * @param page zero-based page number
 * @returns that page of accounts and the total behind it
 */
export const listAccounts = async (page = 0): Promise<AccountPage> => {
  const { data } = await client.get<AccountPage>('/api/admin/users', {
    params: { page, size: ACCOUNTS_PAGE_SIZE },
  });
  return data;
};

/**
 * Switches an account off, ending every session it holds.
 *
 * Rejects with 422 if an administrator aims at their own account, and 404 if there is no such account.
 */
export const disableAccount = async (id: string): Promise<AdminAccount> => {
  const { data } = await client.post<AdminAccount>(`/api/admin/users/${id}/disable`);
  return data;
};

/** Switches an account back on, with everything it owned still in place. */
export const enableAccount = async (id: string): Promise<AdminAccount> => {
  const { data } = await client.post<AdminAccount>(`/api/admin/users/${id}/enable`);
  return data;
};

/** Grants or revokes the administrator role. */
export const changeAccountRole = async (id: string, role: UserRole): Promise<AdminAccount> => {
  const { data } = await client.put<AdminAccount>(`/api/admin/users/${id}/role`, { role });
  return data;
};
