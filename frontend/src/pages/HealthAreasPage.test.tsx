import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import HealthAreasPage from './HealthAreasPage';
import type { HealthArea } from '../types';

const getHealthAreas = vi.fn();
const createHealthArea = vi.fn();
const updateHealthArea = vi.fn();

vi.mock('../api/healthAreas', () => ({
  getHealthAreas: (...a: unknown[]) => getHealthAreas(...a),
  createHealthArea: (...a: unknown[]) => createHealthArea(...a),
  updateHealthArea: (...a: unknown[]) => updateHealthArea(...a),
  deleteHealthArea: vi.fn(),
}));

const AREA_ID = 'area-1';

function anArea(values: Partial<HealthArea> = {}): HealthArea {
  return {
    id: AREA_ID,
    userId: 'user-1',
    name: 'Sleep',
    description: 'Rest and recovery',
    priority: 2,
    icon: '😴',
    color: '#6366f1',
    createdAt: '2026-03-01T09:00:00',
    ...values,
  };
}

/** A refusal shaped the way axios delivers one, so `toApiError` decodes it as it would in production. */
function apiFailure(status: number, body: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = { data: body, status, statusText: '', headers: new AxiosHeaders(), config } as AxiosResponse;
  return new AxiosError('Request failed', String(status), config, {}, response);
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <HealthAreasPage />
    </QueryClientProvider>,
  );
}

/** Opens the edit dialog for the only area listed, and returns queries scoped to it. */
async function openEdit() {
  await screen.findByText('Sleep');
  fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
  return within(screen.getByRole('dialog', { name: 'Edit Health Area' }));
}

/** Opens the create dialog, and returns queries scoped to it. */
async function openCreate() {
  fireEvent.click(await screen.findByRole('button', { name: '+ New Area' }));
  return within(screen.getByRole('dialog', { name: 'New Health Area' }));
}

/**
 * FR-7 (#91) — an area carries an optional priority, and the interface can set it.
 *
 * An update is a full replacement: the API stores null for every field the request leaves out. So a
 * form that does not carry the priority does not just fail to set one — every edit, of any field,
 * erases the one already there.
 */
