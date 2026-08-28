import React, { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';

/**
 * @param isOpen   whether the dialog is shown; when false the component renders nothing at all
 * @param onClose  called by the close button, the backdrop and the Escape key alike
 * @param title    heading text, which also becomes the dialog's accessible name
 * @param children the dialog body, usually a form
 */
interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
}

/**
 * Everything the browser will move focus to with Tab. Deliberately no visibility filter: jsdom gives
 * every element a zero-sized box, so one would match nothing in any test and leave the trap untestable.
 * It costs nothing here because the call sites conditionally *render* their optional fields rather
 * than hiding them in CSS.
 */
const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Marks every other child of `<body>` inert while a dialog is open, refcounted across dialogs.
 *
 * The count is what makes two open dialogs safe: without it, closing the second would un-inert the
 * page while the first is still up. Overlays are skipped by their `data-modal-overlay` attribute,
 * which has to be set in the JSX rather than registered from an effect — React inserts both portals
 * during the mutation phase and runs neither effect until afterwards, so an effect-time registry
 * loses that race and the first dialog marks the second one inert.
 */
let openModals = 0;
let inerted: Element[] = [];

const acquireInert = () => {
  if (++openModals > 1) return;
  inerted = Array.from(document.body.children).filter(
    (el) => !el.hasAttribute('data-modal-overlay') && !el.hasAttribute('inert'),
  );
  inerted.forEach((el) => el.setAttribute('inert', ''));
};

const releaseInert = () => {
  if (--openModals > 0) return;
  inerted.forEach((el) => el.removeAttribute('inert'));
  inerted = [];
};

/**
 * Centred dialog over a dimmed backdrop, bounded by the viewport at any window size.
 *
 * The overlay's padding is what guarantees the gutter, and `max-h-full` resolves against that padded
 * box — the overlay is `fixed inset-0`, so its height is definite and the percentage resolves without
 * needing `vh` or `dvh` and their unit caveats. Inside it, `flex flex-col` with a `shrink-0` header and
 * a scrolling body is what keeps the title and close button in place while a long form scrolls under
 * them. `min-h-0` on that body is redundant — a flex item that is itself a scroll container already has
 * an automatic minimum size of zero — but it is the one declaration that states the intent, and without
 * the rule it names the body would push the panel past its own `max-height` and the scroll would never
 * engage.
 *
 * Closes on Escape as well as on the button and the backdrop; the key listener is bound only while
 * open, so a closed modal costs nothing. Nothing is rendered when closed, which means the body is
 * unmounted and its form state resets between openings.
 *
 * **`aria-modal` is claimed here, and three things make the claim true.** The overlay is portalled to
 * `<body>`, so "the page behind" is exactly "the other children of `<body>`" — a one-level walk rather
 * than an ancestor's siblings at half a dozen different depths. Those children are marked `inert`,
 * which takes them out of the accessibility tree and out of reach of the pointer, not only the
 * keyboard. And Tab is confined: focus moves into the dialog on open, returns to whatever opened it on
 * close, and wraps at both ends.
 *
 * `inert` rather than `aria-hidden` is deliberate. Testing Library's role queries treat an
 * `aria-hidden` ancestor as non-existent and know nothing about `inert`, so `aria-hidden` would leave
 * the next page test that opens a dialog unable to query the page it is standing on. `inert` is also
 * the stronger of the two in a browser. See ADR-013.
 *
 * **What this does not cover.** jsdom implements `inert` not at all, so the tests can pin that the
 * attribute is set and cleared on the right nodes and nothing more; that a browser then honours it
 * falls in the same gap §6 of the requirements records for contrast and layout. Focus falls back to
 * `<body>` when the element that opened the dialog unmounted along with it, which the health-areas
 * delete path does. And `ToastContainer` lives inside `#root`, so a toast raised while a dialog is
 * open goes inert with the rest of the page — still painted, no longer dismissable.
 */
const Modal: React.FC<ModalProps> = ({ isOpen, onClose, title, children }) => {
  // Three modals can share one page, so the title's id has to be unique per instance — a hard-coded
  // one would make every dialog resolve the same accessible name.
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    if (isOpen) document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [isOpen, onClose]);

  // Keyed on `isOpen` alone, deliberately. Adding `onClose` or `children` would re-run this on every
  // parent render and drag focus back to the dialog out from under whatever the user had tabbed to.
  // The trigger is captured inside the effect body rather than in a ref so that under StrictMode's
  // double-invoke the cleanup has already restored it before the second pass captures again.
  useEffect(() => {
    if (!isOpen) return;

    const previouslyFocused = document.activeElement as HTMLElement | null;
    acquireInert();
    dialogRef.current?.focus();

    return () => {
      // Order matters: a browser refuses to move focus into an inert subtree, so the page has to be
      // released before the trigger can take focus back. Keeping both in one effect makes that
      // explicit — split in two it would rest on declaration order, and jsdom would never show the bug.
      releaseInert();
      if (previouslyFocused?.isConnected) previouslyFocused.focus();
    };
  }, [isOpen]);

  /**
   * Confines Tab to the dialog. Bound to the dialog rather than to `document` so two open dialogs
   * cannot fight over the key — only the one holding focus sees the event.
   *
   * `preventDefault` fires at the two boundaries and nowhere else: in the middle, the browser's own
   * order beats a flat `querySelectorAll` list, which gets radio groups wrong. When focus is on the
   * dialog container itself the index is -1, and both branches then do the right thing — Shift+Tab
   * wraps to the last control instead of escaping backwards, and plain Tab is left alone because the
   * container precedes its own contents in DOM order.
   */
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== 'Tab') return;
    const dialog = dialogRef.current;
    if (!dialog) return;

    const items = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (items.length === 0) {
      // Unreachable through this component's own markup — the close button is always rendered and
      // never disabled — but focus still has to land somewhere if a future body removes it.
      e.preventDefault();
      dialog.focus();
      return;
    }

    const index = items.indexOf(document.activeElement as HTMLElement);
    const wrapToLast = e.shiftKey && index <= 0;
    const wrapToFirst = !e.shiftKey && index === items.length - 1;
    if (!wrapToLast && !wrapToFirst) return;

    e.preventDefault();
    (wrapToLast ? items[items.length - 1] : items[0]).focus();
  };

  if (!isOpen) return null;

  return createPortal(
    <div
      data-modal-overlay=""
      className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6"
    >
      <div className="absolute inset-0 bg-overlay/50" onClick={onClose} />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
        className="relative z-10 flex max-h-full w-full min-w-0 max-w-lg flex-col overflow-hidden rounded-xl bg-surface border border-line-strong shadow-xl focus:outline-none"
      >
        <div className="flex shrink-0 items-center justify-between gap-4 px-6 py-4 border-b border-line">
          <h2 id={titleId} className="min-w-0 truncate text-lg font-semibold text-fg-muted">
            {title}
          </h2>
          <button
            onClick={onClose}
            className="shrink-0 text-fg-faint hover:text-fg-subtle text-2xl leading-none"
            aria-label="Close modal"
          >
            &times;
          </button>
        </div>
        <div className="min-h-0 overflow-y-auto overscroll-contain px-6 py-4">{children}</div>
      </div>
    </div>,
    document.body,
  );
};

export default Modal;
