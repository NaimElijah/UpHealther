import React, { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { createEvent, fireEvent, render, screen } from '@testing-library/react';
import Modal from './Modal';

/**
 * Two jsdom limits shape how the focus tests below are written, and both are worth knowing before
 * changing one.
 *
 * jsdom moves focus on Tab for nobody — it implements no tab order at all. So every Tab test fires the
 * key and asserts two things: that `defaultPrevented` is set, because in a real browser our `.focus()`
 * would otherwise be overwritten by the browser's own move a moment later; and where focus actually
 * ended up, because without that the test would pass against no handler whatsoever. Asserting merely
 * that focus stayed *inside* the dialog would be vacuous for the same reason.
 *
 * jsdom also implements `inert` not at all — `'inert' in HTMLElement.prototype` is false. The
 * inertness tests therefore pin that the attribute is set and cleared on the right nodes, and nothing
 * more. That a browser honours it is not covered here and cannot be; see ADR-013.
 *
 * One smaller trap: `fireEvent.click` does not focus in jsdom, so a trigger has to be `.focus()`ed
 * explicitly before it is clicked, or there is nothing for the dialog to restore focus to.
 */

const renderModal = (onClose = vi.fn(), title = 'New Health Area') =>
  render(
    <Modal isOpen onClose={onClose} title={title}>
      <p>body</p>
    </Modal>,
  );

/** Two modals mounted at once, which is what `HealthAreasPage` does with its create/edit/delete trio. */
const TwoModals: React.FC = () => (
  <>
    <Modal isOpen onClose={() => {}} title="New Health Area">
      <p>create</p>
    </Modal>
    <Modal isOpen onClose={() => {}} title="Delete Health Area">
      <p>confirm</p>
    </Modal>
  </>
);

/** Holds the open state itself, so closing can be observed the way a page drives it. */
const Openable: React.FC = () => {
  const [open, setOpen] = useState(true);
  return (
    <Modal isOpen={open} onClose={() => setOpen(false)} title="New Health Area">
      <p>body</p>
    </Modal>
  );
};

/** A body with several controls, so the Tab cycle has a first, a middle and a last to move between. */
const RichBody: React.FC<{ onClose?: () => void }> = ({ onClose = () => {} }) => (
  <Modal isOpen onClose={onClose} title="New Health Area">
    <input aria-label="Name" />
    <input aria-label="Description" />
    <button type="submit">Save</button>
  </Modal>
);

/** A button that opens the dialog, so focus has somewhere real to be restored to. */
const WithTrigger: React.FC<{ removeTriggerOnClose?: boolean }> = ({ removeTriggerOnClose = false }) => {
  const [open, setOpen] = useState(false);
  const [triggerGone, setTriggerGone] = useState(false);
  return (
    <>
      {!triggerGone && (
        <button
          onClick={() => setOpen(true)}
        >
          Open
        </button>
      )}
      <Modal
        isOpen={open}
        onClose={() => {
          if (removeTriggerOnClose) setTriggerGone(true);
          setOpen(false);
        }}
        title="New Health Area"
      >
        <p>body</p>
      </Modal>
    </>
  );
};

/** Focuses and clicks, because a jsdom click alone leaves `document.activeElement` on `<body>`. */
const openVia = (trigger: HTMLElement) => {
  trigger.focus();
  fireEvent.click(trigger);
};

/** Fires a Tab and hands back the event, so the caller can assert on `defaultPrevented`. */
const pressTab = (on: HTMLElement, shiftKey = false) => {
  const event = createEvent.keyDown(on, { key: 'Tab', shiftKey });
  fireEvent(on, event);
  return event;
};

describe('Modal', () => {
  describe('announcing itself', () => {
    it('GivenAnOpenModal_WhenItRenders_ThenItIsADialogNamedByItsTitle', () => {
      renderModal();

      expect(screen.getByRole('dialog', { name: 'New Health Area' })).toBeDefined();
    });

    // Replaces a test that pinned the opposite. `aria-modal` was withheld for as long as nothing made
    // it true; the trap and the inert page behind it are what earned the claim.
    it('GivenAnOpenModal_WhenItRenders_ThenItClaimsTheRestOfThePageIsInert', () => {
      renderModal();

      expect(screen.getByRole('dialog').getAttribute('aria-modal')).toBe('true');
    });

    // Pins the attribute, not the behaviour — jsdom enforces no inertness whatsoever. RTL's own
    // container is a direct child of <body>, which is exactly what the walk marks.
    it('GivenAnOpenModal_WhenItRenders_ThenTheContentBehindIsMarkedInert', () => {
      const { container } = renderModal();

      expect(container.getAttribute('inert')).toBe('');
    });

    it('GivenAModalThatHasClosed_WhenItUnmounts_ThenTheContentBehindIsNoLongerInert', () => {
      const { container } = render(<Openable />);

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(container.getAttribute('inert')).toBeNull();
    });

    // The overlays skip each other by attribute rather than by a registry built in an effect, because
    // React inserts both portals before it runs either effect.
    it('GivenTwoOpenModals_WhenTheyRender_ThenNeitherMarksTheOtherInert', () => {
      render(<TwoModals />);

      const overlays = document.querySelectorAll('[data-modal-overlay]');
      expect(overlays.length).toBe(2);
      overlays.forEach((overlay) => expect(overlay.getAttribute('inert')).toBeNull());
    });

    it('GivenAnOpenModal_WhenItRenders_ThenTheCloseControlIsLabelled', () => {
      renderModal();

      expect(screen.getByRole('button', { name: 'Close modal' })).toBeDefined();
    });

    // A hard-coded id would make both dialogs point at the first title, so both would report the same
    // name — the failure `useId` exists to prevent, and the one a single-modal test cannot see.
    it('GivenTwoOpenModals_WhenTheyRender_ThenEachIsNamedByItsOwnTitle', () => {
      render(<TwoModals />);

      expect(screen.getByRole('dialog', { name: 'New Health Area' })).toBeDefined();
      expect(screen.getByRole('dialog', { name: 'Delete Health Area' })).toBeDefined();
    });
  });

  describe('managing focus', () => {
    it('GivenAModalThatOpens_WhenItRenders_ThenFocusMovesIntoTheDialog', () => {
      renderModal();

      expect(document.activeElement).toBe(screen.getByRole('dialog'));
    });

    it('GivenAModalOpenedFromAButton_WhenTheCloseControlIsClicked_ThenFocusReturnsToThatButton', () => {
      render(<WithTrigger />);
      const trigger = screen.getByRole('button', { name: 'Open' });
      openVia(trigger);

      fireEvent.click(screen.getByRole('button', { name: 'Close modal' }));

      expect(document.activeElement).toBe(trigger);
    });

    it('GivenAModalOpenedFromAButton_WhenEscapeIsPressed_ThenFocusReturnsToThatButton', () => {
      render(<WithTrigger />);
      const trigger = screen.getByRole('button', { name: 'Open' });
      openVia(trigger);

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(document.activeElement).toBe(trigger);
    });

    it('GivenAModalOpenedFromAButton_WhenTheBackdropIsClicked_ThenFocusReturnsToThatButton', () => {
      render(<WithTrigger />);
      const trigger = screen.getByRole('button', { name: 'Open' });
      openVia(trigger);

      fireEvent.click(document.querySelector('[data-modal-overlay]')!.firstElementChild!);

      expect(document.activeElement).toBe(trigger);
    });

    // A spy, not a focus assertion: focusing a detached element is a silent no-op in jsdom, so
    // `activeElement === body` would hold whether or not the isConnected guard were there at all.
    it('GivenTheTriggerHasBeenRemoved_WhenTheModalCloses_ThenItIsNotAskedToTakeFocusBack', () => {
      render(<WithTrigger removeTriggerOnClose />);
      const trigger = screen.getByRole('button', { name: 'Open' });
      openVia(trigger);
      const takeFocus = vi.spyOn(trigger, 'focus');

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(takeFocus).not.toHaveBeenCalled();
    });
  });

  describe('trapping the keyboard', () => {
    it('GivenFocusOnTheLastControlInADialog_WhenTabIsPressed_ThenFocusWrapsToTheFirst', () => {
      render(<RichBody />);
      const submit = screen.getByRole('button', { name: 'Save' });
      submit.focus();

      const event = pressTab(submit);

      expect(event.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close modal' }));
    });

    it('GivenFocusOnTheFirstControlInADialog_WhenShiftTabIsPressed_ThenFocusWrapsToTheLast', () => {
      render(<RichBody />);
      const close = screen.getByRole('button', { name: 'Close modal' });
      close.focus();

      const event = pressTab(close, true);

      expect(event.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Save' }));
    });

    // The one that catches a handler eager enough to hijack every Tab rather than only the two edges.
    it('GivenFocusInTheMiddleOfADialog_WhenTabIsPressed_ThenTheBrowsersOwnOrderIsLeftAlone', () => {
      render(<RichBody />);
      const name = screen.getByLabelText('Name');
      name.focus();

      const event = pressTab(name);

      expect(event.defaultPrevented).toBe(false);
      expect(document.activeElement).toBe(name);
    });

    // Focus starts on the container, so Shift+Tab from there would otherwise escape backwards.
    it('GivenFocusOnTheDialogItself_WhenShiftTabIsPressed_ThenFocusWrapsToTheLastControl', () => {
      render(<RichBody />);
      const dialog = screen.getByRole('dialog');

      const event = pressTab(dialog, true);

      expect(event.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Save' }));
    });

    it('GivenADialogWhoseOnlyControlIsItsCloseButton_WhenTabIsPressed_ThenFocusStaysOnThatButton', () => {
      renderModal();
      const close = screen.getByRole('button', { name: 'Close modal' });
      close.focus();

      const event = pressTab(close);

      expect(event.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(close);
    });
  });

  describe('closing', () => {
    it('GivenAnOpenModal_WhenEscapeIsPressed_ThenItCloses', () => {
      const onClose = vi.fn();
      renderModal(onClose);

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('GivenAnOpenModal_WhenTheCloseControlIsClicked_ThenItCloses', () => {
      const onClose = vi.fn();
      renderModal(onClose);

      fireEvent.click(screen.getByRole('button', { name: 'Close modal' }));

      expect(onClose).toHaveBeenCalledTimes(1);
    });

    // The backdrop is decorative and carries no role, so there is nothing accessible to query it by.
    // Reached through the overlay's own data attribute rather than through RTL's container, because
    // the dialog is portalled to <body> and no longer renders inside it.
    it('GivenAnOpenModal_WhenTheBackdropIsClicked_ThenItCloses', () => {
      const onClose = vi.fn();
      renderModal(onClose);

      fireEvent.click(document.querySelector('[data-modal-overlay]')!.firstElementChild!);

      expect(onClose).toHaveBeenCalledTimes(1);
    });

    // The listener is bound only while open. Asserting the dialog is still gone would pass either way;
    // only the callback distinguishes a bound listener from an unbound one.
    it('GivenAClosedModal_WhenEscapeIsPressed_ThenOnCloseIsNotCalled', () => {
      const onClose = vi.fn();
      render(
        <Modal isOpen={false} onClose={onClose} title="New Health Area">
          <p>body</p>
        </Modal>,
      );

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(onClose).not.toHaveBeenCalled();
    });

    it('GivenAnOpenModalThatHasSinceClosed_WhenEscapeIsPressedAgain_ThenItStaysClosed', () => {
      render(<Openable />);
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByRole('dialog')).toBeNull();

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  describe('several modals', () => {
    // The refcount test. A naive set-then-unset implementation passes every other test in this file
    // and fails only this one, because the second modal's close would release the whole page.
    it('GivenTwoOpenModals_WhenOneCloses_ThenTheContentBehindStaysInert', () => {
      const Pair: React.FC = () => {
        const [secondOpen, setSecondOpen] = useState(true);
        return (
          <>
            <Modal isOpen onClose={() => {}} title="New Health Area">
              <p>create</p>
            </Modal>
            <Modal isOpen={secondOpen} onClose={() => setSecondOpen(false)} title="Delete Health Area">
              <p>confirm</p>
            </Modal>
          </>
        );
      };
      const { container } = render(<Pair />);
      expect(container.getAttribute('inert')).toBe('');

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(screen.queryByRole('dialog', { name: 'Delete Health Area' })).toBeNull();
      expect(container.getAttribute('inert')).toBe('');
    });
  });

  it('GivenAClosedModal_WhenItRenders_ThenNeitherTheDialogNorItsBodyIsInTheDocument', () => {
    render(
      <Modal isOpen={false} onClose={() => {}} title="New Health Area">
        <p>body</p>
      </Modal>,
    );

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByText('body')).toBeNull();
  });
});
