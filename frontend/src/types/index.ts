/**
 * Shared wire types.
 *
 * These mirror the backend's response DTOs and enums. They are hand-written rather than generated, so
 * a backend change that is not reflected here is invisible until it fails at runtime — see the note on
 * {@link UpgradeType}.
 */

/**
 * What an account may do beyond owning its own records.
 *
 * Mirrors the backend `Role` enum; `FrontendEnumContractTest` fails the build if the two drift.
 */
export type UserRole = 'USER' | 'ADMIN';

/** A user's public profile, as returned by the auth endpoints. */
export interface User {
  id: string;
  name: string;
  email: string;
  role: UserRole;
  createdAt: string;
}

/**
 * What a successful sign-in, registration or refresh returns.
 *
 * Only the access token is here. The refresh credential arrives in an `HttpOnly` cookie that this
 * code cannot read, which is the point of it being there rather than in the body.
 */
export interface AuthResponse {
  accessToken: string;
  /** ISO-8601. When the access token lapses, so the client can renew before a request fails. */
  expiresAt: string;
  user: User;
}

/**
 * An account as an administrator sees it.
 *
 * Its own type rather than `User` for one field, `enabled`, and for one absence: there is nothing
 * here about what the account owns, because the API offers nothing. An administrator manages
 * accounts, not records.
 */
export interface AdminAccount {
  id: string;
  name: string;
  email: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
}

/** One page of accounts, with the total behind it so a caller knows whether there are more. */
export interface AccountPage {
  accounts: AdminAccount[];
  page: number;
  size: number;
  total: number;
}

/**
 * A user-defined grouping upgrades are filed under.
 *
 * `upgradeCount` is not part of the health-area response; it is filled in by the client where a count
 * is needed, which is why it is optional.
 */
export interface HealthArea {
  id: string;
  userId: string;
  name: string;
  description?: string;
  priority?: number;
  icon?: string;
  color?: string;
  createdAt: string;
  upgradeCount?: number;
}

/**
 * What kind of change an upgrade is.
 *
 * Must stay identical to the backend `UpgradeType` enum — the value is bound straight from the
 * request body, so a name offered here and missing there fails to deserialize and surfaces as a 500.
 * `FrontendEnumContractTest` in the backend fails the build if the two drift apart.
 *
 * `PROTOCOL` is legacy: it is accepted and rendered so an existing row displays, but it is not one of
 * the documented kinds and is deliberately absent from the create form.
 */
export type UpgradeType =
  | 'HABIT'
  | 'ONE_TIME_ACTION'
  | 'PRODUCT_REPLACEMENT'
  | 'ROUTINE'
  | 'GOAL'
  | 'EXPERIMENT'
  | 'LEARNING_TASK'
  | 'MEDICAL_PREVENTIVE'
  | 'PROTOCOL';

/**
 * Where an upgrade stands in its lifecycle.
 *
 * `IDEA` is the initial state — not `DRAFT`. Transitions are driven server-side through the action
 * endpoints, never by assigning a status.
 */
export type UpgradeStatus =
  | 'IDEA'
  | 'PLANNED'
  | 'ACTIVE'
  | 'PAUSED'
  | 'COMPLETED'
  | 'ABANDONED';

/** How demanding an upgrade is. `HARD` is rationed: at most three may be active at once. */
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD';

/** How progress is measured, which decides which fields of a progress entry are meaningful. */
export type TrackingType = 'BOOLEAN' | 'NUMERIC' | 'TEXT' | 'RATING';

/**
 * How often an upgrade is expected to be acted on.
 *
 * Must stay identical to the backend `Frequency` enum. A per-day schedule is expressed with a
 * reminder, not with a frequency, which is why there is no custom option here.
 */
export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY';

/** How one upgrade is tracked. At most one per upgrade; absent when tracking is not set up. */
export interface TrackingConfig {
  id: string;
  upgradeId: string;
  trackingType: TrackingType;
  frequency: Frequency;
  targetNumericValue?: number;
  targetUnit?: string;
  requiredDaily?: boolean;
}

/**
 * The core domain object: a planned health improvement.
 *
 * `overdue` is derived server-side, and `version` is the optimistic-lock counter — send back the one
 * that was received or the update is rejected with a 409.
 */
export interface HealthUpgrade {
  id: string;
  userId: string;
  areaId?: string;
  title: string;
  description?: string;
  type: UpgradeType;
  status: UpgradeStatus;
  difficulty: Difficulty;
  plannedStartDate?: string;
  actualStartDate?: string;
  targetEndDate?: string;
  motivation?: string;
  successCriteria?: string;
  overdue?: boolean;
  createdAt: string;
  updatedAt?: string;
  version: number;
  trackingConfig?: TrackingConfig;
}

/** An upgrade's streak figures, both counted in consecutive completed days. */
export interface StreakSummary {
  current: number;
  longest: number;
}

