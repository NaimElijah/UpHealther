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

const HealthAreasPage: React.FC = () => {
  const qc = useQueryClient();
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [editArea, setEditArea] = useState<HealthArea | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [form, setForm] = useState<CreateHealthAreaRequest>({ name: '', description: '', icon: '', color: '' });
  const [formError, setFormError] = useState('');

  const { data: areas = [], isLoading, error } = useQuery({ queryKey: ['healthAreas'], queryFn: getHealthAreas });

  const createMutation = useMutation({
    mutationFn: createHealthArea,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['healthAreas'] }); setIsCreateOpen(false); resetForm(); },
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, req }: { id: string; req: Partial<CreateHealthAreaRequest> }) => updateHealthArea(id, req),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['healthAreas'] }); setEditArea(null); },
  });

  const deleteMutation = useMutation({
    mutationFn: deleteHealthArea,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['healthAreas'] }); setDeleteId(null); },
  });

  const resetForm = () => setForm({ name: '', description: '', icon: '', color: '' });

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    if (!form.name.trim()) { setFormError('Name is required.'); return; }
    await createMutation.mutateAsync(form);
  };

  const handleUpdate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editArea) return;
    await updateMutation.mutateAsync({ id: editArea.id, req: form });
  };

  const openEdit = (area: HealthArea) => {
    setEditArea(area);
    setForm({ name: area.name, description: area.description ?? '', icon: area.icon ?? '', color: area.color ?? '' });
  };

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (error) return <p className="text-red-500 text-center py-10">Failed to load health areas.</p>;

  return (
    <div className="max-w-4xl mx-auto">
      <PageHeader
        title="Health Areas"
        subtitle="Organize your upgrades by health focus area"
        action={<Button onClick={() => { resetForm(); setIsCreateOpen(true); }}>+ New Area</Button>}
      />

      {areas.length === 0 ? (
        <EmptyState
          icon="🎯"
          title="No health areas yet"
          description="Create areas like Sleep, Nutrition, Fitness to organize your upgrades."
          action={<Button onClick={() => setIsCreateOpen(true)}>Create Your First Area</Button>}
        />
      ) : (
        <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {areas.map((area) => (
            <Card key={area.id}>
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-2xl">{area.icon ?? '🎯'}</span>
                  <div>
                    <h3 className="font-semibold text-gray-900">{area.name}</h3>
                    {area.upgradeCount !== undefined && (
                      <p className="text-xs text-gray-500">{area.upgradeCount} upgrade{area.upgradeCount !== 1 ? 's' : ''}</p>
                    )}
                  </div>
                </div>
              </div>
              {area.description && <p className="text-sm text-gray-500 mt-2">{area.description}</p>}
              <div className="flex gap-2 mt-4">
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
          <Input label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <Input label="Description" value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          <Input label="Icon (emoji)" value={form.icon ?? ''} onChange={(e) => setForm({ ...form, icon: e.target.value })} />
          <Input label="Color (hex)" value={form.color ?? ''} onChange={(e) => setForm({ ...form, color: e.target.value })} />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setEditArea(null)}>Cancel</Button>
            <Button type="submit" loading={updateMutation.isPending}>Save Changes</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={!!deleteId} onClose={() => setDeleteId(null)} title="Delete Health Area">
        <p className="text-gray-600 mb-4">Are you sure you want to delete this health area? This action cannot be undone.</p>
        <div className="flex gap-2 justify-end">
          <Button variant="secondary" onClick={() => setDeleteId(null)}>Cancel</Button>
          <Button variant="danger" loading={deleteMutation.isPending} onClick={() => deleteId && deleteMutation.mutate(deleteId)}>
            Delete
          </Button>
        </div>
      </Modal>
    </div>
  );
};

export default HealthAreasPage;
