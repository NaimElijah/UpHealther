import type { AppNotification, HealthArea, HealthUpgrade, Reflection } from '../types';

/**
 * Wire objects for tests, built the way the API sends them.
 *
 * The API sends every key of a response, and a value it does not have as `null` (ADR-053). A fixture
 * that leaves a key out describes a shape the API never sends, and a test built on one agrees with a
 * component that mishandles the real shape: #117's tests left `rating` undefined and passed while every
 * unrated entry showed "⭐ /5". Each builder fills every key, and a test overrides only what it is about.
 */

/** An active upgrade with nothing optional set: no area, no dates, no tracking. */
export function upgradeAsSent(values: Partial<HealthUpgrade> = {}): HealthUpgrade {
  return {
    id: 'upgrade-1',
    userId: 'user-1',
    areaId: null,
    title: 'Evening walk',
    description: null,
    type: 'HABIT',
    status: 'ACTIVE',
    difficulty: 'MEDIUM',
    plannedStartDate: null,
    actualStartDate: null,
    targetEndDate: null,
    motivation: null,
    successCriteria: null,
    overdue: false,
    version: 0,
    trackingConfig: null,
    createdAt: '2026-03-01T09:00:00',
    updatedAt: '2026-03-01T09:00:00',
    ...values,
  };
}

/** A health area with a name and nothing else. */
export function healthAreaAsSent(values: Partial<HealthArea> = {}): HealthArea {
  return {
    id: 'area-1',
    userId: 'user-1',
    name: 'Movement',
    description: null,
    priority: null,
    icon: null,
    color: null,
    createdAt: '2026-03-01T09:00:00',
    updatedAt: '2026-03-01T09:00:00',
    ...values,
  };
}

/** A reflection with no ratings and no text. */
export function reflectionAsSent(values: Partial<Reflection> = {}): Reflection {
  return {
    id: 'reflection-1',
    upgradeId: 'upgrade-1',
    userId: 'user-1',
    date: '2026-03-01',
    difficultyRating: null,
    benefitRating: null,
    whatWorked: null,
    whatDidNotWork: null,
    nextAdjustment: null,
    createdAt: '2026-03-01T20:00:00',
    ...values,
  };
}

/** An unread, account-wide notification with a title and no body. */
export function notificationAsSent(values: Partial<AppNotification> = {}): AppNotification {
  return {
    id: 'notification-1',
    type: 'UPGRADE_CREATED',
    category: 'INFO',
    title: 'Upgrade created',
    message: null,
    relatedUpgradeId: null,
    read: false,
    createdAt: '2026-03-01T09:00:00',
    ...values,
  };
}
