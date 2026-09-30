import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getHealthAreas, createHealthArea, updateHealthArea, deleteHealthArea } from '../api/healthAreas';
import PageHeader from '../components/ui/PageHeader';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Modal from '../components/ui/Modal';
import Input from '../components/ui/Input';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import type { CreateHealthAreaRequest, HealthArea } from '../types';
import PageContainer from '../components/ui/PageContainer';
import { areaIconGlyph, DEFAULT_AREA_ICON, isIconGlyph } from '../components/ui/areaIcon';
import ErrorState from '../components/ui/ErrorState';
import { toApiError, toFormMessage } from '../api/apiError';

/**
 * The area dialogs' fields as typed.
 *
 * `priority` stays text until submit, because a number input reports a cleared field as `''`, and that
 * has to reach the request as "no priority" — not as 0, and not as NaN, which serialises to null.
 */
type AreaForm = Omit<CreateHealthAreaRequest, 'priority'> & { priority: string };

const EMPTY_FORM: AreaForm = { name: '', description: '', priority: '', icon: '', color: '' };

/** Mirror `health_areas.priority INTEGER`, which the API's `Integer` field matches. */
const PRIORITY_MIN = -2147483648;
const PRIORITY_MAX = 2147483647;

const PRIORITY_NOT_WHOLE = 'Priority must be a whole number.';
const PRIORITY_OUT_OF_RANGE = `Priority must be between ${PRIORITY_MIN} and ${PRIORITY_MAX}.`;

/** A typed priority either reads as one the request can carry, `undefined` meaning none, or says why not. */
type ParsedPriority = { valid: true; priority: number | undefined } | { valid: false; problem: string };

/**
 * Reads the typed priority: blank is no priority, and anything else must be a whole number the column
 * can hold.
 *
 * Both are checked here because the API gets neither right for the user. Jackson truncates `1.5` to `1`
 * rather than refusing it, so the user would save a number they did not type; and a number past the
 * column is refused as a malformed body, which names no field at all.
 */
const parsePriority = (text: string): ParsedPriority => {
  const trimmed = text.trim();
  if (trimmed === '') return { valid: true, priority: undefined };
  const value = Number(trimmed);
  if (!Number.isInteger(value)) return { valid: false, problem: PRIORITY_NOT_WHOLE };
  if (value < PRIORITY_MIN || value > PRIORITY_MAX) return { valid: false, problem: PRIORITY_OUT_OF_RANGE };
  return { valid: true, priority: value };
};

/**
 * Manages health areas — the folders upgrades are filed under.
 *
 * Create, edit and delete all go through modals over the same list, and each mutation invalidates the
 * area query rather than patching local state, so what is shown is always what was saved. Deleting an
 * area does not touch the upgrades filed under it.
 */
