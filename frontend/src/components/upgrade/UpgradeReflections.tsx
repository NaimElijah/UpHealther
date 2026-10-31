import React, { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createReflection } from '../../api/reflections';
import { parseLocalDate, todayLocal } from '../../lib/localDate';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Modal from '../ui/Modal';
import { intOrUndef, refusalMessage } from './formInput';
import { reflectionsQuery, type SectionRead } from './upgradeDetailsQueries';
import type { CreateReflectionRequest, Reflection } from '../../types';

/** What the Add Reflection form holds: the request, for a date it always has. */
type ReflectionForm = CreateReflectionRequest & { date: string };

/** Where a new reflection's difficulty and benefit start: the middle of their 1-5 scale. */
const DEFAULT_REFLECTION_RATING = 3;

/** A blank reflection for today, set each time **Add Reflection** opens, for the reasons #119 gives. */
const newReflectionForm = (): ReflectionForm => ({
  date: todayLocal(),
  whatWorked: '',
  whatDidNotWork: '',
  nextAdjustment: '',
  difficultyRating: DEFAULT_REFLECTION_RATING,
  benefitRating: DEFAULT_REFLECTION_RATING,
});

/**
 * @param upgradeId the upgrade the reflections are about
 * @param read      the page's read of its reflections
 */
interface Props {
  upgradeId: string;
  read: SectionRead<Reflection[]>;
}

/**
 * An upgrade's reflections (FR-24), newest first as the API returns them, and the **Add Reflection**
 * dialog. A saved reflection refreshes this list alone.
 */
const UpgradeReflections: React.FC<Props> = ({ upgradeId, read }) => {
  const qc = useQueryClient();
  const { data: reflections = [] } = read;

  const [reflectionOpen, setReflectionOpen] = useState(false);
  const [reflectionForm, setReflectionForm] = useState(newReflectionForm);

  // Closing the dialog is passed to each `mutate` call, for the reason `UpgradeProgress` gives.
  const reflectionMutation = useMutation({
    mutationFn: (body: Omit<CreateReflectionRequest, 'upgradeId'>) => createReflection(upgradeId, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: reflectionsQuery(upgradeId).queryKey }),
  });

  /** Starts a new reflection, on the same terms as a new progress entry (`UpgradeProgress`). */
  const openReflection = () => {
    setReflectionForm(newReflectionForm());
    reflectionMutation.reset();
    setReflectionOpen(true);
  };

  return (
    <>
      <Card
        header={`Reflections (${reflections.length})`}
        headerAction={
          <Button size="sm" variant="secondary" onClick={openReflection}>
            + Add Reflection
          </Button>
        }
      >
        {reflections.length === 0 ? (
          <p className="text-fg-subtle text-sm text-center py-4">No reflections yet.</p>
        ) : (
          <div className="space-y-3">
            {reflections.map((r) => (
              <div key={r.id} className="p-3 bg-sunken rounded-lg text-sm space-y-1">
                <p className="text-fg-faint">{parseLocalDate(r.date).toLocaleDateString()}</p>
                {r.whatWorked && (
                  <p>
                    <strong>✅ What worked:</strong> {r.whatWorked}
                  </p>
                )}
                {r.whatDidNotWork && (
                  <p>
                    <strong>❌ What didn't:</strong> {r.whatDidNotWork}
                  </p>
                )}
                {r.nextAdjustment && (
                  <p>
                    <strong>🔄 Next:</strong> {r.nextAdjustment}
                  </p>
                )}
                <div className="flex gap-4">
                  {r.difficultyRating && <span>Difficulty: {r.difficultyRating}/5</span>}
                  {r.benefitRating && <span>Benefit: {r.benefitRating}/5</span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Modal isOpen={reflectionOpen} onClose={() => setReflectionOpen(false)} title="Add Reflection">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            reflectionMutation.mutate(reflectionForm, { onSuccess: () => setReflectionOpen(false) });
          }}
          className="space-y-4"
        >
          <Input
            label="Date"
            type="date"
            value={reflectionForm.date}
            onChange={(e) => setReflectionForm({ ...reflectionForm, date: e.target.value })}
            error={refusalMessage(reflectionMutation.error, 'date')}
          />
          <div>
            <label htmlFor="reflection-what-worked" className="text-sm font-medium text-fg-muted">
              What worked?
            </label>
            <textarea
              id="reflection-what-worked"
              className="w-full mt-1 rounded-lg border border-line-strong bg-surface px-3 py-2 text-sm focus:outline-none focus:ring-2"
              rows={2}
              value={reflectionForm.whatWorked ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, whatWorked: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="reflection-what-did-not-work" className="text-sm font-medium text-fg-muted">
              What didn't work?
            </label>
            <textarea
              id="reflection-what-did-not-work"
              className="w-full mt-1 rounded-lg border border-line-strong bg-surface px-3 py-2 text-sm focus:outline-none focus:ring-2"
              rows={2}
              value={reflectionForm.whatDidNotWork ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, whatDidNotWork: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="reflection-next-adjustment" className="text-sm font-medium text-fg-muted">
              Next adjustment?
            </label>
            <textarea
              id="reflection-next-adjustment"
              className="w-full mt-1 rounded-lg border border-line-strong bg-surface px-3 py-2 text-sm focus:outline-none focus:ring-2"
              rows={2}
              value={reflectionForm.nextAdjustment ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, nextAdjustment: e.target.value })}
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Input
              label="Difficulty (1-5)"
              type="number"
              min={1}
              max={5}
              value={reflectionForm.difficultyRating ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, difficultyRating: intOrUndef(e.target.value) })}
            />
            <Input
              label="Benefit (1-5)"
              type="number"
              min={1}
              max={5}
              value={reflectionForm.benefitRating ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, benefitRating: intOrUndef(e.target.value) })}
            />
          </div>
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setReflectionOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={reflectionMutation.isPending}>
              Save
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
};

export default UpgradeReflections;
