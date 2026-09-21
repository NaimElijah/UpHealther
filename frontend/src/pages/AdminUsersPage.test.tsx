import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import AdminUsersPage from './AdminUsersPage';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';
import type { AccountPage, AdminAccount, User } from '../types';

const listAccounts = vi.fn();
const disableAccount = vi.fn();
const enableAccount = vi.fn();
const changeAccountRole = vi.fn();

vi.mock('../api/admin', () => ({
  ACCOUNTS_PAGE_SIZE: 25,
  listAccounts: (...args: unknown[]) => listAccounts(...args),
  disableAccount: (...args: unknown[]) => disableAccount(...args),
  enableAccount: (...args: unknown[]) => enableAccount(...args),
  changeAccountRole: (...args: unknown[]) => changeAccountRole(...args),
}));

const ME: User = {
  id: 'admin-1',
  name: 'An Administrator',
  email: 'admin@example.com',
  role: 'ADMIN',
  createdAt: '2026-03-15T09:00:00',
};

const MY_ACCOUNT: AdminAccount = {
  id: ME.id,
  name: ME.name,
  email: ME.email,
  role: 'ADMIN',
  enabled: true,
  createdAt: ME.createdAt,
};

const SOMEBODY: AdminAccount = {
  id: 'user-2',
  name: 'Someone Else',
  email: 'someone@example.com',
  role: 'USER',
  enabled: true,
  createdAt: '2026-04-01T09:00:00',
};

function page(accounts: AdminAccount[], total = accounts.length, number = 0): AccountPage {
  return { accounts, page: number, size: 25, total };
}

