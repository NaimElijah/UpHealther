# ADR-068: Desktop notifications keep their detail

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:** `frontend/` — `contexts/NotificationProvider.tsx`, which raises a desktop notification with
  the notification's title and message. FR-49 in `docs/requirements/requirements.md`. No code changes.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), finding S5.D3, which waited on
  the owner. The owner decided to keep the detail.

## Context

FR-49 lets a user opt in to desktop notifications, raised only while the tab is in the background.
`NotificationProvider` raises each with the notification's own title and message, such as a reminder
naming the upgrade it is for.

Once raised, the text belongs to the operating system:
- It stays in the notification centre after the user signs out, and after the tab is closed. Signing out
  clears nothing there, because no web API reaches a notification another page raised.
- Depending on the operating system's settings, it may show on the lock screen, to whoever is near the
  device.

An upgrade's title is the user's own words about their health. On a shared or unattended device, that
text outlives the session that showed it.

## Decision

The desktop notification keeps the title and the message. Nothing in the code changes.

- The user asked for it. Desktop notifications are off until the user opts in, and the browser asks
  for its own permission on top.
- A notification that said only "You have a notification" would meet FR-49's letter and defeat its
  purpose: the user could not tell a reminder from anything else without opening the tab.
- What the operating system shows on a lock screen, and how long it keeps it, are settings the user
  already controls for every application.

## Consequences

**Easy.** A desktop notification says what it is about, as the in-page one does.

**Hard.** On a shared device, an upgrade's title can be read in the notification centre or on the lock
screen after its owner has signed out. Nothing in the application can withdraw it.

## When to revisit

- **A user asks to hide the detail**, for example a setting that raises the notification without its
  title and message.
- **A shared-device mode is planned**, in which the application would have to assume the next person at
  the device is somebody else.

## Alternatives considered

- **A generic text, such as "UpHealther: you have a new notification".** Rejected: it keeps the words
  off the device at the cost of most of the notification's use, for every user, to cover a case the user
  can already control by not opting in.
- **A per-user setting to hide the detail.** Not built until it is asked for; it is the first revisit
  trigger above.
- **Closing the notifications the page raised, at sign-out.** Not chosen. It reaches only those raised
  since the page last loaded, whose `Notification` objects it still holds; and whether `close()` also
  removes an entry from the notification centre is the operating system's choice, not the page's.
