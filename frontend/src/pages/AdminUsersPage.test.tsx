import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, act, fireEvent, within } from '@testing-library/react';
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

/** Lets pending promises run, so a mutation started by a click has reached its `mutationFn`. */
async function settle() {
  await act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));
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

  // A second click while the first change is in flight would send it twice: a second ADMIN_DISABLE
  // entry, or a 409 shown as "did not go through" for a change that did. TanStack runs mutationFn a
  // microtask after mutate, so each check lets pending work run before it counts the calls.

  it('GivenADisableInFlight_WhenConfirmIsClickedAgain_ThenTheAccountIsDisabledOnce', async () => {
    disableAccount.mockReturnValue(new Promise(() => {}));
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Disable');
    await click('Confirm');
    await settle();
    await click('Confirm');
    await settle();

    expect(disableAccount).toHaveBeenCalledTimes(1);
  });

  it('GivenARoleChangeInFlight_WhenConfirmIsClickedAgain_ThenTheRoleIsChangedOnce', async () => {
    changeAccountRole.mockReturnValue(new Promise(() => {}));
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Make admin');
    await click('Confirm');
    await settle();
    await click('Confirm');
    await settle();

    expect(changeAccountRole).toHaveBeenCalledTimes(1);
  });

  it('GivenARoleChangeInFlight_WhenTheDialogIsCancelled_ThenItStaysOpenUntilTheAnswer', async () => {
    let answer: (account: AdminAccount) => void = () => {};
    changeAccountRole.mockReturnValue(new Promise<AdminAccount>((resolve) => (answer = resolve)));
    renderPage();
    await waitFor(() => expect(screen.getByText('Someone Else')).toBeDefined());

    await click('Make admin');
    await click('Confirm');
    await settle();
    await click('Cancel');
    await act(async () => {
      fireEvent.keyDown(document, { key: 'Escape' });
    });

    expect(screen.getByText('Make Someone Else an administrator?')).toBeDefined();
    await act(async () => answer({ ...SOMEBODY, role: 'ADMIN' }));
    await waitFor(() => expect(screen.queryByText('Make Someone Else an administrator?')).toBeNull());
  });

  it('GivenAnEnableInFlight_WhenEnableIsClickedAgain_ThenTheAccountIsEnabledOnce', async () => {
    enableAccount.mockReturnValue(new Promise(() => {}));
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, { ...SOMEBODY, enabled: false }], 2));
    renderPage();
    await waitFor(() => expect(screen.getByText('Enable')).toBeDefined());

    await click('Enable');
    await settle();
    await click('Enable');
    await settle();

    expect(enableAccount).toHaveBeenCalledTimes(1);
  });

  it('GivenAnEnableAnsweredButTheListNotYetReRead_WhenEnableIsClickedAgain_ThenTheAccountIsEnabledOnce', async () => {
    // The row still offers Enable until the list is read again, so the button has to stay busy until then.
    listAccounts
      .mockResolvedValueOnce(page([MY_ACCOUNT, { ...SOMEBODY, enabled: false }], 2))
      .mockReturnValue(new Promise(() => {}));
    renderPage();
    await waitFor(() => expect(screen.getByText('Enable')).toBeDefined());

    await click('Enable');
    await settle();
    await waitFor(() => expect(listAccounts).toHaveBeenCalledTimes(2));
    await click('Enable');
    await settle();

    expect(enableAccount).toHaveBeenCalledTimes(1);
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

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('AdminUsersPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';
    listAccounts.mockResolvedValue(page([]));

    renderPage();

    expect(document.title).toBe('Accounts – UpHealther');
  });
});

/**
 * The controls an administrator was using must keep focus, or hand it on, when the list changes under
 * them. Paging replaced the whole page with a spinner, and Disable and Enable replace each other, so
 * focus fell to `<body>` after each (WCAG 2.4.3, finding X9.4).
 */
