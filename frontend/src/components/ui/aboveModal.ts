/**
 * Exempts a direct child of `<body>` from the inert walk an open `Modal` performs: whatever carries it
 * belongs above a dialog, not to the page behind one. A dialog's own overlay carries it, so that two open
 * dialogs do not mark each other inert.
 *
 * It has to be rendered in the JSX rather than added from an effect. React inserts every portal before it
 * runs any effect, and the walk runs in one, so an attribute added later would arrive too late.
 *
 * Kept out of `Modal.tsx` because `react-refresh/only-export-components` warns when a `.tsx` file
 * exports a non-component value, and lint runs with `--max-warnings 0`.
 */
export const ABOVE_MODAL_ATTRIBUTE = 'data-above-modal';

/** Spread onto the root element of a portal that belongs above an open dialog. */
export const aboveModalProps = { [ABOVE_MODAL_ATTRIBUTE]: '' } as const;
