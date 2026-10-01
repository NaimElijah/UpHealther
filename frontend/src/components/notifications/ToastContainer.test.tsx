import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Modal from '../ui/Modal';
import ToastContainer, { type ToastData } from './ToastContainer';

/**
 * A toast against an open dialog (#74, ADR-019).
 *
 * An open `Modal` marks every other child of `<body>` inert. A toast is a notification about what just
 * happened rather than part of the page behind, so it has to escape that walk, or it is painted above
 * the dialog and cannot be dismissed. jsdom implements `inert` not at all, so these pin that the attribute
 * is absent from the toast's top-level node and stop there, as `Modal.test.tsx` does.
 */

const streakToast: ToastData = {
  id: 'toast-1',
  category: 'SUCCESS',
  title: '🔥 7-day streak!',
  message: 'Drink more water',
};

/** A page holding both, the way a toast and a dialog meet in the app: a dialog open, a toast arriving. */
const PageWithDialog: React.FC<{ dialogOpen: boolean; toasts: ToastData[]; onDismiss?: (id: string) => void }> = ({
  dialogOpen,
  toasts,
  onDismiss = () => {},
}) => (
  <MemoryRouter>
    <ToastContainer toasts={toasts} onDismiss={onDismiss} />
    <Modal isOpen={dialogOpen} onClose={() => {}} title="Log Progress">
      <p>form</p>
    </Modal>
  </MemoryRouter>
);

/** The direct child of `<body>` holding `el`, which is the level the dialog's inert walk works at. */
const topLevelNodeOf = (el: Element): Element => {
  let node = el;
  while (node.parentElement && node.parentElement !== document.body) node = node.parentElement;
  return node;
};

describe('ToastContainer', () => {
  describe('above an open dialog', () => {
    it('GivenAToastShowing_WhenADialogOpens_ThenThePageIsMadeInertButTheToastIsNot', () => {
      // The dialog's walk snapshots <body>'s children as it opens, and a toast already showing is one of
      // them. Only the exemption keeps it out.
      const { container, rerender } = render(<PageWithDialog dialogOpen={false} toasts={[streakToast]} />);

      rerender(<PageWithDialog dialogOpen toasts={[streakToast]} />);

      expect(container.getAttribute('inert')).toBe('');
      expect(topLevelNodeOf(screen.getByRole('status')).hasAttribute('inert')).toBe(false);
    });

    it('GivenAnOpenDialog_WhenAToastIsRaised_ThenTheToastIsNotInert', () => {
      const { rerender } = render(<PageWithDialog dialogOpen toasts={[]} />);

      rerender(<PageWithDialog dialogOpen toasts={[streakToast]} />);

      expect(topLevelNodeOf(screen.getByRole('status')).hasAttribute('inert')).toBe(false);
    });

    it('GivenAToastOverAnOpenDialog_WhenItsDismissIsClicked_ThenThatToastIsDismissed', () => {
      const onDismiss = vi.fn();
      render(<PageWithDialog dialogOpen toasts={[streakToast]} onDismiss={onDismiss} />);

      fireEvent.click(screen.getByRole('button', { name: 'Dismiss notification' }));

      expect(onDismiss).toHaveBeenCalledWith('toast-1');
    });
  });
});