describe('HealthAreasPage', () => {
  beforeEach(() => {
    getHealthAreas.mockReset();
    createHealthArea.mockReset();
    updateHealthArea.mockReset();
    getHealthAreas.mockResolvedValue([anArea()]);
    createHealthArea.mockResolvedValue(anArea());
    updateHealthArea.mockResolvedValue(anArea());
  });

  it('GivenAnAreaWithAPriority_WhenAnotherFieldIsEditedAndSaved_ThenThePriorityIsKept', async () => {
    renderPage();
    const dialog = await openEdit();

    fireEvent.change(dialog.getByLabelText('Name'), { target: { value: 'Sleep better' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => expect(updateHealthArea).toHaveBeenCalledTimes(1));
    expect(updateHealthArea).toHaveBeenCalledWith(AREA_ID, expect.objectContaining({ name: 'Sleep better', priority: 2 }));
  });

  it('GivenAnAreaWithAPriority_WhenItIsEdited_ThenTheFormShowsIt', async () => {
    renderPage();
    const dialog = await openEdit();

    expect((dialog.getByLabelText('Priority') as HTMLInputElement).value).toBe('2');
  });

  it('GivenAnAreaWithAPriority_WhenThePriorityIsChangedAndSaved_ThenTheNewOneIsSent', async () => {
    renderPage();
    const dialog = await openEdit();

    fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: '5' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => expect(updateHealthArea).toHaveBeenCalledTimes(1));
    expect(updateHealthArea.mock.calls[0][1]).toMatchObject({ priority: 5 });
  });

  it('GivenAnAreaWithAPriority_WhenThePriorityIsClearedAndSaved_ThenNoneIsSent', async () => {
    renderPage();
    const dialog = await openEdit();

    fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: '' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => expect(updateHealthArea).toHaveBeenCalledTimes(1));
    expect(updateHealthArea.mock.calls[0][1].priority).toBeUndefined();
  });

  it('GivenAnAreaWithNoPriority_WhenItIsEditedAndSaved_ThenNoneIsSent', async () => {
    getHealthAreas.mockResolvedValue([anArea({ priority: undefined })]);
    renderPage();
    const dialog = await openEdit();

    expect((dialog.getByLabelText('Priority') as HTMLInputElement).value).toBe('');
    fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => expect(updateHealthArea).toHaveBeenCalledTimes(1));
    expect(updateHealthArea.mock.calls[0][1].priority).toBeUndefined();
  });

  it('GivenANewArea_WhenAPriorityIsEntered_ThenItIsSent', async () => {
    renderPage();
    const dialog = await openCreate();

    fireEvent.change(dialog.getByLabelText('Name'), { target: { value: 'Nutrition' } });
    fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: '3' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(createHealthArea).toHaveBeenCalledTimes(1));
    expect(createHealthArea.mock.calls[0][0]).toMatchObject({ name: 'Nutrition', priority: 3 });
  });

  it('GivenANewAreaWithNoPriority_WhenItIsCreated_ThenNoneIsSent', async () => {
    renderPage();
    const dialog = await openCreate();

    fireEvent.change(dialog.getByLabelText('Name'), { target: { value: 'Nutrition' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(createHealthArea).toHaveBeenCalledTimes(1));
    expect(createHealthArea.mock.calls[0][0].priority).toBeUndefined();
  });

  it('GivenAFractionalPriority_WhenANewAreaIsSubmitted_ThenTheFormRefusesIt', async () => {
    // The API binds priority as an Integer, and Jackson truncates 1.5 to 1 rather than refusing it —
    // so the refusal has to happen here, or the user saves a number they did not type.
    renderPage();
    const dialog = await openCreate();

    fireEvent.change(dialog.getByLabelText('Name'), { target: { value: 'Nutrition' } });
    fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: '1.5' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Create' }));

    expect(await dialog.findByText('Priority must be a whole number.')).toBeDefined();
    expect(createHealthArea).not.toHaveBeenCalled();
  });

  it('GivenAFractionalPriority_WhenAnEditIsSaved_ThenTheFormRefusesIt', async () => {
    renderPage();
    const dialog = await openEdit();

    fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: '2.5' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));

    expect(await dialog.findByText('Priority must be a whole number.')).toBeDefined();
    expect(updateHealthArea).not.toHaveBeenCalled();
  });

  // The column is an INTEGER, and so is the API's field. A number past it was sent anyway, refused by
  // the JSON reader as a malformed body, and reported under Name — the one field that was fine.

  it.each(['2147483648', '-2147483649', '1e21'])(
    'GivenAPriorityPastTheColumnsRange %s_WhenSubmitted_ThenTheFormRefusesItBesideThePriority',
    async (priority) => {
      renderPage();
      const dialog = await openEdit();

      fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: priority } });
      fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));

      expect(await dialog.findByText('Priority must be between -2147483648 and 2147483647.')).toBeDefined();
      expect(updateHealthArea).not.toHaveBeenCalled();
    },
  );

  it.each([['2147483647', 2147483647], ['-2147483648', -2147483648]])(
    'GivenAPriorityAtTheColumnsLimit %s_WhenSubmitted_ThenItIsSent',
    async (typed, sent) => {
      renderPage();
      const dialog = await openEdit();

      fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: typed } });
      fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));

      await waitFor(() => expect(updateHealthArea).toHaveBeenCalledTimes(1));
      expect(updateHealthArea.mock.calls[0][1].priority).toBe(sent);
    },
  );

  it('GivenABlankNameAndAFractionalPriority_WhenANewAreaIsSubmitted_ThenBothAreRefusedAtOnce', async () => {
    // One submit, both answers: stopping at the name made the user fix it and submit again, only to be
    // told about the priority the second time.
    renderPage();
    const dialog = await openCreate();

    fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: '1.5' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Create' }));

    expect(await dialog.findByText('Name is required.')).toBeDefined();
    expect(dialog.getByText('Priority must be a whole number.')).toBeDefined();
    expect(createHealthArea).not.toHaveBeenCalled();
  });

  // Each dialog says why a submit was refused, and only its own refusal. The two dialogs share one form
  // and one pair of messages, so a message left over from the last submit would otherwise open with the
  // next dialog.

  it('GivenTheApiRefusesAnEdit_WhenItIsSaved_ThenTheDialogSaysWhy', async () => {
    updateHealthArea.mockRejectedValue(apiFailure(400, {
      message: 'Validation failed',
      fieldErrors: { name: 'size must be between 1 and 255' },
    }));
    renderPage();
    const dialog = await openEdit();

    fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));

    expect(await dialog.findByText('Size must be between 1 and 255')).toBeDefined();
  });

  it('GivenACreateWasRefused_WhenTheCreateDialogIsOpenedAgain_ThenItStartsWithoutTheOldMessage', async () => {
    renderPage();
    let dialog = await openCreate();
    fireEvent.submit(dialog.getByRole('button', { name: 'Create' }));
    await dialog.findByText('Name is required.');
    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));

    dialog = await openCreate();

    expect(dialog.queryByText('Name is required.')).toBeNull();
  });

  it('GivenACreateWasRefused_WhenAnAreaIsEdited_ThenTheEditDialogStartsWithoutTheOldMessage', async () => {
    renderPage();
    const create = await openCreate();
    fireEvent.submit(create.getByRole('button', { name: 'Create' }));
    await create.findByText('Name is required.');
    fireEvent.click(create.getByRole('button', { name: 'Cancel' }));

    const edit = await openEdit();

    expect(edit.queryByText('Name is required.')).toBeNull();
  });

  it('GivenAPriorityWasRefused_WhenTheEditDialogIsOpenedAgain_ThenItStartsWithoutTheOldMessage', async () => {
    renderPage();
    let dialog = await openEdit();
    fireEvent.change(dialog.getByLabelText('Priority'), { target: { value: '2.5' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save Changes' }));
    await dialog.findByText('Priority must be a whole number.');
    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));

    dialog = await openEdit();

    expect(dialog.queryByText('Priority must be a whole number.')).toBeNull();
  });
});
