/**
 * Shared wire types, under the names the frontend uses.
 *
 * Every shape here comes from `api.generated.ts`, which the backend's `FrontendWireTypesContractTest`
 * renders from the API's own request and response records and fails the build when it drifts (NFR-10,
 * ADR-053). Change the record, then run `npm run generate:wire-types` from the repository root; `npx tsc`
 * shows what the change reaches. Only what the API does not send is written by hand here, and it is
 * built on a generated type rather than beside one.
 *
 * The API sends every key of a response. A value it does not have arrives as `null`, never as a missing
 * key, so test for it with `!= null`: a check for `undefined` lets `null` through (#117).
 */
import type * as Wire from './api.generated';

/** What an account may do beyond owning its own records: the backend's `Role`. */
export type UserRole = Wire.Role;

/** A user's public profile, as returned by the auth endpoints. */
export type User = Wire.UserDto;

/**
 * What a successful sign-in, registration or refresh returns.
 *
 * Only the access token is here. The refresh credential arrives in an `HttpOnly` cookie that this
 * code cannot read, which is the point of it being there rather than in the body. `expiresAt` is
 * ISO-8601: when the access token lapses. Only the notification socket reads it, to renew a lapsed
 * token before it connects; an HTTP request renews after it is answered 401.
 */
export type AuthResponse = Wire.TokenPair;

/** The bodies of the two forms that start a session. */
export type LoginRequest = Wire.LoginRequest;
export type RegisterRequest = Wire.RegisterRequest;

/**
 * An account as an administrator sees it.
 *
 * Its own type rather than `User` for one field, `enabled`, and for one absence: there is nothing
 * here about what the account owns, because the API offers nothing. An administrator manages
 * accounts, not records.
 */
export type AdminAccount = Wire.AdminAccountDto;

/** One page of accounts, with the total behind it so a caller knows whether there are more. */
export type AccountPage = Wire.AccountPageDto;

/** Body for changing an account's role. */
export type RoleRequest = Wire.RoleRequest;

/**
 * A user-defined grouping upgrades are filed under.
 *
 * `upgradeCount` is not part of the health-area response; it is filled in by the client where a count
 * is needed, which is why it is optional.
 */
export type HealthArea = Wire.HealthAreaDto & { upgradeCount?: number };

/**
 * What kind of change an upgrade is.
 *
 * The value is bound straight from the request body, so it is generated from the backend enum rather
 * than copied: a name offered here and missing there would fail to deserialize and surface as a 500.
 *
 * `PROTOCOL` is legacy: it is accepted and rendered so an existing row displays, but it is not one of
 * the documented kinds and is deliberately absent from the create form.
 */
export type UpgradeType = Wire.UpgradeType;

/**
 * Where an upgrade stands in its lifecycle.
 *
 * `IDEA` is the initial state — not `DRAFT`. Transitions are driven server-side through the action
 * endpoints, never by assigning a status.
 */
export type UpgradeStatus = Wire.UpgradeStatus;

/**
 * A status an upgrade is moved to by an action that carries nothing but the upgrade's id.
 *
 * `PLANNED` is not one: both ways into it carry a date (planning an idea, rescheduling an abandoned
 * upgrade). Leaving it out is what makes a dateless plan impossible to write rather than refused at
 * runtime (#88). `IDEA` is only ever the starting state.
 */
export type ActionTarget = Exclude<UpgradeStatus, 'IDEA' | 'PLANNED'>;

/** How demanding an upgrade is. `HARD` is rationed: at most three may be active at once. */
export type Difficulty = Wire.Difficulty;

/** How progress is measured, which decides which fields of a progress entry are meaningful. */
export type TrackingType = Wire.TrackingType;

/**
 * How often an upgrade is expected to be acted on.
 *
 * A per-day schedule is expressed with a reminder, not with a frequency, which is why there is no
 * custom option.
 */
export type Frequency = Wire.Frequency;

/** How one upgrade is tracked. At most one per upgrade; the endpoint answers 404 until one is set up. */
export type TrackingConfig = Wire.TrackingConfigDto;

