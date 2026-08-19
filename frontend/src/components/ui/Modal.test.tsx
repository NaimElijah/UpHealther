import React, { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import Modal from './Modal';

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

/** Opens a modal from a button, so closing can be observed the way a page uses it. */
const Openable: React.FC = () => {
  const [open, setOpen] = useState(true);
  return (
    <Modal isOpen={open} onClose={() => setOpen(false)} title="New Health Area">
      <p>body</p>
    </Modal>
  );
};

describe('Modal', () => {
  describe('announcing itself', () => {
    it('GivenAnOpenModal_WhenItRenders_ThenItIsADialogNamedByItsTitle', () => {
      renderModal();

      expect(screen.getByRole('dialog', { name: 'New Health Area' })).toBeDefined();
    });

    it('GivenAnOpenModal_WhenItRenders_ThenItIsMarkedAsModal', () => {
      renderModal();

      expect(screen.getByRole('dialog').getAttribute('aria-modal')).toBe('true');
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
    it('GivenAnOpenModal_WhenTheBackdropIsClicked_ThenItCloses', () => {
      const onClose = vi.fn();
      const { container } = renderModal(onClose);

      fireEvent.click(container.firstElementChild!.firstElementChild!);

      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('GivenAModalThatHasClosed_WhenEscapeIsPressed_ThenNothingListensForIt', () => {
      render(<Openable />);
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByRole('dialog')).toBeNull();

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(screen.queryByRole('dialog')).toBeNull();
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
