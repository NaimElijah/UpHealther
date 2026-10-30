import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from './client';
import { changeAccountRole, disableAccount, enableAccount } from './admin';
import { deleteHealthArea, updateHealthArea } from './healthAreas';
import { markNotificationRead } from './notifications';
import { createProgress, getProgressByUpgrade, getStreak } from './progress';
import { createReflection, getReflectionsByUpgrade } from './reflections';
import { createReminder, deleteReminder, getReminders, updateReminder } from './reminders';
import { getTrackingConfig, saveTrackingConfig } from './trackingConfig';
import {
  deleteUpgrade,
  getUpgradeById,
  performUpgradeAction,
  planUpgrade,
  rescheduleUpgrade,
  updateUpgrade,
} from './upgrades';

/**
 * S5.D4: an id is put into an API path as one segment, whatever it contains.
 *
 * React Router decodes the route's `:id`, so `/upgrades/..%2Fhealth-areas%3F` hands the page
 * `../health-areas?`. Put into a path as it is, that id climbs out of the upgrade's path and cuts the
 * rest off as a query string, and the page's reads and writes go to `/api/health-areas`. Every function
 * below takes an id into its path; each is called with that id, through the real client, and must keep
 * it inside its own segment.
 *
 * The bodies are empty, because only the path is under test.
 */
describe('an id put into an API path', () => {
  const HOSTILE = '../health-areas?';
  const ENCODED = '..%2Fhealth-areas%3F';
  const NO_BODY = {} as never;

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

  const CALLS: [string, () => Promise<unknown>, string][] = [
    ['disableAccount', () => disableAccount(HOSTILE), `/api/admin/users/${ENCODED}/disable`],
    ['enableAccount', () => enableAccount(HOSTILE), `/api/admin/users/${ENCODED}/enable`],
    ['changeAccountRole', () => changeAccountRole(HOSTILE, 'USER'), `/api/admin/users/${ENCODED}/role`],
    ['updateHealthArea', () => updateHealthArea(HOSTILE, NO_BODY), `/api/health-areas/${ENCODED}`],
    ['deleteHealthArea', () => deleteHealthArea(HOSTILE), `/api/health-areas/${ENCODED}`],
    ['markNotificationRead', () => markNotificationRead(HOSTILE), `/api/notifications/${ENCODED}/read`],
    ['getProgressByUpgrade', () => getProgressByUpgrade(HOSTILE), `/api/upgrades/${ENCODED}/progress`],
    [
      'createProgress',
      () => createProgress({ upgradeId: HOSTILE } as Parameters<typeof createProgress>[0]),
      `/api/upgrades/${ENCODED}/progress`,
    ],
    ['getStreak', () => getStreak(HOSTILE), `/api/upgrades/${ENCODED}/streak`],
    ['getReflectionsByUpgrade', () => getReflectionsByUpgrade(HOSTILE), `/api/upgrades/${ENCODED}/reflections`],
    ['createReflection', () => createReflection(HOSTILE, NO_BODY), `/api/upgrades/${ENCODED}/reflections`],
    ['getReminders', () => getReminders(HOSTILE), `/api/upgrades/${ENCODED}/reminders`],
    ['createReminder', () => createReminder(HOSTILE, NO_BODY), `/api/upgrades/${ENCODED}/reminders`],
    ['updateReminder', () => updateReminder(HOSTILE, NO_BODY), `/api/reminders/${ENCODED}`],
    ['deleteReminder', () => deleteReminder(HOSTILE), `/api/reminders/${ENCODED}`],
    ['getTrackingConfig', () => getTrackingConfig(HOSTILE), `/api/upgrades/${ENCODED}/tracking-config`],
    ['saveTrackingConfig', () => saveTrackingConfig(HOSTILE, NO_BODY), `/api/upgrades/${ENCODED}/tracking-config`],
    ['getUpgradeById', () => getUpgradeById(HOSTILE), `/api/upgrades/${ENCODED}`],
    ['updateUpgrade', () => updateUpgrade(HOSTILE, NO_BODY), `/api/upgrades/${ENCODED}`],
    ['performUpgradeAction', () => performUpgradeAction(HOSTILE, 'PAUSED'), `/api/upgrades/${ENCODED}/pause`],
    ['planUpgrade', () => planUpgrade(HOSTILE, '2026-04-01'), `/api/upgrades/${ENCODED}/plan`],
    ['rescheduleUpgrade', () => rescheduleUpgrade(HOSTILE, '2026-04-01'), `/api/upgrades/${ENCODED}/reschedule`],
    ['deleteUpgrade', () => deleteUpgrade(HOSTILE), `/api/upgrades/${ENCODED}`],
  ];

  it.each(CALLS)(
    'GivenAnIdThatClimbsAndStartsAQuery_When%sIsCalled_ThenItStaysOneEncodedSegment',
    async (_, call, path) => {
      await call();

      expect(sent.map((config) => config.url)).toEqual([path]);
    },
  );

  it.each(['..', '.', ''])('GivenTheId"%s"_WhenItIsPutIntoAPath_ThenTheCallIsRefusedAndNothingIsSent', async (id) => {
    // No encoding keeps these inside their segment: the URL parser resolves `.` and `..`, encoded or
    // not, and an empty id names the collection rather than an item of it.
    await expect(getUpgradeById(id)).rejects.toThrow(RangeError);

    expect(sent).toEqual([]);
  });
});
