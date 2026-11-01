import React, { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { saveTrackingConfig } from '../../api/trackingConfig';
import { refreshUpgradeViews } from '../../lib/upgradeViews';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Select from '../ui/Select';
import Modal from '../ui/Modal';
import { numOrUndef, refusalMessage } from './formInput';
import { labelFor } from './upgradeMeta';
import type { Frequency, HealthUpgrade, SaveTrackingConfigRequest, TrackingType } from '../../types';

/** A select's choices, in the order a label map lists them. */
const optionsOf = <T extends string>(labels: Record<T, string>): { value: T; label: string }[] =>
  (Object.keys(labels) as T[]).map((value) => ({ value, label: labels[value] }));

/**
 * What the configuration form, and the card above it, call each tracking type. Keyed by the generated
 * union, so a type the API adds is a compile error here rather than an option the form lacks. A saved
 * configuration carries `UpgradeTrackingType`, the `upgrade` context's mirror of that union (ADR-077); a
 * contract test holds the two equal, so the form can open on a saved type as it is.
 */
const TRACKING_TYPE_LABELS: Record<TrackingType, string> = {
  BOOLEAN: 'Yes / No (did it)',
  NUMERIC: 'Numeric (amount vs target)',
  RATING: 'Rating (1-5)',
  TEXT: 'Text note',
};

/**
 * What the configuration form, and the card above it, call each frequency; total over the union for
 * the same reason.
 */
const FREQUENCY_LABELS: Record<Frequency, string> = {
  DAILY: 'Daily',
  WEEKLY: 'Weekly',
  MONTHLY: 'Monthly',
};

const TRACKING_TYPE_OPTIONS = optionsOf(TRACKING_TYPE_LABELS);
const FREQUENCY_OPTIONS = optionsOf(FREQUENCY_LABELS);

/** The one tracking type measured against a target, and so the only one with a target and a unit. */
const TARGETED_TYPE: TrackingType = 'NUMERIC';

/**
 * The request a save sends: the form as filled in, without the target and unit unless the type is
 * NUMERIC. Their fields are hidden for any other type, and the save replaces the whole configuration,
 * so values typed before the type changed would otherwise be stored where nothing shows them.
 */
const toRequest = (form: SaveTrackingConfigRequest): SaveTrackingConfigRequest =>
  form.trackingType === TARGETED_TYPE ? form : { ...form, targetNumericValue: null, targetUnit: null };

/**
 * @param upgradeId      the upgrade the route names
 * @param trackingConfig its configuration as the upgrade last arrived with it, or none yet
 */
interface Props {
  upgradeId: string;
  trackingConfig: HealthUpgrade['trackingConfig'];
}

/**
 * How an upgrade is tracked: its configuration as saved, and the **Configure Tracking** dialog that
 * replaces it (FR-15).
 *
 * The configuration has no read of its own: it arrives on the upgrade, which the page reads. So a save
 * refreshes every view of upgrades, the page's own included (ADR-029).
 */
const UpgradeTrackingConfig: React.FC<Props> = ({ upgradeId, trackingConfig }) => {
  const qc = useQueryClient();
  const [trackingOpen, setTrackingOpen] = useState(false);
  const [trackingForm, setTrackingForm] = useState<SaveTrackingConfigRequest>({
    trackingType: 'BOOLEAN',
    frequency: 'DAILY',
    requiredDaily: true,
  });

  // Every view of upgrades, not just this one: an upgrade carries its tracking configuration (FR-15), and
  // the daily check-in picks each entry's input from it (#189).
  const trackingMutation = useMutation({
    mutationFn: (req: SaveTrackingConfigRequest) => saveTrackingConfig(upgradeId, req),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  /**
   * Edits the tracking configuration from what is saved, on the same terms as the progress dialog
   * (`UpgradeProgress`): fresh values, and no tie to a save left in flight by Cancel (#119).
   */
  const openTracking = (saved: HealthUpgrade['trackingConfig']) => {
    setTrackingForm({
      trackingType: saved?.trackingType ?? 'BOOLEAN',
      frequency: saved?.frequency ?? 'DAILY',
      targetNumericValue: saved?.targetNumericValue,
      targetUnit: saved?.targetUnit,
      requiredDaily: saved?.requiredDaily ?? true,
    });
    trackingMutation.reset();
    setTrackingOpen(true);
  };

  return (
    <>
      <Card
        header="Tracking Configuration"
        headerAction={
          <Button size="sm" variant="secondary" onClick={() => openTracking(trackingConfig)}>
            {trackingConfig ? 'Edit' : 'Configure'}
          </Button>
        }
      >
        {trackingConfig ? (
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <span className="text-fg-subtle">Type:</span>{' '}
              <span className="font-medium">{labelFor(TRACKING_TYPE_LABELS, trackingConfig.trackingType)}</span>
            </div>
            <div>
              <span className="text-fg-subtle">Frequency:</span>{' '}
              <span className="font-medium">{labelFor(FREQUENCY_LABELS, trackingConfig.frequency)}</span>
            </div>
            {trackingConfig.trackingType === TARGETED_TYPE && trackingConfig.targetNumericValue != null && (
              <div>
                <span className="text-fg-subtle">Target:</span>{' '}
                <span className="font-medium">
                  {trackingConfig.targetNumericValue} {trackingConfig.targetUnit}
                </span>
              </div>
            )}
          </div>
        ) : (
          <p className="text-fg-subtle text-sm text-center py-4">
            No tracking configured yet. Configure how you'll track this upgrade.
          </p>
        )}
      </Card>

      <Modal isOpen={trackingOpen} onClose={() => setTrackingOpen(false)} title="Configure Tracking">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            trackingMutation.mutate(toRequest(trackingForm), { onSuccess: () => setTrackingOpen(false) });
          }}
          className="space-y-4"
        >
          <Select
            label="Tracking type"
            value={trackingForm.trackingType}
            onChange={(e) => setTrackingForm({ ...trackingForm, trackingType: e.target.value as TrackingType })}
            error={refusalMessage(trackingMutation.error, 'trackingType')}
            options={TRACKING_TYPE_OPTIONS}
          />
          <Select
            label="Frequency"
            value={trackingForm.frequency ?? 'DAILY'}
            onChange={(e) => setTrackingForm({ ...trackingForm, frequency: e.target.value as Frequency })}
            options={FREQUENCY_OPTIONS}
          />
          {trackingForm.trackingType === TARGETED_TYPE && (
            <div className="grid grid-cols-2 gap-4">
              <Input
                label="Target value"
                type="number"
                value={trackingForm.targetNumericValue ?? ''}
                onChange={(e) => setTrackingForm({ ...trackingForm, targetNumericValue: numOrUndef(e.target.value) })}
              />
              <Input
                label="Unit"
                value={trackingForm.targetUnit ?? ''}
                placeholder="e.g. liters"
                onChange={(e) => setTrackingForm({ ...trackingForm, targetUnit: e.target.value })}
              />
            </div>
          )}
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={trackingForm.requiredDaily ?? false}
              onChange={(e) => setTrackingForm({ ...trackingForm, requiredDaily: e.target.checked })}
              className="w-4 h-4 rounded"
            />
            <span className="text-sm font-medium text-fg-muted">Required daily</span>
          </label>
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setTrackingOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={trackingMutation.isPending}>
              Save
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
};

export default UpgradeTrackingConfig;
