# ADR-019: A toast stays live above an open dialog

- **Status:** Accepted
- **Date:** 2026-10-02
- **Supersedes in part:** [ADR-013](ADR-013-trapping-focus-without-a-native-dialog.md). Its *What this
  makes hard* paragraph records a toast going inert under an open dialog; that is no longer true. Its
  `data-modal-overlay` attribute is now `data-above-modal`.
- **Scope:** `frontend/src/components/notifications/ToastContainer.tsx`,
  `frontend/src/components/ui/aboveModal.ts` and `Modal`'s inert walk. No new dependency.
- **Issue:** [#74](https://github.com/NaimElijah/UpHealther/issues/74)

## Context

ADR-013 made `aria-modal="true"` honest. `Modal` portals its overlay to `<body>` and, while a dialog
is open, marks every *other* child of `<body>` `inert`. It skips a node only if the node carries the
overlay attribute, which is how two open dialogs avoid marking each other.

`ToastContainer` was rendered by `NotificationProvider` inside `#root`. So it went inert with the rest
of the page, while still painted at `z-[60]` above the dialog's `z-50`. A toast raised while a dialog
was open was **visible, on top, and unclickable**. Its dismiss button did nothing, and it stayed until its
six-second timer expired.

By the letter of `aria-modal`, that was correct: the page behind a modal dialog is not interactive.
But a control painted above everything that silently ignores the pointer reads as broken to anyone who
meets it. And a toast is not part of the page behind. It is the application reporting what just
happened, often the very thing the dialog did.

## Decision

**A toast belongs above an open dialog, and stays live there.**

- `ToastContainer` portals itself to `<body>`, the same as the dialog's overlay.
- It carries the attribute the inert walk skips.

**The attribute is renamed for the job it now does.** `data-modal-overlay` became `data-above-modal`.
It now marks two different things, and what they share is "skip me when marking the page behind a
dialog", not "I am an overlay".

- The name lives once, in `components/ui/aboveModal.ts`. The walk reads it from there, and both
  portals spread it from there.
- It is a module of its own because `react-refresh/only-export-components` forbids exporting it from
  `Modal.tsx`.

The attribute is still set in the JSX, for the reason ADR-013 gives: React inserts portals before it runs
any effect. It is what covers a toast that is **already showing** when a dialog opens, since the walk
snapshots `<body>`'s children at that moment. A toast that **arrives** after the dialog opened is
outside the snapshot either way.

## Consequences

**What this makes easy.** A toast over a dialog can be dismissed, and its link can be followed. Its
live region is no longer inside an inert subtree, so assistive technology is no longer told it doesn't
exist.

**What this makes hard.**

- **`aria-modal` is now true of the page and not of the toast.** One small, deliberate region outside the
  dialog is reachable while the dialog says nothing is. A screen reader may still choose not to
  announce a live region outside a modal dialog. That is the browser's and the reader's call, and it is
  not verified here.
- **The toast is reachable by pointer only.** Tab stays confined to the dialog by design (ADR-013), so a
  keyboard user cannot reach a toast's buttons while a dialog is open. The toast dismisses itself after
  six seconds, and everything it says is also in the notification list.
- **Pressing a toast's buttons does not take focus.** Their mousedown is prevented. Otherwise a pressed
  toast would take focus out of the dialog's trap, and then drop it on `<body>` once it unmounted.
- **Any future portal has to choose.** Spreading `aboveModalProps` means "this stays live above a
  dialog", and that is a claim about the product, not a styling choice.

**What cannot be verified.** As in ADR-013: jsdom implements `inert` not at all, so
`ToastContainer.test.tsx` pins that the toast's top-level node is never marked, and stops there.

## Alternatives considered

- **Leave the toast inert.** This is the status quo, and it is what `aria-modal` literally asks for.
  Rejected, because the result is a control drawn on top of everything that ignores clicks.
  *Revisit if* toasts ever carry content that belongs to the page behind, rather than reports about what
  just happened.
- **Paint the toast below the dialog** (`z-40`). Consistent with inertness: it is out of reach and also
  out of sight under the scrim. Rejected, because it hides the confirmation of the action the user just
  took in the dialog, which is the toast most likely to matter.
- **Hold toasts until every dialog closes.** Rejected: it needs a queue shared between `Modal` and the
  notification provider, which is machinery for a six-second message.
- **Put the toast inside the dialog's subtree.** Rejected: toasts are global and outlive any one
  dialog. It would also make the toast part of what Tab cycles through, changing the dialog's own focus
  order.

*Revisit this record when* a toast carries an action somebody must be able to complete with the
keyboard. At that point the trap's boundary, not just the inert walk, has to know about it.
