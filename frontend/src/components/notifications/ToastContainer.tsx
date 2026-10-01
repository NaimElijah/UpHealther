import React from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import type { AppNotification } from '../../types';
import { aboveModalProps } from '../ui/aboveModal';
import { categoryMeta } from './notificationMeta';

/**
 * The subset of a notification a toast needs.
 *
 * Derived from `AppNotification` with `Pick`, so a field renamed there fails to compile here instead of
 * silently rendering nothing.
 */
export interface ToastData extends Pick<AppNotification, 'category' | 'title' | 'message' | 'relatedUpgradeId'> {
  id: string;
}

/**
 * @param toasts    the toasts to show, newest first; capped by the provider that owns them
 * @param onDismiss called on the close button, on selection, and by the auto-dismiss timer
 */
interface Props {
  toasts: ToastData[];
  onDismiss: (id: string) => void;
}

/**
 * Keeps a pressed toast button from taking focus. The click still acts. Without this, pressing one over
 * an open dialog moves focus out of the dialog's trap, onto the toast and then to `<body>` once it
 * unmounts. A keyboard user is unaffected, since Tab focus never goes through mousedown.
 */
const keepFocusWhereItIs = (e: React.MouseEvent) => e.preventDefault();

/**
 * Transient real-time toasts, stacked top-right above everything, an open dialog included.
 *
 * Rendered by the notification provider rather than by any page, so a toast survives navigation. Each
 * is a polite live region: announced when it appears, without interrupting whatever is being read.
 *
 * Portalled to `<body>` and marked to stay live above a dialog. An open `Modal` makes every other child
 * of `<body>` inert, and a toast is a report about what just happened, not part of the page behind. If
 * it were left in `#root`, it would be painted above the dialog and ignore every click (#74, ADR-019).
 * Tab stays inside the dialog, so while one is open a toast is reachable by pointer only. It dismisses
 * itself after a few seconds, and what it says is also in the notification list. Pressing a toast's
 * buttons acts without taking focus, so the dialog keeps it.
 */
const ToastContainer: React.FC<Props> = ({ toasts, onDismiss }) => {
  const navigate = useNavigate();
  if (toasts.length === 0) return null;

  return createPortal(
    <div {...aboveModalProps} className="fixed top-4 right-4 z-[60] flex flex-col gap-2 w-80 max-w-[calc(100vw-2rem)]">
      {toasts.map((t) => {
        const meta = categoryMeta[t.category];
        return (
          // The card is a non-interactive live region; the interactive parts are real buttons so the
          // toast is keyboard- and screen-reader-accessible.
          <div
            key={t.id}
            role="status"
            aria-live="polite"
            className={`${meta.bg} ${meta.border} border rounded-xl shadow-lg p-3 flex items-start gap-2 animate-fade-in`}
          >
            <button
              type="button"
              onMouseDown={keepFocusWhereItIs}
              onClick={() => {
                if (t.relatedUpgradeId) navigate(`/upgrades/${t.relatedUpgradeId}`);
                onDismiss(t.id);
              }}
              className="flex items-start gap-3 flex-1 min-w-0 text-left"
            >
              <span className="text-lg leading-none mt-0.5">{meta.icon}</span>
              <span className="flex-1 min-w-0">
                <span className={`block text-sm font-semibold ${meta.text} truncate`}>{t.title}</span>
                {t.message && <span className="block text-xs text-fg-subtle mt-0.5 line-clamp-2">{t.message}</span>}
              </span>
            </button>
            <button
              type="button"
              onMouseDown={keepFocusWhereItIs}
              onClick={() => onDismiss(t.id)}
              className="text-fg-faint hover:text-fg-subtle text-lg leading-none shrink-0"
              aria-label="Dismiss notification"
            >
              &times;
            </button>
          </div>
        );
      })}
    </div>,
    document.body,
  );
};

export default ToastContainer;
