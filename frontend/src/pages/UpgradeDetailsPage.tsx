import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgradeById } from '../api/upgrades';
import { getProgressByUpgrade, createProgress, getStreak } from '../api/progress';
import { getReflectionsByUpgrade, createReflection } from '../api/reflections';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import Card from '../components/ui/Card';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import Modal from '../components/ui/Modal';
import UpgradeStatusBadge from '../components/upgrade/UpgradeStatusBadge';
import UpgradeTypeBadge from '../components/upgrade/UpgradeTypeBadge';
import Badge from '../components/ui/Badge';
import type { CreateProgressRequest, CreateReflectionRequest } from '../types';

const today = () => new Date().toISOString().split('T')[0];

const UpgradeDetailsPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const [progressOpen, setProgressOpen] = useState(false);
  const [reflectionOpen, setReflectionOpen] = useState(false);

  const [progressForm, setProgressForm] = useState<CreateProgressRequest>({
    upgradeId: id ?? '',
    date: today(),
    completed: false,
    note: '',
  });

  const [reflectionForm, setReflectionForm] = useState<Omit<CreateReflectionRequest, 'upgradeId'>>({
    date: today(),
    whatWorked: '',
    whatDidNotWork: '',
    nextAdjustment: '',
    difficultyRating: 3,
    benefitRating: 3,
  });

  const { data: upgrade, isLoading } = useQuery({ queryKey: ['upgrade', id], queryFn: () => getUpgradeById(id!), enabled: !!id });
  const { data: progress = [] } = useQuery({ queryKey: ['progress', id], queryFn: () => getProgressByUpgrade(id!), enabled: !!id });
  const { data: reflections = [] } = useQuery({ queryKey: ['reflections', id], queryFn: () => getReflectionsByUpgrade(id!), enabled: !!id });
  const { data: streak = 0 } = useQuery({ queryKey: ['streak', id], queryFn: () => getStreak(id!), enabled: !!id });

  const progressMutation = useMutation({
    mutationFn: createProgress,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['progress', id] }); qc.invalidateQueries({ queryKey: ['streak', id] }); setProgressOpen(false); },
  });

  const reflectionMutation = useMutation({
    mutationFn: createReflection,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['reflections', id] }); setReflectionOpen(false); },
  });

  if (isLoading || !upgrade) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <button onClick={() => navigate(-1)} className="text-sm text-blue-600 hover:underline">← Back</button>

      <Card>
        <div className="flex flex-wrap gap-2 mb-3">
          <UpgradeStatusBadge status={upgrade.status} />
          <UpgradeTypeBadge type={upgrade.type} />
          <Badge variant={upgrade.difficulty === 'EASY' ? 'green' : upgrade.difficulty === 'MEDIUM' ? 'yellow' : 'red'}>
            {upgrade.difficulty}
          </Badge>
        </div>
        <h1 className="text-2xl font-bold text-gray-900">{upgrade.title}</h1>
        {upgrade.description && <p className="text-gray-600 mt-2">{upgrade.description}</p>}
        {upgrade.motivation && (
          <div className="mt-4 bg-blue-50 rounded-lg p-3">
            <p className="text-sm text-blue-700"><strong>Motivation:</strong> {upgrade.motivation}</p>
          </div>
        )}
        {upgrade.successCriteria && (
          <div className="mt-2 bg-green-50 rounded-lg p-3">
            <p className="text-sm text-green-700"><strong>Success Criteria:</strong> {upgrade.successCriteria}</p>
          </div>
        )}
        <div className="mt-4 grid grid-cols-2 gap-4 text-sm text-gray-500">
          {upgrade.plannedStartDate && <div><span className="font-medium">Planned Start:</span> {new Date(upgrade.plannedStartDate).toLocaleDateString()}</div>}
          {upgrade.actualStartDate && <div><span className="font-medium">Actual Start:</span> {new Date(upgrade.actualStartDate).toLocaleDateString()}</div>}
          {upgrade.targetEndDate && <div><span className="font-medium">Target End:</span> {new Date(upgrade.targetEndDate).toLocaleDateString()}</div>}
        </div>
      </Card>

      {streak > 0 && (
        <div className="bg-orange-50 border border-orange-200 rounded-xl p-4 flex items-center gap-3">
          <span className="text-3xl">🔥</span>
          <div>
            <p className="font-semibold text-orange-700">{streak}-day streak!</p>
            <p className="text-sm text-orange-500">Keep it up!</p>
          </div>
        </div>
      )}

      {upgrade.trackingConfig && (
        <Card header="Tracking Configuration">
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div><span className="text-gray-500">Type:</span> <span className="font-medium">{upgrade.trackingConfig.trackingType}</span></div>
            <div><span className="text-gray-500">Frequency:</span> <span className="font-medium">{upgrade.trackingConfig.frequency}</span></div>
            {upgrade.trackingConfig.targetNumericValue && (
              <div><span className="text-gray-500">Target:</span> <span className="font-medium">{upgrade.trackingConfig.targetNumericValue} {upgrade.trackingConfig.targetUnit}</span></div>
            )}
          </div>
        </Card>
      )}

      <Card header={
        <div className="flex items-center justify-between">
          <span>Progress History ({progress.length})</span>
          <Button size="sm" onClick={() => setProgressOpen(true)}>+ Log Progress</Button>
        </div>
      }>
        {progress.length === 0 ? (
          <p className="text-gray-500 text-sm text-center py-4">No progress logged yet.</p>
        ) : (
          <div className="space-y-2 max-h-64 overflow-y-auto">
            {[...progress].reverse().map((p) => (
              <div key={p.id} className="flex items-center justify-between p-3 bg-gray-50 rounded-lg text-sm">
                <span className="text-gray-500">{new Date(p.date).toLocaleDateString()}</span>
                <div className="flex items-center gap-3">
                  {p.completed !== undefined && <Badge variant={p.completed ? 'green' : 'red'}>{p.completed ? 'Done' : 'Missed'}</Badge>}
                  {p.numericValue !== undefined && <span className="font-medium">{p.numericValue} {p.unit}</span>}
                  {p.rating !== undefined && <span>⭐ {p.rating}/5</span>}
                  {p.note && <span className="text-gray-500 italic truncate max-w-32">{p.note}</span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card header={
        <div className="flex items-center justify-between">
          <span>Reflections ({reflections.length})</span>
          <Button size="sm" variant="secondary" onClick={() => setReflectionOpen(true)}>+ Add Reflection</Button>
        </div>
      }>
        {reflections.length === 0 ? (
          <p className="text-gray-500 text-sm text-center py-4">No reflections yet.</p>
        ) : (
          <div className="space-y-3">
            {[...reflections].reverse().map((r) => (
              <div key={r.id} className="p-3 bg-gray-50 rounded-lg text-sm space-y-1">
                <p className="text-gray-400">{new Date(r.date).toLocaleDateString()}</p>
                {r.whatWorked && <p><strong>✅ What worked:</strong> {r.whatWorked}</p>}
                {r.whatDidNotWork && <p><strong>❌ What didn't:</strong> {r.whatDidNotWork}</p>}
                {r.nextAdjustment && <p><strong>🔄 Next:</strong> {r.nextAdjustment}</p>}
                <div className="flex gap-4">
                  {r.difficultyRating && <span>Difficulty: {r.difficultyRating}/5</span>}
                  {r.benefitRating && <span>Benefit: {r.benefitRating}/5</span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Modal isOpen={progressOpen} onClose={() => setProgressOpen(false)} title="Log Progress">
        <form onSubmit={(e) => { e.preventDefault(); progressMutation.mutate(progressForm); }} className="space-y-4">
          <Input label="Date" type="date" value={progressForm.date} onChange={(e) => setProgressForm({ ...progressForm, date: e.target.value })} />
          {(!upgrade.trackingConfig || upgrade.trackingConfig.trackingType === 'BOOLEAN') && (
            <label className="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" checked={progressForm.completed ?? false} onChange={(e) => setProgressForm({ ...progressForm, completed: e.target.checked })} className="w-4 h-4 rounded" />
              <span className="text-sm font-medium text-gray-700">Completed</span>
            </label>
          )}
          {upgrade.trackingConfig?.trackingType === 'NUMERIC' && (
            <Input label={`Value (${upgrade.trackingConfig.targetUnit ?? ''})`} type="number" value={progressForm.numericValue ?? ''} onChange={(e) => setProgressForm({ ...progressForm, numericValue: parseFloat(e.target.value) })} />
          )}
          {upgrade.trackingConfig?.trackingType === 'RATING' && (
            <div>
              <label className="text-sm font-medium text-gray-700">Rating (1-5)</label>
              <div className="flex gap-2 mt-2">
                {[1, 2, 3, 4, 5].map((n) => (
                  <button key={n} type="button" onClick={() => setProgressForm({ ...progressForm, rating: n })} className={`w-10 h-10 rounded-full text-sm font-semibold transition-colors ${progressForm.rating === n ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'}`}>{n}</button>
                ))}
              </div>
            </div>
          )}
          <Input label="Note" value={progressForm.note ?? ''} onChange={(e) => setProgressForm({ ...progressForm, note: e.target.value })} placeholder="Optional note" />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setProgressOpen(false)}>Cancel</Button>
            <Button type="submit" loading={progressMutation.isPending}>Save</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={reflectionOpen} onClose={() => setReflectionOpen(false)} title="Add Reflection">
        <form onSubmit={(e) => { e.preventDefault(); reflectionMutation.mutate({ ...reflectionForm, upgradeId: id! }); }} className="space-y-4">
          <Input label="Date" type="date" value={reflectionForm.date} onChange={(e) => setReflectionForm({ ...reflectionForm, date: e.target.value })} />
          <div>
            <label className="text-sm font-medium text-gray-700">What worked?</label>
            <textarea className="w-full mt-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" rows={2} value={reflectionForm.whatWorked ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, whatWorked: e.target.value })} />
          </div>
          <div>
            <label className="text-sm font-medium text-gray-700">What didn't work?</label>
            <textarea className="w-full mt-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" rows={2} value={reflectionForm.whatDidNotWork ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, whatDidNotWork: e.target.value })} />
          </div>
          <div>
            <label className="text-sm font-medium text-gray-700">Next adjustment?</label>
            <textarea className="w-full mt-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" rows={2} value={reflectionForm.nextAdjustment ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, nextAdjustment: e.target.value })} />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Input label="Difficulty (1-5)" type="number" min={1} max={5} value={reflectionForm.difficultyRating ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, difficultyRating: parseInt(e.target.value) })} />
            <Input label="Benefit (1-5)" type="number" min={1} max={5} value={reflectionForm.benefitRating ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, benefitRating: parseInt(e.target.value) })} />
          </div>
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setReflectionOpen(false)}>Cancel</Button>
            <Button type="submit" loading={reflectionMutation.isPending}>Save</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
};

export default UpgradeDetailsPage;
