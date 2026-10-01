/**
 * Exempts a direct child of `<body>` from the inert walk an open `Modal` performs: whatever carries it
 * belongs above a dialog, not to the page behind one. A dialog's own overlay carries it, so that two open
 * dialogs do not mark each other inert.
 *
 * It has to be rendered in the JSX rather than added from an effect. Within one commit, React inserts
 * every portal before it runs any effect, and the walk runs in one. So when two dialogs mount together,
 * an attribute added from an effect would arrive too late.
 *
 * Kept out of `Modal.tsx` because of `aboveModalProps`. It is an object, and the lint rule
 * `react-refresh/only-export-components` exempts constant exports only when they are primitives
 * (`allowConstantExport`). Lint runs with `--max-warnings 0`.
 */
export const ABOVE_MODAL_ATTRIBUTE = 'data-above-modal';

/** Spread onto the root element of a portal that belongs above an open dialog. */
export const aboveModalProps = { [ABOVE_MODAL_ATTRIBUTE]: '' } as const;
