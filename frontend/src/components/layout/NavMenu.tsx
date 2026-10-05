import React, { useCallback, useState } from 'react';
import Modal from '../ui/Modal';
import NavLinks from './NavLinks';

/**
 * The navigation for a window too narrow for the sidebar: a navbar button that opens the same links
 * in a dialog, shown only below the sidebar's `md` breakpoint so the two are never on screen together.
 *
 * It is the shared `Modal` rather than a slide-in drawer on purpose. The modal already traps focus,
 * marks the page behind it inert, closes on Escape and hands focus back to this button (FR-40, ADR-013);
 * a drawer would need all of that written again for a different shape of panel. Following a link
 * closes it, since the user has chosen where to go.
 */
const NavMenu: React.FC = () => {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);

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
        <NavLinks onNavigate={close} />
      </Modal>
    </>
  );
};

export default NavMenu;
