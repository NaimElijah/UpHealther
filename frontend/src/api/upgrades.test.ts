import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from './client';
import { performUpgradeAction, planUpgrade } from './upgrades';

/**
 * FR-12 (#88) — the request a plan sends, which is where the Plan button broke.
 *
 * The page test replaces this module, so it cannot see what reaches the wire. The adapter is replaced
 * here instead, so the real client and its interceptors run and nothing leaves the process.
 */
describe('the upgrades API', () => {
  const realAdapter = client.defaults.adapter;
  let sent: InternalAxiosRequestConfig[] = [];

  beforeEach(() => {
    sent = [];
    client.defaults.adapter = (config) => {
      sent.push(config as InternalAxiosRequestConfig);
      return Promise.resolve({ data: {}, status: 200, statusText: 'OK', headers: {}, config } as AxiosResponse);
    };
  });

  afterEach(() => {
    client.defaults.adapter = realAdapter;
  });

  it('GivenAStartDate_WhenAnUpgradeIsPlanned_ThenTheDateIsPostedInTheBody', async () => {
    await planUpgrade('upgrade-1', '2026-04-01');

    expect(sent).toHaveLength(1);
    expect(sent[0].method).toBe('post');
    expect(sent[0].url).toBe('/api/upgrades/upgrade-1/plan');
    expect(JSON.parse(sent[0].data as string)).toEqual({ plannedStartDate: '2026-04-01' });
  });

  it('GivenAPlannedTarget_WhenItIsPassedToTheGenericAction_ThenTheBuildRefusesIt', () => {
    // Enforced by `tsc` in `npm run build`, not by this run: the generic action posts no body, and both
    // ways into PLANNED carry a date. Should its parameter ever accept PLANNED again, the directive
    // below goes unused and the build fails. The function is never called, so nothing is sent.
    const datelessPlan = () =>
      // @ts-expect-error PLANNED is not an ActionTarget
      performUpgradeAction('upgrade-1', 'PLANNED');

    expect(datelessPlan).toBeTypeOf('function');
  });
});
