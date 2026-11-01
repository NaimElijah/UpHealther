import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useNotifications } from '../../hooks/useNotifications';
import NotificationDropdown from './NotificationDropdown';

/** The highest count the badge spells out; above it the badge reads "9+". */
const BADGE_MAX = 9;

/**
 * Navbar bell with an unread badge, opening the notification dropdown.
 *
 * The count is capped at "9+" so the badge cannot grow wide enough to disturb the navbar, and the
 * button carries the count in its accessible name — a screen reader would otherwise announce only
 * "Notifications", losing the very thing the badge conveys.
 *
 * It is a disclosure, as the WAI-ARIA Authoring Practices describe one (WCAG 4.1.2): `aria-expanded` says
 * whether the panel is open and `aria-controls` names it, and Escape closes it. Escape, a selection and
 * View all close it and hand focus back to the bell, which would otherwise fall to `<body>` (2.4.3). An
 * action that removes its own button (Mark all read, the desktop prompt) leaves it open and hands focus back to
 * the bell too. A selection that opens a page moves focus on again to that page's heading, after this
 * (ADR-083). An outside click closes it and leaves focus where the click put it.
 */
const NotificationBell: React.FC = () => {
  const { unreadCount } = useNotifications();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const bellRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  const returnFocus = useCallback(() => bellRef.current?.focus(), []);
  const closeAndReturnFocus = useCallback(() => {
    setOpen(false);
    returnFocus();
  }, [returnFocus]);

  // Bound only while open, like the outside click below.
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeAndReturnFocus();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, closeAndReturnFocus]);

  // Close on outside click. Bound only while open, and on mousedown rather than click so the dropdown
  // is gone before a click lands on whatever is underneath it.
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div className="relative shrink-0" ref={containerRef}>
      <button
        ref={bellRef}
        type="button"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((o) => !o)}
        className="relative w-9 h-9 flex items-center justify-center rounded-full hover:bg-muted transition-colors"
        aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
      >
        <span className="text-xl" aria-hidden="true">
          🔔
        </span>
        {unreadCount > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 flex items-center justify-center rounded-full bg-danger text-fg-inverted text-[10px] font-bold">
            {unreadCount > BADGE_MAX ? `${BADGE_MAX}+` : unreadCount}
          </span>
        )}
      </button>
      {open && <NotificationDropdown id={panelId} onClose={closeAndReturnFocus} onActed={returnFocus} />}
    </div>
  );
};

export default NotificationBell;