function renderPage() {
  const ctx: AuthContextType = {
    user: ME,
    isLoading: false,
    isAuthenticated: true,
    login: async () => {},
    register: async () => {},
    logout: async () => {},
  };
  // retry: false so a rejected query surfaces as an error instead of being retried past the assertion.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={ctx}>
        <MemoryRouter>
          <AdminUsersPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

/** Clicks a button by its label, inside act so React state settles before the assertion. */
async function click(label: string) {
  await act(async () => {
    screen.getByText(label).click();
  });
}

/**
 * The administration page, and the three things about it that are decisions rather than markup.
 *
 * **The administrator's own row carries no buttons.** The server refuses a self-directed change with a
 * 422 — offering controls that can only fail is worse than offering none, so the row explains itself.
 * This is the assertion most likely to be broken by a later refactor that treats every row alike.
 *
 * **A destructive change is confirmed first.** Disabling an account signs somebody out of every device
 * they own; granting the role hands that power to somebody else. The test that matters is the negative
 * one: opening the dialog and *not* confirming must leave the account untouched.
 *
 * **A failure is shown.** A mutation that fails has to say so — a silent no-op leaves an administrator
 * believing they disabled an account that is still live.
 */
describe('AdminUsersPage', () => {
  beforeEach(() => {
    listAccounts.mockReset();
    disableAccount.mockReset();
    enableAccount.mockReset();
    changeAccountRole.mockReset();
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, SOMEBODY], 2));
    disableAccount.mockResolvedValue({ ...SOMEBODY, enabled: false });
    enableAccount.mockResolvedValue(SOMEBODY);
    changeAccountRole.mockResolvedValue({ ...SOMEBODY, role: 'ADMIN' });
  });

  it('GivenAccountsExist_WhenThePageLoads_ThenEachIsListedWithItsRoleAndState', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());
    expect(screen.getByText('someone@example.com')).toBeDefined();
    expect(screen.getByText('Administrator')).toBeDefined();
    expect(screen.getByText('2 accounts on this installation')).toBeDefined();
  });

  it('GivenADisabledAccount_WhenThePageLoads_ThenItIsMarkedDisabledAndOfferedEnable', async () => {
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, { ...SOMEBODY, enabled: false }], 2));

    renderPage();

    await waitFor(() => expect(screen.getByText('Disabled')).toBeDefined());
    expect(screen.getByText('Enable')).toBeDefined();
    expect(screen.queryByText('Disable')).toBeNull();
  });

  it('GivenTheAdministratorsOwnRow_WhenItIsRendered_ThenItOffersNoControlsAndSaysWhy', async () => {
    // The server answers 422 for any self-directed change. Buttons that can only fail are worse than
    // none — and there must be exactly one Disable button on a two-row page, not two.
    renderPage();

    await waitFor(() => expect(screen.getByText('An Administrator')).toBeDefined());
    expect(screen.getByText('This is you')).toBeDefined();
    expect(screen.getAllByText('Disable')).toHaveLength(1);
    expect(screen.queryAllByText('Revoke admin')).toHaveLength(0);
  });

  it('GivenADisableIsStarted_WhenItIsNotConfirmed_ThenTheAccountIsLeftAlone', async () => {
    // The whole point of the confirmation. Cancelling must be a no-op, not a delayed yes.
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Disable');
    expect(screen.getByText('Disable Someone Else?')).toBeDefined();
    await click('Cancel');

    expect(disableAccount).not.toHaveBeenCalled();
  });

  it('GivenADisableIsConfirmed_WhenTheDialogIsAccepted_ThenTheAccountIsSwitchedOff', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Disable');
    await click('Confirm');

    expect(disableAccount).toHaveBeenCalledWith(SOMEBODY.id);
  });

  it('GivenTheConfirmation_WhenItIsShown_ThenItSaysWhatDisablingActuallyDoes', async () => {
    // Somebody is about to be signed out of every device they own. The dialog says so, and says that
    // nothing is deleted, because both halves change whether this is the right button to press.
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Disable');

    const body = screen.getByText(/signed out everywhere/i);
    expect(body).toBeDefined();
    expect(body.textContent).toContain('Nothing they have recorded is deleted');
  });

  it('GivenARoleGrantIsConfirmed_WhenTheDialogIsAccepted_ThenTheAccountBecomesAnAdministrator', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Make admin');
    await click('Confirm');

    expect(changeAccountRole).toHaveBeenCalledWith(SOMEBODY.id, 'ADMIN');
  });

  it('GivenAnAdministratorOtherThanMe_WhenTheirRoleIsRevoked_ThenItIsChangedBackToUser', async () => {
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, { ...SOMEBODY, role: 'ADMIN' }], 2));
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Revoke admin');
    await click('Confirm');

    expect(changeAccountRole).toHaveBeenCalledWith(SOMEBODY.id, 'USER');
  });

  it('GivenAnEnableIsClicked_WhenTheAccountIsOff_ThenItIsSwitchedOnWithoutAConfirmation', async () => {
    // Deliberately unconfirmed: restoring access is reversible and harms nobody if mis-clicked, which
    // is the opposite of the other three.
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, { ...SOMEBODY, enabled: false }], 2));
    renderPage();
    await waitFor(() => expect(screen.getByText('Enable')).toBeDefined());

    await click('Enable');

    expect(enableAccount).toHaveBeenCalledWith(SOMEBODY.id);
  });

  it('GivenTheListCannotBeLoaded_WhenThePageRenders_ThenItSaysSoRatherThanShowingNothing', async () => {
    listAccounts.mockRejectedValue(new Error('boom'));

    renderPage();

    await waitFor(() => expect(screen.getByText('Could not load the accounts.')).toBeDefined());
  });

  it('GivenAChangeThatFails_WhenItIsConfirmed_ThenTheFailureIsShownRatherThanSwallowed', async () => {
    // A silent no-op would leave an administrator believing they had disabled an account that is
    // still live and still signed in.
    disableAccount.mockRejectedValue(new Error('boom'));
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Disable');
    await click('Confirm');

    await waitFor(() => expect(screen.getByText('That change did not go through.')).toBeDefined());
  });

  it('GivenMoreAccountsThanOnePage_WhenNextIsUsed_ThenTheFollowingPageIsRequested', async () => {
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, SOMEBODY], 60));
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Next');

    await waitFor(() => expect(listAccounts).toHaveBeenCalledWith(1));
  });

  it('GivenASinglePageOfAccounts_WhenThePageRenders_ThenNoPagingControlsAreShown', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());
    expect(screen.queryByText('Next')).toBeNull();
    expect(screen.queryByText('Previous')).toBeNull();
  });
});
