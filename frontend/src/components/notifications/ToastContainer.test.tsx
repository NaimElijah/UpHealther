import React, { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Modal from '../ui/Modal';
import PageHeading from '../ui/PageHeading';
import ToastContainer from './ToastContainer';
import {
  NotificationContext,
  type NotificationContextType,
  type ToastData,
} from '../../contexts/notificationContextValue';

/**
 * A toast against an open dialog (#74, ADR-019).
 *
 * An open `Modal` marks every other child of `<body>` inert. A toast is a notification about what just
 * happened rather than part of the page behind, so it has to escape that walk, or it is painted above
 * the dialog and cannot be dismissed. jsdom implements `inert` not at all — it blocks no click and moves
 * no focus — so these pin that no ancestor of the toast carries the attribute, and stop there, as
 * `Modal.test.tsx` does.
 */

const streakToast: ToastData = {
  id: 'toast-1',
  category: 'SUCCESS',
  title: '🔥 7-day streak!',
  message: 'Drink more water',
  relatedUpgradeId: null,
};

/** The context the toasts are read from, holding these toasts and nothing else of interest. */
function withToasts(toasts: ToastData[], dismissToast: (id: string) => void): NotificationContextType {
  return {
    notifications: [],
    unreadCount: 0,
    connected: true,
    desktopPermission: 'default',
    markRead: () => {},
    markAllRead: () => {},
    requestDesktopPermission: () => {},
    toasts,
    dismissToast,
  };
}

/** A page holding both, the way a toast and a dialog meet in the app: a dialog open, a toast arriving. */
const PageWithDialog: React.FC<{ dialogOpen: boolean; toasts: ToastData[]; onDismiss?: (id: string) => void }> = ({
  dialogOpen,
  toasts,
  onDismiss = () => {},
}) => (
  <MemoryRouter>
    <NotificationContext.Provider value={withToasts(toasts, onDismiss)}>
      <ToastContainer />
      <Modal isOpen={dialogOpen} onClose={() => {}} title="Log Progress">
        <p>form</p>
      </Modal>
    </NotificationContext.Provider>
  </MemoryRouter>
);

describe('ToastContainer', () => {
  describe('above an open dialog', () => {
    it('GivenAToastShowing_WhenADialogOpens_ThenThePageIsMadeInertButTheToastIsNot', () => {
      // The dialog's walk snapshots <body>'s children as it opens, and a toast already showing is one of
      // them. Only the exemption keeps it out, so this is the test that pins the attribute.
      const { container, rerender } = render(<PageWithDialog dialogOpen={false} toasts={[streakToast]} />);

      rerender(<PageWithDialog dialogOpen toasts={[streakToast]} />);

      expect(container.getAttribute('inert')).toBe('');
      expect(screen.getByRole('status').closest('[inert]')).toBeNull();
    });

    it('GivenAnOpenDialog_WhenAToastIsRaised_ThenTheToastIsNotInert', () => {
      // This one pins that the toast is not inside the page. A toast portalled after the dialog opened
      // is outside the walk's snapshot with or without the exemption, but the old toast, rendered inside
      // the already-inert #root, was not.
      const { rerender } = render(<PageWithDialog dialogOpen toasts={[]} />);

      rerender(<PageWithDialog dialogOpen toasts={[streakToast]} />);

      expect(screen.getByRole('status').closest('[inert]')).toBeNull();
    });

    it('GivenAToastOverAnOpenDialog_WhenItsDismissIsClicked_ThenThatToastIsDismissed', () => {
      // jsdom delivers a click inside an inert subtree, so the click alone would pass against the old,
      // inert toast. The ancestor check is what a browser's refusal of that click turns on.
      const onDismiss = vi.fn();
      render(<PageWithDialog dialogOpen toasts={[streakToast]} onDismiss={onDismiss} />);
      const dismiss = screen.getByRole('button', { name: 'Dismiss notification' });

      expect(dismiss.closest('[inert]')).toBeNull();
      fireEvent.click(dismiss);

      expect(onDismiss).toHaveBeenCalledWith('toast-1');
    });

    it('GivenAToastOverAnOpenDialog_WhenItsButtonsArePressed_ThenNeitherTakesFocusOutOfTheDialog', () => {
      // A pressed button takes focus in Chrome and Firefox, and the trap is the dialog's own onKeyDown,
      // so focus on a toast — or on <body> once the toast unmounts — would sit outside it. jsdom moves no
      // focus on mousedown at all, so this pins the mechanism a browser honours: the default is prevented.
      render(<PageWithDialog dialogOpen toasts={[streakToast]} />);

      const buttons = within(screen.getByRole('status')).getAllByRole('button');

      expect(buttons).toHaveLength(2);
      buttons.forEach((button) => expect(fireEvent.mouseDown(button)).toBe(false));
    });
  });

  // A tab opened before a deploy that adds a category receives one this build does not know. Read
  // without a fallback, its colours threw during render, and the error boundary blanked the app.
  it('GivenACategoryThisBuildDoesNotKnow_WhenItsToastIsShown_ThenItRendersAsInfo', () => {
    const unknown = { ...streakToast, category: 'URGENT' } as unknown as ToastData;

    render(<PageWithDialog dialogOpen={false} toasts={[unknown]} />);

    expect(screen.getByRole('group', { name: '🔥 7-day streak!' }).className).toContain('bg-info-soft');
    expect(screen.getByText('🔥 7-day streak!')).toBeDefined();
  });
});

/** How long a toast stays up on its own, ten seconds, while nothing holds it. */
const TOAST_LIFETIME_MS = 10_000;

/** The toasts, held in state the way the provider holds them, so a dismissal takes one off. */
const LiveToasts: React.FC<{ initial: ToastData[] }> = ({ initial }) => {
  const [toasts, setToasts] = useState(initial);
  return (
    <MemoryRouter>
      <NotificationContext.Provider
        value={withToasts(toasts, (id) => setToasts((shown) => shown.filter((t) => t.id !== id)))}
      >
        <ToastContainer />
      </NotificationContext.Provider>
    </MemoryRouter>
  );
};

/**
 * A toast can be read before it goes: it stays while the pointer is over it or focus is in it, and counts
 * down again once neither is, for ten seconds in all. The live region it is announced through is mounted
 * before any toast arrives, since a region inserted together with its text may not be announced at all
 * (WCAG 2.2.1, 4.1.3, finding X9.14).
 */
describe('ToastContainer timing', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('GivenNoToasts_WhenTheContainerRenders_ThenAnEmptyLiveRegionIsAlreadyMounted', () => {
    render(<PageWithDialog dialogOpen={false} toasts={[]} />);

    const region = screen.getByRole('status');
    expect(region.getAttribute('aria-live')).toBe('polite');
    expect(region.textContent).toBe('');
  });

  // `role="status"` implies `aria-atomic="true"`, so with a toast already showing, a new one could have a
  // screen reader read the whole region again, every earlier toast included (review finding 10 on #407).
  it('GivenTheLiveRegion_WhenItRenders_ThenItAnnouncesEachToastOnItsOwn', () => {
    render(<PageWithDialog dialogOpen={false} toasts={[]} />);

    expect(screen.getByRole('status').getAttribute('aria-atomic')).toBe('false');
  });

  it('GivenAToastShowing_WhenTenSecondsPass_ThenItDismissesItselfAndNotBefore', () => {
    vi.useFakeTimers();
    render(<LiveToasts initial={[streakToast]} />);

    act(() => vi.advanceTimersByTime(TOAST_LIFETIME_MS - 1));
    expect(screen.queryByText('🔥 7-day streak!')).not.toBeNull();

    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByText('🔥 7-day streak!')).toBeNull();
  });

  it('GivenAToastUnderThePointer_WhenItsLifetimePasses_ThenItStaysUntilThePointerLeavesAndTheRestRunsOut', () => {
    vi.useFakeTimers();
    render(<LiveToasts initial={[streakToast]} />);
    const toast = screen.getByRole('group', { name: '🔥 7-day streak!' });
    act(() => vi.advanceTimersByTime(4_000));

    fireEvent.mouseEnter(toast);
    act(() => vi.advanceTimersByTime(TOAST_LIFETIME_MS * 2));
    expect(screen.queryByText('🔥 7-day streak!')).not.toBeNull();

    fireEvent.mouseLeave(toast);
    act(() => vi.advanceTimersByTime(TOAST_LIFETIME_MS - 4_000 - 1));
    expect(screen.queryByText('🔥 7-day streak!')).not.toBeNull();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByText('🔥 7-day streak!')).toBeNull();
  });

  // Review finding 29 on #407: elapsed time was read from the wall clock, which can jump while a toast is
  // up, and a jump forward used up the whole lifetime at once.
  it('GivenTheWallClockJumpsAnHourAhead_WhenTheToastIsHeldAndLetGo_ThenItKeepsTheTimeItHadLeft', () => {
    vi.useFakeTimers();
    render(<LiveToasts initial={[streakToast]} />);
    const toast = screen.getByRole('group', { name: '🔥 7-day streak!' });
    act(() => vi.advanceTimersByTime(1_000));
    vi.setSystemTime(Date.now() + 60 * 60 * 1_000);

    fireEvent.mouseEnter(toast);
    fireEvent.mouseLeave(toast);
    act(() => vi.advanceTimersByTime(TOAST_LIFETIME_MS - 1_000 - 1));

    expect(screen.queryByText('🔥 7-day streak!')).not.toBeNull();
  });

  it('GivenFocusInAToast_WhenItsLifetimePasses_ThenItStaysUntilFocusLeaves', () => {
    vi.useFakeTimers();
    render(<LiveToasts initial={[streakToast]} />);
    const dismiss = screen.getByRole('button', { name: 'Dismiss notification' });

    act(() => dismiss.focus());
    act(() => vi.advanceTimersByTime(TOAST_LIFETIME_MS * 2));
    expect(screen.queryByText('🔥 7-day streak!')).not.toBeNull();

    act(() => dismiss.blur());
    act(() => vi.advanceTimersByTime(TOAST_LIFETIME_MS));
    expect(screen.queryByText('🔥 7-day streak!')).toBeNull();
  });
});

