import React, { useCallback, useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import Modal from '../ui/Modal';
import NavLinks from './NavLinks';

/**
 * Tailwind's `md`, the width at which `Sidebar` (`hidden md:block`) appears and this menu's button
 * (`md:hidden`) goes. Stated again here because a media query cannot read the Tailwind config; the two
 * have to move together.
 */
const SIDEBAR_SHOWN = '(min-width: 768px)';

/**
 * The navigation for a window too narrow for the sidebar: a navbar button that opens the same links
 * in a dialog, shown only below the sidebar's `md` breakpoint so the two are never on screen together.
 *
 * It is the shared `Modal` rather than a slide-in drawer on purpose. The modal already traps focus,
 * marks the page behind it inert, closes on Escape and hands focus back to this button (FR-40, ADR-013);
 * a drawer would need all of that written again for a different shape of panel. ADR-022 records the
 * choice and what would reopen it.
 *
 * It closes whenever this tab's location changes, whatever changed it: one of its own links, the
 * browser's Back button — the usual way a phone user dismisses an overlay — or anything else that
 * navigates. `Layout` stays mounted across routes, so without this the menu would sit open over the
 * page it had just been left for. A Ctrl- or Cmd-click opens a link in another tab and moves nothing
 * here, so the menu rightly stays open for a second choice.
 *
 * It also closes when the window widens far enough to show the sidebar — a tablet rotated to landscape
 * — because its own button is then hidden, and a dialog left open beside the sidebar would keep the
 * page inert with nothing on screen that opened it.
 */
const NavMenu: React.FC = () => {
  const { key } = useLocation();
  const [open, setOpen] = useState(false);
  const [lastKey, setLastKey] = useState(key);
  const close = useCallback(() => setOpen(false), []);

  // Adjusted during render rather than in an effect, so the menu is never painted over the new page
  // for a frame. It reacts to the key *changing*, rather than storing the key the menu was opened at
  // and treating that key as "open": Back restores an entry's own key, so the stored-key version would
  // reopen the menu by itself on the way back.
  if (key !== lastKey) {
    setLastKey(key);
    if (open) setOpen(false);
  }

  useEffect(() => {
    // Only while open: a closed menu has nothing to close. Guarded like ThemeProvider's query, for the
    // embedded webviews that have no matchMedia at all.
    if (!open || typeof window.matchMedia !== 'function') return;
    const sidebarShown = window.matchMedia(SIDEBAR_SHOWN);
    const closeOnceTheSidebarShows = (event: MediaQueryListEvent) => {
      if (event.matches) close();
    };
    sidebarShown.addEventListener('change', closeOnceTheSidebarShows);
    return () => sidebarShown.removeEventListener('change', closeOnceTheSidebarShows);
  }, [open, close]);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="md:hidden shrink-0 w-9 h-9 flex items-center justify-center rounded-full text-xl text-fg-subtle hover:bg-muted hover:text-fg transition-colors"
        aria-label="Open navigation"
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <span aria-hidden="true">☰</span>
      </button>
      <Modal isOpen={open} onClose={close} title="Menu">
        <NavLinks />
      </Modal>
    </>
  );
};

export default NavMenu;