/**
 * One day's logged progress. At most one entry exists per upgrade per date.
 *
 * `completed` is the server's verdict rather than what was submitted: for a configured upgrade it is
 * recomputed from the target when the entry is recorded.
 */
export interface ProgressEntry {
  id: string;
  upgradeId: string;
  userId: string;
  date: string;
  completed?: boolean;
  numericValue?: number;
  unit?: string;
  rating?: number;
  note?: string;
  createdAt: string;
}

/** A written review of how an upgrade is going. Append-only — there is no edit or delete endpoint. */
export interface Reflection {
  id: string;
  upgradeId: string;
  userId: string;
  date: string;
  difficultyRating?: number;
  benefitRating?: number;
  whatWorked?: string;
  whatDidNotWork?: string;
  nextAdjustment?: string;
  createdAt: string;
}

/**
 * The whole dashboard in one response.
 *
 * The upgrade lists overlap deliberately — one upgrade can be active, due today and overdue at once,
 * and appears in each list it qualifies for. `streaks` is keyed by upgrade id and covers active
 * upgrades only.
 */
export interface DashboardDto {
  activeUpgrades: HealthUpgrade[];
  plannedUpgrades: HealthUpgrade[];
  todayUpgrades: HealthUpgrade[];
  overdueUpgrades: HealthUpgrade[];
  /** Already a percentage in the range 0–100, not a fraction. Display it as-is. */
  weeklyCompletionRate: number;
  streaks: Record<string, number>;
  recentlyCompleted: HealthUpgrade[];
  areaSummary: AreaSummary[];
}

/** Upgrade counts for one health area, as shown on the dashboard. */
export interface AreaSummary {
  areaId: string;
  areaName: string;
  totalUpgrades: number;
  activeCount: number;
  completedCount: number;
}

/** Body for creating or replacing a health area. Updates are a full replacement, not a patch. */
export interface CreateHealthAreaRequest {
  name: string;
  description?: string;
  priority?: number;
  icon?: string;
  color?: string;
}

/**
 * Body for creating or replacing an upgrade.
 *
 * Carries no status: an upgrade is created as an `IDEA` and moves only through the action endpoints.
 */
export interface CreateUpgradeRequest {
  areaId?: string;
  title: string;
  description?: string;
  type: UpgradeType;
  difficulty: Difficulty;
  motivation?: string;
  successCriteria?: string;
  plannedStartDate?: string;
  targetEndDate?: string;
}

/**
 * Body for logging progress, plus the `upgradeId` that selects the endpoint.
 *
 * `upgradeId` is stripped from the payload by `createProgress` — it belongs in the URL. Which of the
 * value fields matters depends on the upgrade's tracking type.
 */
export interface CreateProgressRequest {
  upgradeId: string;
  date: string;
  completed?: boolean;
  numericValue?: number;
  unit?: string;
  rating?: number;
  note?: string;
}

/** Body for writing a reflection. Every field but the date is optional. */
export interface CreateReflectionRequest {
  upgradeId: string;
  date: string;
  difficultyRating?: number;
  benefitRating?: number;
  whatWorked?: string;
  whatDidNotWork?: string;
  nextAdjustment?: string;
}

/** What a notification is about. Drives the icon and the destination a click leads to. */
export type NotificationType =
  | 'UPGRADE_CREATED'
  | 'UPGRADE_PLANNED'
  | 'UPGRADE_ACTIVATED'
  | 'UPGRADE_PAUSED'
  | 'UPGRADE_COMPLETED'
  | 'UPGRADE_ABANDONED'
  | 'STREAK_ACHIEVED'
  | 'UPGRADE_OVERDUE'
  | 'REFLECTION_ADDED'
  | 'REMINDER'
  | 'CHECKIN_REMINDER';

/** How a notification should read, which drives its colour and icon. */
export type NotificationCategory = 'INFO' | 'SUCCESS' | 'WARNING' | 'REMINDER';

/**
 * A notification as delivered over either transport.
 *
 * Named `AppNotification` because `Notification` is a DOM global — shadowing it would make the browser
 * API unreachable in any module importing this one.
 */
export interface AppNotification {
  id: string;
  type: NotificationType;
  category: NotificationCategory;
  title: string;
  message?: string;
  relatedUpgradeId?: string;
  read: boolean;
  createdAt: string;
}

/** A recurring nudge attached to an upgrade. An empty `daysOfWeek` means every day. */
export interface Reminder {
  id: string;
  upgradeId: string;
  reminderTime: string;   // "HH:mm" / "HH:mm:ss"
  daysOfWeek: string[];   // e.g. ["MON","WED","FRI"]; empty means every day
  enabled: boolean;
}

/**
 * Body for creating or rescheduling a reminder.
 *
 * An unrecognised day token is rejected by the API rather than ignored. Omitting `enabled` creates the
 * reminder enabled and leaves an existing one's state alone.
 */
export interface CreateReminderRequest {
  reminderTime: string;   // "HH:mm"
  daysOfWeek?: string[];
  enabled?: boolean;
}
