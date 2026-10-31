import React, { useRef, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ACCOUNTS_PAGE_SIZE, changeAccountRole, disableAccount, enableAccount, listAccounts } from '../api/admin';
import { toApiError } from '../api/apiError';
import { useAuth } from '../hooks/useAuth';
import PageContainer from '../components/ui/PageContainer';
import PageHeader from '../components/ui/PageHeader';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Modal from '../components/ui/Modal';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import ErrorState from '../components/ui/ErrorState';
import type { AdminAccount, UserRole } from '../types';
import { useFocusWhenReady } from '../hooks/useFocusWhenReady';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/** The query key for the account list, so every mutation invalidates the same thing. */
const ACCOUNTS_KEY = ['adminAccounts'];

/** What a pending confirmation is about, so one dialog serves all three changes. */
type PendingChange =
  | { kind: 'disable'; account: AdminAccount }
  | { kind: 'promote'; account: AdminAccount }
  | { kind: 'demote'; account: AdminAccount };

/**
 * Account administration: who exists, what they may do, and whether they are switched on.
 *
 * Three things here are deliberate rather than incidental.
 *
 * **The administrator's own row is inert.** The server refuses a self-directed change with a 422, and
 * showing buttons that can only fail is a worse experience than showing none — so the row says why
 * instead. The rule is the server's; this is the explanation.
 *
 * **Every change that takes something away is confirmed.** Disabling an account signs somebody out of
 * every device they own; granting the role hands over the ability to do that to anybody; revoking it
 * takes it back. None of those belongs behind a single click on a row a mis-aimed pointer can find.
 * Enabling is the exception, deliberately: it is the recovery action, it restores an account to
 * exactly what it was, and a mis-click is undone by the Disable button next to it. A dialog in front
 * of the one safe action would be noise, and noise is what teaches people to click through dialogs.
 *
 * **A failure is shown with its trace id**, through `ErrorState` and `toApiError`, rather than being
 * swallowed into a silent no-op — the change did not happen, and the person needs to know that.
 */
