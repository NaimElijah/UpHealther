import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ACCOUNTS_PAGE_SIZE,
  changeAccountRole,
  disableAccount,
  enableAccount,
  listAccounts,
} from '../api/admin';
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
import type { AdminAccount } from '../types';

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
 * **Every change is confirmed.** Disabling an account signs somebody out of every device they own, and
 * granting the role hands over the ability to do that to anybody. Neither belongs behind a single
 * click on a row that a mis-aimed pointer can find.
 *
 * **A failure is shown with its trace id**, through `ErrorState` and `toApiError`, rather than being
 * swallowed into a silent no-op — the change did not happen, and the person needs to know that.
 */
const AdminUsersPage: React.FC = () => {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const [page, setPage] = useState(0);
  const [pending, setPending] = useState<PendingChange | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: [...ACCOUNTS_KEY, page],
    queryFn: () => listAccounts(page),
  });

  /** Every change re-reads the list rather than patching a row, so what is shown is what was saved. */
  const afterChange = {
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY });
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
    mutationFn: ({ id, role }: { id: string; role: 'USER' | 'ADMIN' }) => changeAccountRole(id, role),
    ...afterChange,
  });

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
        <div className="flex justify-center py-16"><LoadingSpinner size="lg" /></div>
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
      <PageHeader
        title="Accounts"
        subtitle={`${total} account${total === 1 ? '' : 's'} on this installation`}
      />

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
                  <div className="min-w-0">
                    <p className="font-medium text-fg truncate">{account.name}</p>
                    <p className="text-sm text-fg-subtle truncate">{account.email}</p>
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
                            onClick={() => setPending({ kind: 'disable', account })}
                          >
                            Disable
                          </Button>
                        ) : (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => enabling.mutate(account.id)}
                          >
                            Enable
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="sm"
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
          <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <span className="text-sm text-fg-subtle">Page {page + 1}</span>
          <Button variant="ghost" size="sm" disabled={!hasMore} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      )}

      <Modal
        isOpen={pending !== null}
        onClose={() => setPending(null)}
        title={pending ? confirmTitle(pending) : ''}
      >
        {pending && (
          <div className="flex flex-col gap-4">
            <p className="text-fg-subtle">{confirmBody(pending)}</p>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setPending(null)}>Cancel</Button>
              <Button variant="primary" onClick={confirm}>Confirm</Button>
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

function confirmBody(pending: PendingChange): string {
  if (pending.kind === 'disable') {
    return 'They will be signed out everywhere and will not be able to sign in again. Nothing they '
      + 'have recorded is deleted, and enabling the account restores it exactly as it was.';
  }
  if (pending.kind === 'promote') {
    return 'They will be able to list every account here, switch accounts off, and grant this role to '
      + 'anybody else. They still will not be able to read anyone else’s records.';
  }
  return 'They will keep their own account and everything in it, and lose access to this page.';
}

export default AdminUsersPage;