const HealthAreasPage: React.FC = () => {
  const qc = useQueryClient();
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [editArea, setEditArea] = useState<HealthArea | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [form, setForm] = useState<AreaForm>(EMPTY_FORM);
  const [formError, setFormError] = useState('');
  const [priorityError, setPriorityError] = useState('');

  const { data: areas = [], isLoading, error } = useQuery({ queryKey: ['healthAreas'], queryFn: getHealthAreas });

  const createMutation = useMutation({
    mutationFn: createHealthArea,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['healthAreas'] }); setIsCreateOpen(false); },
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, req }: { id: string; req: Partial<CreateHealthAreaRequest> }) => updateHealthArea(id, req),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['healthAreas'] }); setEditArea(null); },
  });

  const deleteMutation = useMutation({
    mutationFn: deleteHealthArea,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['healthAreas'] }); setDeleteId(null); },
  });

  /**
   * Drops the last submit's messages. Both dialogs share them, so without this a dialog could open
   * showing a refusal that belonged to the other one, or to an attempt already cancelled.
   */
  const clearErrors = () => { setFormError(''); setPriorityError(''); };

  /** Opens create on an empty form, so it inherits neither an edited area's values nor its messages. */
  const openCreate = () => {
    setForm(EMPTY_FORM);
    clearErrors();
    setIsCreateOpen(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    clearErrors();
    // Both fields are checked before either refusal returns, so one submit reports every problem.
    const nameMissing = !form.name.trim();
    const parsed = parsePriority(form.priority);
    if (nameMissing) setFormError('Name is required.');
    if (!parsed.valid) setPriorityError(parsed.problem);
    if (nameMissing || !parsed.valid) return;
    try {
      await createMutation.mutateAsync({ ...form, priority: parsed.priority });
    } catch (thrown) {
      // The name, icon and colour are each bounded by their column (BR-16), and the API names whichever
      // failed. Before this the rejection was unhandled and the dialog gave no sign of it.
      setFormError(toFormMessage(toApiError(thrown), 'name'));
    }
  };

  const handleUpdate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editArea) return;
    clearErrors();
    const parsed = parsePriority(form.priority);
    if (!parsed.valid) { setPriorityError(parsed.problem); return; }
    try {
      // The priority goes out whether or not it was touched: an update is a full replacement, so leaving
      // it out would clear it (#91).
      await updateMutation.mutateAsync({ id: editArea.id, req: { ...form, priority: parsed.priority } });
    } catch (thrown) {
      setFormError(toFormMessage(toApiError(thrown), 'name'));
    }
  };

  /**
   * Opens the edit modal pre-filled from an area; nullable fields become empty strings for the inputs.
   *
   * A stored icon the card cannot draw arrives as empty rather than as itself. Showing `water_drop` in a
   * field labelled "Icon (emoji)" next to a card showing the default would invite the user to press Save
   * and write the unusable value straight back.
   */
  const openEdit = (area: HealthArea) => {
    setEditArea(area);
    clearErrors();
    setForm({
      name: area.name,
      description: area.description ?? '',
      priority: area.priority?.toString() ?? '',
      icon: isIconGlyph(area.icon) ? (area.icon ?? '').trim() : '',
      color: area.color ?? '',
    });
  };

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (error) return <ErrorState title="Could not load your health areas." error={toApiError(error)} />;

  return (
    <PageContainer>
      <PageHeader
        title="Health Areas"
        subtitle="Organize your upgrades by health focus area"
        action={<Button onClick={openCreate}>+ New Area</Button>}
      />

      {areas.length === 0 ? (
        <EmptyState
          icon={DEFAULT_AREA_ICON}
          title="No health areas yet"
          description="Create areas like Sleep, Nutrition, Fitness to organize your upgrades."
          action={<Button onClick={openCreate}>Create Your First Area</Button>}
        />
      ) : (
        <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {areas.map((area) => (
            <Card key={area.id}>
              <div className="flex items-start gap-2">
                <span
                  className="flex h-8 w-8 shrink-0 items-center justify-center overflow-hidden text-2xl leading-none"
                  aria-hidden="true"
                >
                  {areaIconGlyph(area.icon)}
                </span>
                <div className="min-w-0">
                  <h3 className="font-semibold text-fg break-words">{area.name}</h3>
                  {area.upgradeCount !== undefined && (
                    <p className="text-xs text-fg-subtle">{area.upgradeCount} upgrade{area.upgradeCount !== 1 ? 's' : ''}</p>
                  )}
                </div>
              </div>
              {area.description && <p className="text-sm text-fg-subtle mt-2 break-words">{area.description}</p>}
              <div className="flex flex-wrap gap-2 mt-4">
                <Button size="sm" variant="secondary" onClick={() => openEdit(area)}>Edit</Button>
                <Button size="sm" variant="danger" onClick={() => setDeleteId(area.id)}>Delete</Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Modal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)} title="New Health Area">
        <form onSubmit={handleCreate} className="space-y-4">
          <Input label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="e.g. Sleep, Nutrition" error={formError} />
          <Input label="Description" value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Optional description" />
          <Input label="Priority" type="number" value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} placeholder="Optional, e.g. 1" error={priorityError} />
          <Input label="Icon (emoji)" value={form.icon ?? ''} onChange={(e) => setForm({ ...form, icon: e.target.value })} placeholder="e.g. 😴" />
          <Input label="Color (hex)" value={form.color ?? ''} onChange={(e) => setForm({ ...form, color: e.target.value })} placeholder="e.g. #6366f1" />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setIsCreateOpen(false)}>Cancel</Button>
            <Button type="submit" loading={createMutation.isPending}>Create</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={!!editArea} onClose={() => setEditArea(null)} title="Edit Health Area">
        <form onSubmit={handleUpdate} className="space-y-4">
          <Input label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} error={formError} />
          <Input label="Description" value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          <Input label="Priority" type="number" value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} error={priorityError} />
          <Input label="Icon (emoji)" value={form.icon ?? ''} onChange={(e) => setForm({ ...form, icon: e.target.value })} />
          <Input label="Color (hex)" value={form.color ?? ''} onChange={(e) => setForm({ ...form, color: e.target.value })} />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setEditArea(null)}>Cancel</Button>
            <Button type="submit" loading={updateMutation.isPending}>Save Changes</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={!!deleteId} onClose={() => setDeleteId(null)} title="Delete Health Area">
        <p className="text-fg-subtle mb-4">Are you sure you want to delete this health area? This action cannot be undone.</p>
        <div className="flex gap-2 justify-end">
          <Button variant="secondary" onClick={() => setDeleteId(null)}>Cancel</Button>
          <Button variant="danger" loading={deleteMutation.isPending} onClick={() => deleteId && deleteMutation.mutate(deleteId)}>
            Delete
          </Button>
        </div>
      </Modal>
    </PageContainer>
  );
};

export default HealthAreasPage;
