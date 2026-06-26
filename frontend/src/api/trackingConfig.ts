import client from './client';
import type { TrackingConfig, TrackingType, Frequency } from '../types';

export interface SaveTrackingConfigRequest {
  trackingType: TrackingType;
  frequency?: Frequency;
  targetNumericValue?: number;
  targetUnit?: string;
  requiredDaily?: boolean;
}

export const getTrackingConfig = async (upgradeId: string): Promise<TrackingConfig> => {
  const { data } = await client.get<TrackingConfig>(`/api/upgrades/${upgradeId}/tracking-config`);
  return data;
};

export const saveTrackingConfig = async (
  upgradeId: string,
  req: SaveTrackingConfigRequest,
): Promise<TrackingConfig> => {
  const { data } = await client.put<TrackingConfig>(`/api/upgrades/${upgradeId}/tracking-config`, req);
  return data;
};
