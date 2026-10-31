import { describe, it } from 'vitest';
import type { HealthUpgrade } from './index';

type EmbeddedTrackingConfig = NonNullable<HealthUpgrade['trackingConfig']>;

describe('the tracking configuration an upgrade carries', () => {
  // Enforced by `tsc` in `npm run build`, not by this run, so these assert nothing at run time: a
  // runtime check here could not fail. NFR-10: the embedded configuration's enums are the API's closed
  // sets, so a value the API never sends is a compile error. Should either field widen back to `string`,
  // its directive goes unused and the build fails. The functions are never called.

  it('GivenTheEmbeddedConfiguration_WhenItsTrackingTypeIsComparedWithAValueTheApiNeverSends_ThenTheBuildRefusesIt', () => {
    void ((config: EmbeddedTrackingConfig) =>
      // @ts-expect-error NUMERC is not a tracking type
      config.trackingType === 'NUMERC');
  });

  it('GivenTheEmbeddedConfiguration_WhenItsFrequencyIsComparedWithAValueTheApiNeverSends_ThenTheBuildRefusesIt', () => {
    void ((config: EmbeddedTrackingConfig) =>
      // @ts-expect-error HOURLY is not a frequency
      config.frequency === 'HOURLY');
  });
});