const AdminUsersPage: React.FC = () => {
  useDocumentTitle('Accounts');
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const [page, setPage] = useState(0);
  const [pending, setPending] = useState<PendingChange | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  // The page being left stays on screen while the next one loads. Without it the whole page became a
  // spinner, Previous and Next went with it, and focus fell to <body> on every page turn.
  const { data, isLoading, error } = useQuery({
    queryKey: [...ACCOUNTS_KEY, page],
    queryFn: () => listAccounts(page),
    placeholderData: keepPreviousData,
  });

  const previousButton = useRef<HTMLButtonElement>(null);
  const nextButton = useRef<HTMLButtonElement>(null);
  const focusWhenReady = useFocusWhenReady();

  /**
   * Turns the page. Reaching the first or the last page disables the button just pressed, and a disabled
   * button drops focus, so focus moves to the other one.
   */
  const turnTo = (target: number) => {
    setPage(target);
    focusWhenReady(() => {
      const [pressed, other] = target > page ? [nextButton, previousButton] : [previousButton, nextButton];
      return pressed.current?.disabled ? other.current : null;
    });
  };

  /**
   * Every change re-reads the list rather than patching a row, so what is shown is what was saved. The
   * re-read is awaited, so the mutation stays pending until the row shows the change: until then the
   * row still offers the control just used, and a second click would send the change again.
   */
  const afterChange = {
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY });
      setPending(null);
      setActionError(null);
    },
    onError: (failure: unknown) => {
      setActionError(failure);
      setPending(null);
    },
  };

  // Wrapped rather than passed by reference: React Query hands mutationFn a second context
  // argument, which a bare reference would forward into the API function as an extra parameter.
  const disabling = useMutation({ mutationFn: (id: string) => disableAccount(id), ...afterChange });
  const enabling = useMutation({ mutationFn: (id: string) => enableAccount(id), ...afterChange });
  const changingRole = useMutation({
    mutationFn: ({ id, role }: { id: string; role: UserRole }) => changeAccountRole(id, role),
    ...afterChange,
  });

  const confirming = disabling.isPending || changingRole.isPending;

  /**
   * Closes the confirmation unless its change is in flight: the answer closes it, and closing it first
   * would let a second change start before the first had landed.
   */
  const closeConfirmation = () => {
    if (!confirming) setPending(null);
  };

  const confirm = () => {
    if (!pending) return;
    if (pending.kind === 'disable') disabling.mutate(pending.account.id);
    if (pending.kind === 'promote') changingRole.mutate({ id: pending.account.id, role: 'ADMIN' });
    if (pending.kind === 'demote') changingRole.mutate({ id: pending.account.id, role: 'USER' });
  };

  if (isLoading) {
    return (
      <PageContainer>
        <PageHeader title="Accounts" subtitle="Everyone with an account on this installation" />
        <div className="flex justify-center py-16">
          <LoadingSpinner size="lg" />
        </div>
      </PageContainer>
    );
  }

  if (error) {
    return (
      <PageContainer>
        <PageHeader title="Accounts" subtitle="Everyone with an account on this installation" />
        <ErrorState title="Could not load the accounts." error={toApiError(error)} />
      </PageContainer>
    );
  }

  const accounts = data?.accounts ?? [];
  const total = data?.total ?? 0;
  const hasMore = (page + 1) * ACCOUNTS_PAGE_SIZE < total;

  return (
    <PageContainer>
      <PageHeader title="Accounts" subtitle={`${total} account${total === 1 ? '' : 's'} on this installation`} />

      {actionError !== null && (
        <div className="mb-4">
          <ErrorState title="That change did not go through." error={toApiError(actionError)} />
        </div>
      )}

      {accounts.length === 0 ? (
        <EmptyState icon="👤" title="No accounts" description="Nobody has registered yet." />
      ) : (
        <div className="flex flex-col gap-3">
          {accounts.map((account) => {
            const isSelf = account.id === user?.id;
            return (
              <Card key={account.id}>
                <div className="flex flex-wrap items-center gap-3 justify-between">
                  {/* Wrapped, not truncated: at 320px an address cut to an ellipsis is no longer one an
                      administrator can tell from another (WCAG 1.4.10). */}
                  <div className="min-w-0">
                    <p className="font-medium text-fg break-words">{account.name}</p>
                    <p className="text-sm text-fg-subtle break-all">{account.email}</p>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {account.role === 'ADMIN' && <Badge variant="purple">Administrator</Badge>}
                    {!account.enabled && <Badge variant="red">Disabled</Badge>}
                    {isSelf ? (
                      // The server refuses a self-directed change with a 422. Buttons that can only
                      // fail are worse than none, so the row explains itself instead.
                      <span className="text-sm text-fg-subtle">This is you</span>
                    ) : (
                      <>
                        {account.enabled ? (
                          <Button
                            variant="ghost"
                            size="sm"
                            aria-label={`Disable ${account.name}`}
                            onClick={() => setPending({ kind: 'disable', account })}
                          >
                            Disable
                          </Button>
                        ) : (
                          // Loading rather than disabled, so it keeps focus: the row then turns it into
                          // Disable, the same button, and focus stays on it.
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={enabling.isPending}
                            loading={enabling.isPending && enabling.variables === account.id}
                            aria-label={`Enable ${account.name}`}
                            onClick={() => enabling.mutate(account.id)}
                          >
                            Enable
                          </Button>
                        )}
                        {/* Each of the row's buttons adds the account to the word it shows, so a list of
                            rows does not read "Disable, Make admin" over and over (WCAG 2.4.6, 2.5.3). */}
                        <Button
                          variant="ghost"
                          size="sm"
                          aria-label={`${account.role === 'ADMIN' ? 'Revoke admin' : 'Make admin'} for ${account.name}`}
                          onClick={() =>
                            setPending({
                              kind: account.role === 'ADMIN' ? 'demote' : 'promote',
                              account,
                            })
                          }
                        >
                          {account.role === 'ADMIN' ? 'Revoke admin' : 'Make admin'}
                        </Button>
                      </>
                    )}
                  </div>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {(page > 0 || hasMore) && (
        <div className="flex items-center justify-between mt-6">
          <Button ref={previousButton} variant="ghost" size="sm" disabled={page === 0} onClick={() => turnTo(page - 1)}>
            Previous
          </Button>
          <span className="text-sm text-fg-subtle">Page {page + 1}</span>
          <Button ref={nextButton} variant="ghost" size="sm" disabled={!hasMore} onClick={() => turnTo(page + 1)}>
            Next
          </Button>
        </div>
      )}

      <Modal isOpen={pending !== null} onClose={closeConfirmation} title={pending ? confirmTitle(pending) : ''}>
        {pending && (
          <div className="flex flex-col gap-4">
            <p className="text-fg-subtle break-words">{confirmBody(pending)}</p>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={closeConfirmation} disabled={confirming}>
                Cancel
              </Button>
              <Button variant="primary" loading={confirming} onClick={confirm}>
                Confirm
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </PageContainer>
  );
};

function confirmTitle(pending: PendingChange): string {
  if (pending.kind === 'disable') return `Disable ${pending.account.name}?`;
  if (pending.kind === 'promote') return `Make ${pending.account.name} an administrator?`;
  return `Revoke ${pending.account.name}'s administrator role?`;
}

/**
 * What a change does, said of the account by name and address. The title names the account too, but the
 * body is what is read in full, whatever the window's width, and two accounts can share a name.
 */
function confirmBody(pending: PendingChange): string {
  const whose = `${pending.account.name} (${pending.account.email})`;
  if (pending.kind === 'disable') {
    return (
      `${whose} will be signed out everywhere and will not be able to sign in again. Nothing they ` +
      'have recorded is deleted, and enabling the account restores it exactly as it was.'
    );
  }
  if (pending.kind === 'promote') {
    return (
      `${whose} will be able to list every account here, switch accounts off, and grant this role to ` +
      'anybody else. They still will not be able to read anyone else’s records.'
    );
  }
  return `${whose} will keep their own account and everything in it, and lose access to this page.`;
}

export default AdminUsersPage;
