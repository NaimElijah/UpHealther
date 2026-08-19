import React, { useEffect, useId } from 'react';

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
 * open, so a closed modal costs nothing and several on one page cannot fight over the key. Nothing is
 * rendered when closed, which means the body is unmounted and its form state resets between openings.
 *
 * Deliberately no `aria-modal`. It would tell assistive technology that everything outside the dialog
 * is inert, and nothing here makes that true: no focus trap, so Tab walks straight out into the page
 * behind, and focus is neither moved in on open nor restored on close. Claiming it would leave a
 * screen-reader user tabbing to controls the dialog has removed from their buffer — worse than the
 * plain `dialog` role, which at least announces this panel honestly. Closing the gap properly means a
 * native `<dialog>` with `showModal()`; see `docs/requirements/requirements.md` §6.
 */
const Modal: React.FC<ModalProps> = ({ isOpen, onClose, title, children }) => {
  // Three modals can share one page, so the title's id has to be unique per instance — a hard-coded
  // one would make every dialog resolve the same accessible name.
  const titleId = useId();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    if (isOpen) document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6">
      <div className="absolute inset-0 bg-overlay/50" onClick={onClose} />
      <div
        role="dialog"
        aria-labelledby={titleId}
        className="relative z-10 flex max-h-full w-full min-w-0 max-w-lg flex-col overflow-hidden rounded-xl bg-surface border border-line-strong shadow-xl"
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
    </div>
  );
};

export default Modal;
