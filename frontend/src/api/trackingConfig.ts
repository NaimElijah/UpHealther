import client from './client';
import type { TrackingConfig, TrackingType, Frequency } from '../types';

/**
 * Body for saving an upgrade's tracking configuration.
 *
 * `trackingType` decides which of the other fields are meaningful; the numeric target applies to
 * `NUMERIC` tracking only.
 */
export interface SaveTrackingConfigRequest {
  trackingType: TrackingType;
  frequency?: Frequency;
  targetNumericValue?: number;
  targetUnit?: string;
  requiredDaily?: boolean;
}

/** Fetches an upgrade's tracking configuration. Rejects with a 404 when none is set up. */
export const getTrackingConfig = async (upgradeId: string): Promise<TrackingConfig> => {
  const { data } = await client.get<TrackingConfig>(`/api/upgrades/${upgradeId}/tracking-config`);
  return data;
};

/**
 * Creates or replaces an upgrade's tracking configuration.
 *
 * Idempotent, and never rescores entries already logged — a past day keeps the verdict it was given
 * under the rule in force at the time.
 */
export const saveTrackingConfig = async (
  upgradeId: string,
  req: SaveTrackingConfigRequest,
): Promise<TrackingConfig> => {
  const { data } = await client.put<TrackingConfig>(`/api/upgrades/${upgradeId}/tracking-config`, req);
  return data;
};