const waterToast: ToastData = {
  id: 'toast-2',
  category: 'INFO',
  title: 'Time to drink water',
  message: null,
  relatedUpgradeId: null,
};

/** The toasts over a page that has its heading and one other control, as every page has. */
const ToastsOverAPage: React.FC<{ initial: ToastData[] }> = ({ initial }) => (
  <>
    <PageHeading>Dashboard</PageHeading>
    <button type="button">Somewhere on the page</button>
    <LiveToasts initial={initial} />
  </>
);

/** The Dismiss button of the toast titled `title`. */
const dismissOf = (title: string) =>
  within(screen.getByRole('group', { name: title })).getByRole('button', { name: 'Dismiss notification' });

/**
 * A toast pressed from the keyboard goes, and the button that had focus with it, so focus fell to
 * `<body>` (WCAG 2.4.3, review finding 15 on #407). It moves to the same button of the next toast, or of
 * the one before when it was the last, and to the page's heading when no toast is left. A pointer press
 * never gave a toast focus, so it leaves focus where it was.
 */
describe('ToastContainer focus after a dismissal', () => {
  it('GivenTwoToastsAndFocusOnTheFirstsDismiss_WhenItIsPressed_ThenTheNextToastsDismissHasFocus', () => {
    render(<ToastsOverAPage initial={[streakToast, waterToast]} />);
    const dismiss = dismissOf('🔥 7-day streak!');
    dismiss.focus();

    fireEvent.click(dismiss);

    expect(screen.queryByRole('group', { name: '🔥 7-day streak!' })).toBeNull();
    expect(document.activeElement).toBe(dismissOf('Time to drink water'));
  });

  it('GivenTwoToastsAndFocusOnTheLastsDismiss_WhenItIsPressed_ThenTheToastBeforeItsDismissHasFocus', () => {
    render(<ToastsOverAPage initial={[streakToast, waterToast]} />);
    const dismiss = dismissOf('Time to drink water');
    dismiss.focus();

    fireEvent.click(dismiss);

    expect(document.activeElement).toBe(dismissOf('🔥 7-day streak!'));
  });

  it('GivenOneToastAndFocusOnItsDismiss_WhenItIsPressed_ThenThePageHeadingHasFocus', () => {
    render(<ToastsOverAPage initial={[streakToast]} />);
    const dismiss = dismissOf('🔥 7-day streak!');
    dismiss.focus();

    fireEvent.click(dismiss);

    expect(screen.queryByRole('group')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Dashboard' }));
  });

  it('GivenOneToastWithNoUpgradeAndFocusOnIt_WhenItIsPressed_ThenThePageHeadingHasFocus', () => {
    render(<ToastsOverAPage initial={[streakToast]} />);
    const open = within(screen.getByRole('group', { name: '🔥 7-day streak!' })).getByRole('button', {
      name: /7-day streak/,
    });
    open.focus();

    fireEvent.click(open);

    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Dashboard' }));
  });

  it('GivenFocusOnThePage_WhenAToastIsDismissedWithThePointer_ThenFocusStaysWhereItWas', () => {
    render(<ToastsOverAPage initial={[streakToast, waterToast]} />);
    const elsewhere = screen.getByRole('button', { name: 'Somewhere on the page' });
    elsewhere.focus();

    fireEvent.click(dismissOf('🔥 7-day streak!'));

    expect(document.activeElement).toBe(elsewhere);
  });
});