/** Body for creating or replacing an upgrade's tracking configuration. A full replacement, not a patch. */
export type SaveTrackingConfigRequest = Wire.TrackingConfigRequest;

/**
 * The core domain object: a planned health improvement.
 *
 * `overdue` is derived server-side, and `version` is the optimistic-lock counter. No request sends it
 * back yet, so an edit cannot be refused as stale (#194). `trackingConfig` is `null` when tracking is
 * not set up; its type and frequency arrive as `UpgradeTrackingType` and `UpgradeTrackingFrequency`, the
 * unions of the upgrade context's mirrors of the tracking context's enums (ADR-077).
 */
export type HealthUpgrade = Wire.UpgradeDto;

/** An upgrade's streak figures, both counted in consecutive completed days. */
export type StreakSummary = Wire.StreakDto;

/**
 * One day's logged progress. At most one entry exists per upgrade per date.
 *
 * `completed` is the server's verdict rather than what was submitted: for a configured upgrade it is
 * recomputed from the target when the entry is recorded.
 */
export type ProgressEntry = Wire.ProgressDto;

/** A written review of how an upgrade is going. Append-only — there is no edit or delete endpoint. */
export type Reflection = Wire.ReflectionDto;

/**
 * The whole dashboard in one response.
 *
 * The upgrade lists overlap deliberately — one upgrade can be active, due today and overdue at once,
 * and appears in each list it qualifies for. `streaks` is keyed by upgrade id and covers active
 * upgrades only. `weeklyCompletionRate` is already a percentage in the range 0–100, not a fraction:
 * display it as-is.
 */
export type DashboardDto = Wire.DashboardDto;

/** Upgrade counts for one health area, as shown on the dashboard. */
export type AreaSummary = Wire.AreaSummary;

/** Body for creating or replacing a health area. Updates are a full replacement, not a patch. */
export type CreateHealthAreaRequest = Wire.HealthAreaRequest;

/**
 * Body for creating or replacing an upgrade.
 *
 * Carries no status: an upgrade is created as an `IDEA` and moves only through the action endpoints.
 */
export type CreateUpgradeRequest = Wire.UpgradeRequest;

/** Body for planning an idea: the date it is meant to start, which the endpoint requires (#88). */
export type PlanRequest = Wire.PlanRequest;

/** Body for rescheduling an upgrade, including one that was abandoned. */
export type RescheduleRequest = Wire.RescheduleRequest;

/**
 * Body for logging progress, plus the `upgradeId` that selects the endpoint.
 *
 * `upgradeId` is stripped from the payload by `createProgress` — it belongs in the URL. Which of the
 * value fields matters depends on the upgrade's tracking type.
 */
export type CreateProgressRequest = Wire.ProgressRequest & { upgradeId: string };

/** Body for writing a reflection. Every field is optional; an absent date means today. */
export type CreateReflectionRequest = Wire.ReflectionRequest;

/** What a notification is about. Drives the icon and the destination a click leads to. */
export type NotificationType = Wire.NotificationType;

/** How a notification should read, which drives its colour and icon. */
export type NotificationCategory = Wire.NotificationCategory;

/**
 * A notification as delivered over either transport.
 *
 * Named `AppNotification` because `Notification` is a DOM global — shadowing it would make the browser
 * API unreachable in any module importing this one.
 */
export type AppNotification = Wire.NotificationDto;

/** How many of the caller's notifications are unread. */
export type UnreadCount = Wire.UnreadCountDto;

/**
 * A recurring nudge attached to an upgrade. `reminderTime` is "HH:mm" or "HH:mm:ss", and an empty
 * `daysOfWeek` means every day.
 */
export type Reminder = Wire.ReminderDto;

/**
 * Body for creating or rescheduling a reminder.
 *
 * An unrecognised day token is rejected by the API rather than ignored. Omitting `enabled` creates the
 * reminder enabled and leaves an existing one's state alone.
 */
export type CreateReminderRequest = Wire.ReminderRequest;