describe('AdminUsersPage focus', () => {
  beforeEach(() => {
    listAccounts.mockReset();
    disableAccount.mockReset();
    enableAccount.mockReset();
    changeAccountRole.mockReset();
  });

  it('GivenAnotherPageIsLoading_WhenNextWasPressed_ThenTheListAndThePagingControlsStay', async () => {
    listAccounts.mockResolvedValueOnce(page([MY_ACCOUNT, SOMEBODY], 60)).mockReturnValue(new Promise(() => {}));
    renderPage();
    const next = await screen.findByRole('button', { name: 'Next' });
    next.focus();

    fireEvent.click(next);
    await settle();

    expect(screen.getByText('Someone Else')).toBeDefined();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Next' }));
  });

  it('GivenTheLastPageIsReached_WhenNextWasPressed_ThenFocusMovesToPrevious', async () => {
    listAccounts.mockImplementation((pageNumber: number) => Promise.resolve(page([SOMEBODY], 26, pageNumber)));
    renderPage();
    const next = await screen.findByRole('button', { name: 'Next' });
    next.focus();

    fireEvent.click(next);

    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Previous' })));
  });

  it('GivenAnAccountIsDisabled_WhenTheConfirmationCloses_ThenFocusIsOnItsEnable', async () => {
    let enabled = true;
    listAccounts.mockImplementation(() => Promise.resolve(page([MY_ACCOUNT, { ...SOMEBODY, enabled }], 2)));
    disableAccount.mockImplementation(() => {
      enabled = false;
      return Promise.resolve({ ...SOMEBODY, enabled: false });
    });
    renderPage();
    const disable = await screen.findByRole('button', { name: /^Disable / });
    disable.focus();
    fireEvent.click(disable);

    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: /^Enable / })));
  });

  // Enable was disabled while its request ran, and a disabled button gives up focus in a browser.
  it('GivenAnEnableInFlight_WhenItsButtonHasFocus_ThenTheButtonKeepsItAndSaysItIsBusy', async () => {
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, { ...SOMEBODY, enabled: false }], 2));
    enableAccount.mockReturnValue(new Promise(() => {}));
    renderPage();
    const enable = await screen.findByRole('button', { name: /^Enable / });
    enable.focus();

    fireEvent.click(enable);
    await settle();

    expect(enable.hasAttribute('disabled')).toBe(false);
    expect(enable.getAttribute('aria-busy')).toBe('true');
    expect(document.activeElement).toBe(enable);
  });

  it('GivenADisabledAccount_WhenItIsEnabled_ThenFocusIsOnItsDisable', async () => {
    let enabled = false;
    listAccounts.mockImplementation(() => Promise.resolve(page([MY_ACCOUNT, { ...SOMEBODY, enabled }], 2)));
    enableAccount.mockImplementation(() => {
      enabled = true;
      return Promise.resolve(SOMEBODY);
    });
    renderPage();
    const enable = await screen.findByRole('button', { name: /^Enable / });
    enable.focus();

    fireEvent.click(enable);

    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: /^Disable / })));
  });
});

/**
 * An account's buttons name the account they act on, after the word they show (WCAG 2.4.6, 4.1.2, 2.5.3,
 * finding X9.20).
 */
describe('AdminUsersPage per-account names', () => {
  beforeEach(() => {
    listAccounts.mockReset();
  });

  it('GivenAnEnabledAccount_WhenItsRowRenders_ThenItsButtonsNameIt', async () => {
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, SOMEBODY], 2));
    renderPage();

    expect(await screen.findByRole('button', { name: 'Disable Someone Else' })).toBeDefined();
    expect(screen.getByRole('button', { name: 'Make admin for Someone Else' })).toBeDefined();
  });

  it('GivenADisabledAdministrator_WhenTheirRowRenders_ThenItsButtonsNameThem', async () => {
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, { ...SOMEBODY, enabled: false, role: 'ADMIN' }], 2));
    renderPage();

    expect(await screen.findByRole('button', { name: 'Enable Someone Else' })).toBeDefined();
    expect(screen.getByRole('button', { name: 'Revoke admin for Someone Else' })).toBeDefined();
  });
});

/**
 * A confirmation says whose account it changes in its body, not only in its title, which a narrow window
 * cut short (WCAG 1.4.10, finding X9.21).
 */
describe('AdminUsersPage confirmations', () => {
  beforeEach(() => {
    listAccounts.mockReset();
    listAccounts.mockResolvedValue(page([MY_ACCOUNT, SOMEBODY], 2));
  });

  it.each([/^Disable /, /^Make admin /])(
    'GivenAnAccount_When%sIsPressed_ThenTheConfirmationsBodyNamesIt',
    async (name) => {
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name }));

      const body = within(screen.getByRole('dialog')).getByText(/will/);
      expect(body.textContent).toContain('Someone Else');
      expect(body.textContent).toContain('someone@example.com');
    },
  );
});
