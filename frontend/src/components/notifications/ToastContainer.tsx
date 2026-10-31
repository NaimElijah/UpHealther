import React, { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { useNotifications } from '../../hooks/useNotifications';
import type { ToastData } from '../../contexts/notificationContextValue';
import { aboveModalProps } from '../ui/aboveModal';
import { categoryStyle } from './notificationMeta';

/**
 * How long a toast stays up on its own, counted only while nothing holds it (WCAG 2.2.1). Ten seconds
 * rather than the six it was: long enough to read a title and a two-line message at an unhurried pace,
 * and the toast stays for as long as the pointer is over it or focus is in it on top of that.
 */
const TOAST_LIFETIME_MS = 10_000;

/**
 * Keeps a pressed toast button from taking focus. The click still acts. Without this, pressing one over
 * an open dialog moves focus out of the dialog's trap, onto the toast and then to `<body>` once it
 * unmounts. A keyboard user is unaffected, since Tab focus never goes through mousedown.
 */
const keepFocusWhereItIs = (e: React.MouseEvent) => e.preventDefault();

/**
 * @param toast     the toast to show
 * @param onDismiss removes it, when it is dismissed, followed, or its time runs out
 */
interface ToastProps {
  toast: ToastData;
  onDismiss: (id: string) => void;
}

/**
 * One toast, which dismisses itself once it has been shown for `TOAST_LIFETIME_MS` with nothing holding
 * it. The pointer over it or focus inside it pauses the count, and leaving resumes it from where it
 * stopped, so somebody reading or about to press it is never cut off.
 *
 * A group named by its title, so a screen reader that lands in it says whose buttons these are.
 */
const Toast: React.FC<ToastProps> = ({ toast, onDismiss }) => {
  const navigate = useNavigate();
  const titleId = useId();
  const meta = categoryStyle(toast.category);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const remaining = useRef(TOAST_LIFETIME_MS);
  const held = hovered || focused;

  useEffect(() => {
    if (held) return;
    const startedAt = Date.now();
    const timer = window.setTimeout(() => onDismiss(toast.id), remaining.current);
    return () => {
      window.clearTimeout(timer);
      remaining.current -= Date.now() - startedAt;
    };
  }, [held, toast.id, onDismiss]);

  return (
    <div
      role="group"
      aria-labelledby={titleId}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget)) setFocused(false);
      }}
      className={`${meta.bg} ${meta.border} border rounded-xl shadow-lg p-3 flex items-start gap-2 animate-fade-in`}
    >
      <button
        type="button"
        onMouseDown={keepFocusWhereItIs}
        onClick={() => {
          if (toast.relatedUpgradeId) navigate(`/upgrades/${toast.relatedUpgradeId}`);
          onDismiss(toast.id);
        }}
        className="flex items-start gap-3 flex-1 min-w-0 text-left"
      >
        <span className="text-lg leading-none mt-0.5" aria-hidden="true">
          {meta.icon}
        </span>
        <span className="flex-1 min-w-0">
          <span id={titleId} className={`block text-sm font-semibold ${meta.text} truncate`}>
            {toast.title}
          </span>
          {toast.message && <span className="block text-xs text-fg-subtle mt-0.5 line-clamp-2">{toast.message}</span>}
        </span>
      </button>
      <button
        type="button"
        onMouseDown={keepFocusWhereItIs}
        onClick={() => onDismiss(toast.id)}
        className="text-fg-faint hover:text-fg-subtle text-lg leading-none shrink-0"
        aria-label="Dismiss notification"
      >
        &times;
      </button>
    </div>
  );
};

/**
 * Transient real-time toasts, stacked bottom-right above everything, an open dialog included.
 *
 * Bottom rather than top, so a toast hides no control that keeps its place (WCAG 2.4.11). At the top it
 * covered the theme switch, the bell and Logout for as long as it stayed, and just below the header it
 * covered the bell's open panel instead. Measured in Chromium and Firefox at 1280px and 320px; jsdom
 * has no layout to see it.
 *
 * Reads the toasts and their dismissal from the notification context, and is mounted once beside the
 * routes rather than by any page, so a toast survives navigation. The polite live region they are
 * announced through is mounted from the start, empty, and stays: a region inserted together with its
 * text is announced unreliably, if at all (WCAG 4.1.3). Each toast in it says itself when it appears,
 * without interrupting whatever is being read, and only itself: `role="status"` implies
 * `aria-atomic="true"`, which would have the whole region read again, every earlier toast included.
 *
 * Portalled to `<body>` and marked to stay live above a dialog. An open `Modal` makes every other child
 * of `<body>` inert, and a toast is a report about what just happened, not part of the page behind. If
 * it were left in `#root`, it would be painted above the dialog and ignore every click (#74, ADR-019).
 * Tab stays inside the dialog, so while one is open a toast is reachable by pointer only. It stays while
 * held and dismisses itself after `TOAST_LIFETIME_MS` otherwise, and what it says is also in the
 * notification list. Pressing a toast's buttons acts without taking focus, so the dialog keeps it.
 */
const ToastContainer: React.FC = () => {
  const { toasts, dismissToast } = useNotifications();

  return createPortal(
    <div
      {...aboveModalProps}
      role="status"
      aria-live="polite"
      aria-atomic="false"
      className="fixed bottom-4 right-4 z-[60] flex flex-col gap-2 w-80 max-w-[calc(100vw-2rem)]"
    >
      {toasts.map((t) => (
        <Toast key={t.id} toast={t} onDismiss={dismissToast} />
      ))}
    </div>,
    document.body,
  );
};

export default ToastContainer;
