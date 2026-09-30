import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
});
